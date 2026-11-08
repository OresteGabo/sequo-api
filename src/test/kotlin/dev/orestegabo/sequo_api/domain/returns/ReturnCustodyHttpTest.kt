package dev.orestegabo.sequo_api.domain.returns

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.order.CustomerOrderLineRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.order.OrderProductCategory
import dev.orestegabo.sequo_api.domain.relay.RelayParcelCategory
import dev.orestegabo.sequo_api.domain.relay.RelayPointRepository
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.url=jdbc:h2:mem:return_custody_http;DB_CLOSE_DELAY=-1"],
)
class ReturnCustodyHttpTest @Autowired constructor(
    private val context: ApplicationContext,
    private val returns: ReturnRequestRecordRepository,
    private val orders: CustomerOrderRecordRepository,
    private val rateLimiter: InMemoryRateLimiter,
) {
    companion object {
        @JvmStatic @org.springframework.test.context.DynamicPropertySource
        fun isolatedPostgres(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_RETURN_CUSTODY_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) {
                "Use a disposable local return custody fixture database."
            }
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "sequo_fixture" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.dialect.PostgreSQLDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    @MockitoBean(name = "returnClock") private lateinit var returnClock: Clock
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val now = Instant.parse("2026-09-02T10:00:00Z")

    @org.junit.jupiter.api.BeforeEach
    fun resetFixtures() {
        rateLimiter.resetForTests()
        time(now)
        context.getBean(RelayPointRepository::class.java).findAll().forEach {
            it.status = "INACTIVE"; context.getBean(RelayPointRepository::class.java).save(it)
        }
    }

    @Test
    fun physicalCustodyAndAcceptedInspectionPrecedeOnePendingRefundInstruction() {
        val relay = ReturnJourneyFixtures.relayWithOperator(context)
        val fixture = ReturnJourneyFixtures.order(context, now.minusSeconds(3600))
        val collector = ReturnCustodyFixtures.actor(context, RoleCode.SUPPORT_AGENT)
        val receiver = ReturnCustodyFixtures.actor(context, RoleCode.SUPPORT_AGENT)
        val admin = ReturnCustodyFixtures.actor(context, RoleCode.ADMIN)
        val merchantId = context.getBean(CustomerOrderLineRecordRepository::class.java).findByOrderIdOrderByLineIndexAsc(fixture.orderId).single().sellerId
        val vendor = ReturnCustodyFixtures.actor(context, RoleCode.MERCHANT_OWNER, merchantId)
        val id = "custody-return-${UUID.randomUUID()}"
        val created = post("/api/returns", fixture.owner.token, ReturnJourneyFixtures.body(id, fixture.orderId))
        val pin = created["returnPin"].asText()
        val root = "/api/returns/$id"
        val collection = ReturnCustodyFixtures.collectionBody("collection-$id")
        val receipt = ReturnCustodyFixtures.receiptBody("receipt-$id")
        val inspection = ReturnCustodyFixtures.inspectionBody("inspection-$id")
        val refund = ReturnCustodyFixtures.refundBody(3000, "refund-$id")
        val startingHistory = get(root, admin.token)["history"].size()
        assertEquals(400, call("POST", "$root/refund", admin.token, refund).statusCode())
        assertEquals(400, call("POST", "$root/physical-receipt", receiver.token, receipt).statusCode())
        for (token in listOf(fixture.owner.token, vendor.token, relay.operator.token)) {
            for ((endpoint, body) in listOf("collection" to collection, "physical-receipt" to receipt, "inspection" to inspection, "refund" to refund)) {
                assertEquals(403, call("POST", "$root/$endpoint", token, body).statusCode())
            }
        }
        assertEquals(403, call("POST", "$root/refund", collector.token, refund).statusCode())

        time(now.plusSeconds(60))
        post("$root/relay-dropoff", relay.operator.token, ReturnCustodyFixtures.dropoffBody(relay.id, pin, "dropoff-$id"))
        audit(get(root, admin.token), relay.operator.id, now.plusSeconds(60), startingHistory + 1)
        assertEquals(id, get("/api/returns/relay?relayPointId=${relay.id}", relay.operator.token).single()["id"].asText())
        assertEquals(403, call("GET", "/api/returns/relay?relayPointId=forged-other-site", relay.operator.token).statusCode())
        assertEquals(400, call("POST", "$root/refund", admin.token, refund).statusCode())
        assertEquals(400, call("POST", "$root/physical-receipt", receiver.token, receipt).statusCode())
        time(now.plusSeconds(120))
        val collected = post("$root/collection", collector.token, collection)
        assertEquals("CollectedBySequo", collected["status"].asText())
        assertEquals(collector.id, collected["collectingOperatorId"].asText())
        assertEquals(now.plusSeconds(120), Instant.parse(collected["collectedBySequoAt"].asText()))
        audit(get(root, admin.token), collector.id, now.plusSeconds(120), startingHistory + 2)
        assertEquals(400, call("POST", "$root/refund", admin.token, refund).statusCode())
        assertEquals(200, call("POST", "$root/collection", collector.token, collection).statusCode())
        time(now.plusSeconds(180))
        val received = post("$root/physical-receipt", receiver.token, receipt)
        assertEquals("ReceivedBySequo", received["status"].asText())
        assertEquals(receiver.id, received["receivingOperatorId"].asText())
        assertEquals(now.plusSeconds(180), Instant.parse(received["receivedBySequoAt"].asText()))
        audit(get(root, admin.token), receiver.id, now.plusSeconds(180), startingHistory + 3)
        assertEquals(400, call("POST", "$root/refund", admin.token, refund).statusCode())
        assertEquals(200, call("POST", "$root/physical-receipt", receiver.token, receipt).statusCode())
        assertDenied(call("POST", "$root/physical-receipt", receiver.token, receipt.replace("Packaging arrived sealed.", "Changed evidence on replay.")))
        time(now.plusSeconds(240))
        val inspected = post("$root/inspection", admin.token, inspection)
        assertEquals("ACCEPTED", inspected["inspectionDecision"].asText())
        assertEquals(admin.id, inspected["inspectingOperatorId"].asText())
        assertEquals(now.plusSeconds(240), Instant.parse(inspected["inspectedAt"].asText()))
        audit(get(root, admin.token), admin.id, now.plusSeconds(240), startingHistory + 4)
        assertEquals(200, call("POST", "$root/inspection", admin.token, inspection).statusCode())
        assertDenied(call("POST", "$root/inspection", admin.token, inspection.replace("ACCEPTED", "REJECTED")))

        time(now.plusSeconds(300))
        val responses = (1..2).map { index -> CompletableFuture.supplyAsync {
            call("POST", "$root/refund", admin.token, ReturnCustodyFixtures.refundBody(3000, "refund-$id-$index"))
        } }.map { it.join() }
        responses.forEach { assertEquals(200, it.statusCode(), it.body()) }
        val states = responses.map { json.readTree(it.body()) }
        val instruction = states.first()["refundInstruction"]
        assertEquals(1, states.map { it["refundInstruction"]["id"].asText() }.toSet().size)
        assertEquals(3000, instruction["amountCfa"].asInt())
        assertEquals("XOF", instruction["currency"].asText())
        assertEquals("REQUESTED", instruction["status"].asText())
        assertEquals("NOT_SUBMITTED", instruction["paymentStatus"].asText())
        assertEquals(admin.id, instruction["requestedByUserId"].asText())
        assertEquals(now.plusSeconds(300), Instant.parse(instruction["requestedAt"].asText()))
        assertEquals("RefundPending", states.first()["status"].asText())
        assertEquals("UNCONFIGURED", states.first()["refundDeadlineState"].asText())
        assertTrue(states.first().path("refundDueAt").isNull || states.first().path("refundDueAt").isMissingNode)
        audit(get(root, admin.token), admin.id, now.plusSeconds(300), startingHistory + 5)
        assertEquals(200, call("POST", "$root/refund", admin.token, ReturnCustodyFixtures.refundBody(3000, "refund-$id-1")).statusCode())
        assertDenied(call("POST", "$root/refund", admin.token, ReturnCustodyFixtures.refundBody(2500, "refund-$id-1")))
        assertDenied(call("POST", "$root/refund", admin.token, ReturnCustodyFixtures.refundBody(2500, "different-refund-$id")))
        assertEquals(startingHistory + 5, get(root, admin.token)["history"].size())
        assertEquals(CustomerOrderStatus.RETURN_REQUESTED, orders.findById(fixture.orderId).orElseThrow().orderStatus)
        assertEquals(ReturnStatus.RefundPending, returns.findById(id).orElseThrow().status)
        val customer = get(root, fixture.owner.token)
        assertEquals("RefundPending", customer["status"].asText())
        assertEquals("NOT_SUBMITTED", customer["refundInstruction"]["paymentStatus"].asText())
        val merchant = get("/api/returns/merchant?merchantId=$merchantId", vendor.token).single()
        assertEquals(id, merchant["id"].asText())
        assertEquals("RefundPending", merchant["status"].asText())
        assertEquals("REQUESTED", merchant["refundInstructionState"].asText())
        assertEquals("NOT_SUBMITTED", merchant["refundPaymentStatus"].asText())
        assertEquals("Validated", merchant["orderPaymentStatus"].asText())
        assertEquals(1, merchant["lines"].single()["quantity"].asInt())
        for (privateField in listOf("customerId", "returnPin", "returnPinHash", "reason", "history", "requestedRefundCfa", "refundInstruction"))
            assertFalse(merchant.has(privateField), privateField)
        assertEquals(403, call("GET", "/api/returns/merchant?merchantId=forged-other-merchant", vendor.token).statusCode())
    }

    @Test
    fun rejectedInspectionAndIneligibleRelayIntakeCannotAuthorizeRefund() {
        val relay = ReturnJourneyFixtures.relayWithOperator(context)
        val support = ReturnCustodyFixtures.actor(context, RoleCode.SUPPORT_AGENT)
        val admin = ReturnCustodyFixtures.actor(context, RoleCode.ADMIN)
        val food = ReturnJourneyFixtures.order(context, now.minusSeconds(3600), OrderProductCategory.GenericSealedItem,
            relayCategory = RelayParcelCategory.Food)
        val manual = post("/api/returns", food.owner.token,
            ReturnJourneyFixtures.body("manual-return-${UUID.randomUUID()}", food.orderId))
        assertEquals("Requested", manual["status"].asText())
        val manualId = manual["id"].asText()
        assertEquals(400, call("POST", "/api/returns/$manualId/relay-dropoff", relay.operator.token,
            ReturnCustodyFixtures.dropoffBody(relay.id, "123456", "ineligible-$manualId")).statusCode())
        assertEquals(ReturnStatus.Requested, returns.findById(manualId).orElseThrow().status)

        val fixture = ReturnJourneyFixtures.order(context, now.minusSeconds(3600))
        val created = post("/api/returns", fixture.owner.token, ReturnJourneyFixtures.body("rejected-return-${UUID.randomUUID()}", fixture.orderId))
        val id = created["id"].asText(); val root = "/api/returns/$id"
        post("$root/relay-dropoff", relay.operator.token, ReturnCustodyFixtures.dropoffBody(relay.id, created["returnPin"].asText(), "dropoff-$id"))
        post("$root/collection", support.token, ReturnCustodyFixtures.collectionBody("collection-$id"))
        post("$root/physical-receipt", support.token, ReturnCustodyFixtures.receiptBody("receipt-$id"))
        val inspected = post("$root/inspection", support.token, ReturnCustodyFixtures.inspectionBody("inspection-$id", "REJECTED"))
        assertEquals("Rejected", inspected["status"].asText())
        assertEquals("REJECTED", inspected["inspectionDecision"].asText())
        assertEquals(400, call("POST", "$root/refund", admin.token, ReturnCustodyFixtures.refundBody(3000, "refund-$id")).statusCode())
        assertTrue(get(root, admin.token).path("refundInstruction").isNull || get(root, admin.token).path("refundInstruction").isMissingNode)
        assertEquals(CustomerOrderStatus.RETURN_REQUESTED, orders.findById(fixture.orderId).orElseThrow().orderStatus)
    }

    @Test
    fun legacyReceiptEvidenceRemainsDistinctFromLaterInspectionAndCanBeReplayed() {
        val fixture = ReturnJourneyFixtures.order(context, now.minusSeconds(3600))
        val receiver = ReturnCustodyFixtures.actor(context, RoleCode.SUPPORT_AGENT)
        val inspector = ReturnCustodyFixtures.actor(context, RoleCode.ADMIN)
        val id = "legacy-receipt-${UUID.randomUUID()}"
        val originalAssessment = "Original receipt noted sealed external packaging."
        legacyReturn(fixture, id, receiver.id).also {
            it.conditionAssessment = originalAssessment
            returns.saveAndFlush(it)
        }
        val inspection = json.writeValueAsString(mapOf("decision" to "ACCEPTED", "conditionAssessment" to "Inspection attributed the defect to SEQUO.",
            "responsibility" to "Sequo", "idempotencyKey" to "inspection-$id"))
        val inspected = post("/api/returns/$id/inspection", inspector.token, inspection)
        assertEquals(originalAssessment, inspected["receiptConditionAssessment"].asText())
        assertEquals("Merchant", inspected["receiptResponsibility"].asText())
        assertEquals("Inspection attributed the defect to SEQUO.", inspected["conditionAssessment"].asText())
        assertEquals("Sequo", inspected["responsibility"].asText())
        assertTrue(inspected.path("collectedBySequoAt").isNull || inspected.path("collectedBySequoAt").isMissingNode)
        val receiptRetry = json.writeValueAsString(mapOf("conditionAssessment" to originalAssessment, "responsibility" to "Merchant",
            "idempotencyKey" to "legacy-receipt-$id"))
        val retried = post("/api/returns/$id/physical-receipt", receiver.token, receiptRetry)
        assertEquals(originalAssessment, retried["receiptConditionAssessment"].asText())
        assertEquals("Inspection attributed the defect to SEQUO.", retried["conditionAssessment"].asText())
        assertEquals(inspected["history"].size(), retried["history"].size())
        assertEquals(now.minusSeconds(60), Instant.parse(retried["receivedBySequoAt"].asText()))
    }

    @Test
    fun aLegacyRefundOnAnotherReturnForTheSameOrderRequiresReconciliation() {
        val fixture = ReturnJourneyFixtures.order(context, now.minusSeconds(3600))
        val admin = ReturnCustodyFixtures.actor(context, RoleCode.ADMIN)
        val legacyId = "legacy-refund-${UUID.randomUUID()}"
        val newerId = "newer-return-${UUID.randomUUID()}"
        legacyReturn(fixture, legacyId, admin.id, ReturnStatus.RefundPending).also {
            it.refundIdempotencyKey = "already-triggered-$legacyId"; it.refundAmountCfa = 1000
            returns.saveAndFlush(it)
        }
        legacyReturn(fixture, newerId, admin.id).also {
            it.inspectionDecision = ReturnInspectionDecision.ACCEPTED
            it.inspectedAt = now.minusSeconds(30); it.inspectingOperatorId = admin.id
            returns.saveAndFlush(it)
        }
        val response = call("POST", "/api/returns/$newerId/refund", admin.token, ReturnCustodyFixtures.refundBody(3000, "refund-$newerId"))
        assertEquals(400, response.statusCode(), response.body())
        assertEquals("order_refund_review_required", json.readTree(response.body())["code"].asText())
        assertNull(returns.findById(newerId).orElseThrow().refundInstructionId)
        assertEquals(ReturnStatus.ReceivedBySequo, returns.findById(newerId).orElseThrow().status)
        val legacy = returns.findById(legacyId).orElseThrow()
        assertEquals("already-triggered-$legacyId", legacy.refundIdempotencyKey)
        assertEquals(1000, legacy.refundAmountCfa)
        assertNull(legacy.refundInstructionId)
    }

    @Test
    fun staffReadPrecedenceIsPreservedForAccountsThatAlsoHaveTheCustomerRole() {
        val first = ReturnJourneyFixtures.order(context, now.minusSeconds(3600))
        val second = ReturnJourneyFixtures.order(context, now.minusSeconds(3600))
        val firstId = "dual-role-return-${UUID.randomUUID()}"
        val secondId = "dual-role-return-${UUID.randomUUID()}"
        post("/api/returns", first.owner.token, ReturnJourneyFixtures.body(firstId, first.orderId))
        post("/api/returns", second.owner.token, ReturnJourneyFixtures.body(secondId, second.orderId))
        val staff = context.getBean(UserRepository::class.java).save(User(email = "dual-role-${UUID.randomUUID()}@fixture.invalid",
            provider = AuthProvider.EMAIL, roles = mutableSetOf(RoleCode.CUSTOMER, RoleCode.ADMIN)))
        val token = ReturnJourneyFixtures.token(context, requireNotNull(staff.id))
        val all = get("/api/returns", token).map { it["id"].asText() }.toSet()
        assertTrue(firstId in all && secondId in all)
        val filtered = get("/api/returns?customerId=${first.owner.id}", token)
        assertEquals(firstId, filtered.single()["id"].asText())
        assertEquals(first.owner.id, filtered.single()["customerId"].asText())
        val requested = get("/api/returns?status=Requested", token).map { it["id"].asText() }.toSet()
        assertTrue(firstId in requested && secondId in requested)
    }

    private fun legacyReturn(fixture: ReturnJourneyOrder, id: String, receiverId: String,
        status: ReturnStatus = ReturnStatus.ReceivedBySequo): ReturnRequestRecord {
        orders.findById(fixture.orderId).orElseThrow().also {
            it.orderStatus = CustomerOrderStatus.RETURN_REQUESTED; orders.save(it)
        }
        return ReturnRequestRecord(id = id, orderId = fixture.orderId, customerId = fixture.owner.id,
            status = status, reason = "Legacy returned item.", requestedRefundCfa = 3000,
            returnPinHash = Sha256ReturnPinHasher().hash("123456"), droppedAt = now.minusSeconds(120),
            receivedBySequoAt = now.minusSeconds(60), receivingOperatorId = receiverId,
            conditionAssessment = "Legacy receipt and condition note.", responsibility = RefundResponsibility.Merchant,
            receiptIdempotencyKey = "legacy-receipt-$id", createdAt = now.minusSeconds(180), updatedAt = now.minusSeconds(60))
    }

    private fun audit(state: JsonNode, actor: String, at: Instant, count: Int) {
        assertEquals(count, state["history"].size())
        val event = state["history"].last()
        assertEquals(actor, event["actorUserId"].asText())
        assertEquals(at, Instant.parse(event["createdAt"].asText()))
        assertTrue(event["action"].asText().isNotBlank())
    }
    private fun time(at: Instant) {
        Mockito.`when`(returnClock.instant()).thenReturn(at)
        Mockito.`when`(returnClock.zone).thenReturn(ZoneOffset.UTC)
    }
    private fun assertDenied(response: HttpResponse<String>) = assertTrue(response.statusCode() in setOf(400, 403, 409), response.body())
    private fun get(path: String, token: String) = parse(call("GET", path, token))
    private fun post(path: String, token: String, body: String) = parse(call("POST", path, token, body))
    private fun parse(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }
    private fun call(method: String, path: String, token: String, body: String? = null) = ReturnJourneyFixtures.request(port, method, path, token, body)
}

internal object ReturnCustodyFixtures {
    private val json = ObjectMapper()
    fun actor(context: ApplicationContext, role: RoleCode, merchantId: String? = null): ReturnJourneyActor {
        val user = context.getBean(UserRepository::class.java).save(User(email = "custody-${UUID.randomUUID()}@fixture.invalid",
            provider = AuthProvider.EMAIL, roles = mutableSetOf(role)))
        val id = requireNotNull(user.id)
        merchantId?.let { context.getBean(MerchantMembershipRepository::class.java).save(MerchantMembership(userId = id, merchantId = it, role = role)) }
        val token = context.getBean(JwtService::class.java).generateAccessToken(UserSession(id, user.email, user.provider, setOf(role), setOfNotNull(merchantId)))
        return ReturnJourneyActor(id, token)
    }
    fun dropoffBody(relayId: String, pin: String, key: String) = json.writeValueAsString(mapOf(
        "relayPointId" to relayId, "rawReturnPin" to pin, "idempotencyKey" to key,
        "metadata" to "Operator verified the packaged return.", "droppedAt" to "1900-01-01T00:00:00Z", "actorUserId" to "forged-actor"))
    fun collectionBody(key: String) = json.writeValueAsString(mapOf("idempotencyKey" to key,
        "metadata" to "SEQUO collected the packaged return from relay.", "collectedAt" to "1900-01-01T00:00:00Z", "actorUserId" to "forged-actor"))
    fun receiptBody(key: String) = json.writeValueAsString(mapOf("idempotencyKey" to key,
        "conditionAssessment" to "Packaging arrived sealed.", "responsibility" to "Merchant", "receivedAt" to "1900-01-01T00:00:00Z", "operatorId" to "forged-actor"))
    fun inspectionBody(key: String, decision: String = "ACCEPTED") = json.writeValueAsString(mapOf("idempotencyKey" to key,
        "conditionAssessment" to "Correct returned product physically inspected.", "responsibility" to "Merchant", "decision" to decision,
        "inspectedAt" to "1900-01-01T00:00:00Z", "operatorId" to "forged-actor"))
    fun refundBody(amount: Int, key: String) = json.writeValueAsString(mapOf("amountCfa" to amount, "idempotencyKey" to key,
        "requestedAt" to "1900-01-01T00:00:00Z", "requestedByUserId" to "forged-actor"))
}
