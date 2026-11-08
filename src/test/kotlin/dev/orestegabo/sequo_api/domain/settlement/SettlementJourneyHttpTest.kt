package dev.orestegabo.sequo_api.domain.settlement

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRepository
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.platform.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:settlement_journey_http;DB_CLOSE_DELAY=-1",
        "sequo.cart.straight-line-dispatch-origin=0,0",
        "sequo.wallets.yas-togo.webhook-secret=isolated-fixture-webhook-secret",
        "sequo.wallets.yas-togo.api-base-url=https://fixture.invalid",
        "sequo.wallets.yas-togo.api-key=isolated-fixture-key",
        "sequo.wallets.yas-togo.merchant-account-id=isolated-fixture-account",
    ],
)
class SettlementJourneyHttpTest @Autowired constructor(
    private val users: UserRepository,
    private val jwt: JwtService,
    private val memberships: MerchantMembershipRepository,
    private val merchants: CommerceMerchantRepository,
    private val products: CommerceProductRepository,
    private val addresses: CustomerAddressRepository,
    private val plans: SubscriptionPlanRepository,
    private val subscriptions: CustomerSubscriptionRepository,
    private val couriers: SettlementCourierRecordRepository,
    private val pending: PendingPaymentCheckoutRepository,
    private val missions: DeliveryMissionRepository,
    private val posting: DeliverySettlementPostingService,
    private val postings: DeliverySettlementRecordRepository,
    private val rateLimiter: InMemoryRateLimiter,
) {
    companion object {
        @JvmStatic @org.springframework.test.context.DynamicPropertySource
        fun isolatedPostgres(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_SETTLEMENT_JOURNEY_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) {
                "Use a disposable local settlement fixture database."
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

    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val client = HttpClient.newHttpClient()

    @org.junit.jupiter.api.BeforeEach
    fun resetRequestBudget() = rateLimiter.resetForTests()

    @Test
    fun paidIndependentDeliveryFreezesDefaultCommissionAndPostsFullPayableWithSequoShortfallOnce() {
        val fixture = fixture("FREELANCER", subscribed = true)
        val default = read("/api/commissions/merchant-overrides/${fixture.merchantId}", fixture.admin)
        assertEquals(1500, default["commissionRateBps"].asInt())
        val journey = payAndPrepare(fixture)
        assertSubOrder(journey, 1500, 600, 3400)
        // A later operator edit must not reprice an already-paid sale or its settlement.
        setCommission(fixture, 1000)
        assertSubOrder(journey, 1500, 600, 3400)
        val mission = pickUp(journey)
        assertEquals(320, mission["customerDeliveryFeeCfa"].asInt())
        assertEquals(400, mission["courierFeeCfa"].asInt())
        assertEquals(80, mission["shortfallCfa"].asInt())
        assertTrue(ledger(journey, "DeliveryMission", journey.missionId).isEmpty())
        complete(journey)

        val payouts = ownPayouts(journey)
        assertEquals(1, payouts.size())
        val payout = payouts.single()
        assertEquals(3400, payout["merchantNetCfa"].asInt())
        assertEquals(600, payout["commissionCfa"].asInt())
        val itemEntries = ledger(journey, "OrderItem", journey.subOrderId)
        assertEntry(itemEntries, "MerchantPayable", "Credit", 3400)
        assertEntry(itemEntries, "SequoCommissionRevenue", "Credit", 600)
        assertEquals(2, itemEntries.size())
        val deliveryEntries = ledger(journey, "DeliveryMission", journey.missionId)
        assertEntry(deliveryEntries, "CourierPayable", "Credit", 400)
        assertEntry(deliveryEntries, "SequoDeliveryShortfallExpense", "Debit", 80)
        assertEquals(2, deliveryEntries.size())
        assertTrue(deliveryEntries.all { it["courierId"].asText() == fixture.courierId })
        assertEquals("Freelancer", deliveryEntries.single { it["account"].asText() == "CourierPayable" }["workforceType"].asText())

        val summary = read("/api/rider/payouts/summary", fixture.rider.token)
        assertEquals(400, summary["availableBalanceCfa"].asInt())
        assertEquals(400, summary["earnedTotalCfa"].asInt())
        assertEquals(1, summary["settledMissionCount"].asInt())
        assertEquals("NOT_INTEGRATED", summary["transferStatus"].asText())
        assertEquals(0, summary["processingCfa"].asInt())
        assertEquals(0, summary["paidCfa"].asInt())
        assertEquals(0, summary["paidOrProcessingCfa"].asInt())
        val history = read("/api/rider/payouts/history", fixture.rider.token)
        assertEquals(400, history.sumOf { it["payableAmountCfa"].asInt() })
        assertTrue(history.all { it["missionId"].asText() == journey.missionId })
        assertAccessBoundaries(journey)

        val storedCourier = couriers.findById(fixture.courierId).orElseThrow()
        storedCourier.workforceType = "SEQUO_SALARIED"
        couriers.save(storedCourier)
        assertEquals(400, read("/api/rider/payouts/summary", fixture.rider.token)["availableBalanceCfa"].asInt())
        assertEquals(400, read("/api/rider/payouts/history", fixture.rider.token).sumOf { it["payableAmountCfa"].asInt() })
        assertEquals(payouts, ownPayouts(journey))
        assertEquals(deliveryEntries, ledger(journey, "DeliveryMission", journey.missionId))
    }

    @Test
    fun paidSalariedDeliveryKeepsApprovedOverrideAndCreatesNoIndependentCourierPayable() {
        val fixture = fixture("SEQUO_SALARIED")
        assertEquals(403, call("PUT", "/api/commissions/merchant-overrides/${fixture.merchantId}", fixture.merchant.token,
            """{"commissionRateBps":500,"reason":"Unapproved merchant edit"}""").statusCode())
        setCommission(fixture, 1000)
        val journey = payAndPrepare(fixture)
        assertSubOrder(journey, 1000, 400, 3600)
        setCommission(fixture, 1500)
        assertSubOrder(journey, 1000, 400, 3600)
        val mission = pickUp(journey)
        assertEquals(400, mission["customerDeliveryFeeCfa"].asInt())
        assertEquals(0, mission["courierFeeCfa"].asInt())
        assertEquals(0, mission["shortfallCfa"].asInt())
        complete(journey)
        val payout = ownPayouts(journey).single()
        assertEquals(3600, payout["merchantNetCfa"].asInt())
        assertEquals(400, payout["commissionCfa"].asInt())
        val itemEntries = ledger(journey, "OrderItem", journey.subOrderId)
        assertEntry(itemEntries, "MerchantPayable", "Credit", 3600)
        assertEntry(itemEntries, "SequoCommissionRevenue", "Credit", 400)
        assertEquals(2, itemEntries.size())
        assertTrue(ledger(journey, "DeliveryMission", journey.missionId).isEmpty())
        val summary = read("/api/rider/payouts/summary", fixture.rider.token)
        assertFalse(summary["payable"].asBoolean())
        assertEquals(0, summary["availableBalanceCfa"].asInt())
        assertEquals(0, summary["earnedTotalCfa"].asInt())
        assertEquals(0, summary["paidOrProcessingCfa"].asInt())
        assertEquals("NOT_INTEGRATED", summary["transferStatus"].asText())
        assertTrue(read("/api/rider/payouts/history", fixture.rider.token).isEmpty())
        assertAccessBoundaries(journey)
    }

    @Test
    fun multipleIndependentLegsAllocateThePaidCustomerDeliveryContributionOnlyOnce() {
        fun assertSingleContribution(journey: Journey, secondId: String) {
            val rows = postings.findByOrderId(journey.orderId)
            assertEquals(2, rows.size)
            assertEquals(400, rows.sumOf { it.customerDeliveryFeeAllocatedCfa })
            assertEquals(800, rows.sumOf { it.courierPayableCfa })
            assertEquals(400, rows.sumOf { it.shortfallCfa })
            assertTrue(rows.all { it.status == DeliverySettlementPostingStatus.POSTED })
            val entries = ledger(journey, "DeliveryMission", journey.missionId).toList() + ledger(journey, "DeliveryMission", secondId).toList()
            assertEquals(800, entries.filter { it["account"].asText() == "CourierPayable" }.sumOf { it["amountCfa"].asInt() })
            assertEquals(400, entries.filter { it["account"].asText() == "SequoDeliveryShortfallExpense" }.sumOf { it["amountCfa"].asInt() })
            assertEquals(3, entries.size)
        }

        val (sequential, sequentialSecond) = completedLegs()
        assertNotNull(posting.postDeliverySettlement(sequential.missionId))
        assertNotNull(posting.postDeliverySettlement(sequentialSecond))
        assertNotNull(posting.postDeliverySettlement(sequential.missionId))
        assertNotNull(posting.postDeliverySettlement(sequentialSecond))
        assertSingleContribution(sequential, sequentialSecond)

        val (concurrent, concurrentSecond) = completedLegs()
        val results = listOf(concurrent.missionId, concurrentSecond, concurrent.missionId, concurrentSecond).map { id ->
            CompletableFuture.supplyAsync { posting.postDeliverySettlement(id) }
        }.map { it.join() }
        results.forEach { assertNotNull(it) }
        assertSingleContribution(concurrent, concurrentSecond)
    }

    @Test
    fun aCompletedLegacyLegWithUnknownContributionRequiresReviewBeforeAnyNewAllocation() {
        val (journey, finalLegId) = completedLegs()
        val legacy = missions.findById(journey.missionId).orElseThrow()
        legacy.settlementPolicyVersion = null
        missions.saveAndFlush(legacy)
        assertEquals(1, missions.findById(finalLegId).orElseThrow().settlementPolicyVersion)

        val final = assertNotNull(posting.postDeliverySettlement(finalLegId))
        val historical = assertNotNull(posting.postDeliverySettlement(journey.missionId))
        assertEquals(DeliverySettlementPostingStatus.REVIEW_REQUIRED, final.status)
        assertEquals(DeliverySettlementPostingStatus.REVIEW_REQUIRED, historical.status)
        assertEquals(final.missionId, assertNotNull(posting.postDeliverySettlement(finalLegId)).missionId)
        val rows = postings.findByOrderId(journey.orderId)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.status == DeliverySettlementPostingStatus.REVIEW_REQUIRED })
        assertEquals(0, rows.sumOf { it.customerDeliveryFeeAllocatedCfa })
        assertEquals(0, rows.sumOf { it.courierPayableCfa })
        assertEquals(0, rows.sumOf { it.shortfallCfa })
        assertTrue(ledger(journey, "DeliveryMission", journey.missionId).isEmpty())
        assertTrue(ledger(journey, "DeliveryMission", finalLegId).isEmpty())
        assertEquals(0, read("/api/rider/payouts/summary", journey.fixture.rider.token)["availableBalanceCfa"].asInt())
    }

    private data class Actor(val id: String, val token: String)
    private data class Fixture(val customer: Actor, val merchant: Actor, val rider: Actor, val admin: String,
        val merchantId: String, val productId: String, val addressId: String, val courierId: String, val deliveryFee: Int)
    private data class Journey(val fixture: Fixture, val checkoutId: String, val orderId: String, val subOrderId: String, val missionId: String)

    private fun completedLegs(): Pair<Journey, String> {
        val journey = payAndPrepare(fixture("FREELANCER"))
        val fixture = journey.fixture
        val first = missions.findById(journey.missionId).orElseThrow()
        val completedAt = Instant.now()
        first.courierId = fixture.courierId
        first.assignmentWorkforceType = DeliveryWorkforceType.Freelancer
        first.courierFeeCfa = 400
        first.destinationType = DeliveryMissionRecordDestination.SEQUO_CONSOLIDATION
        first.status = DeliveryMissionRecordStatus.HANDED_TO_SEQUO
        first.updatedAt = completedAt
        missions.saveAndFlush(first)
        // Separate persisted completion records let the service-level tests control the posting race.
        val second = missions.saveAndFlush(DeliveryMission(
            deliveryCode = "settlement-final-${UUID.randomUUID()}", orderId = journey.orderId,
            courierId = fixture.courierId, assignmentWorkforceType = DeliveryWorkforceType.Freelancer,
            deliveryMode = DeliveryMissionRecordMode.STANDARD, destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            status = DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, customerDeliveryFeeCfa = 400,
            courierFeeCfa = 400, deliveredAt = completedAt, updatedAt = completedAt,
        ))
        return journey to requireNotNull(second.id)
    }

    private fun actor(role: RoleCode, merchantId: String? = null): Actor {
        val user = users.save(User(email = "settlement-${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL, roles = mutableSetOf(role)))
        val id = requireNotNull(user.id)
        merchantId?.let { memberships.save(MerchantMembership(userId = id, merchantId = it, role = role)) }
        return Actor(id, jwt.generateAccessToken(UserSession(id, user.email, user.provider, setOf(role), setOfNotNull(merchantId))))
    }

    private fun fixture(workforceType: String, subscribed: Boolean = false): Fixture {
        val customer = actor(RoleCode.CUSTOMER)
        val merchantId = "settlement-merchant-${UUID.randomUUID()}"
        merchants.save(MerchantRecord(id = merchantId, name = "Local settlement fixture merchant"))
        val merchant = actor(RoleCode.MERCHANT_OWNER, merchantId)
        val rider = actor(RoleCode.COURIER)
        val courierId = "courier-${rider.id}"
        couriers.save(CourierRecord(id = courierId, userId = rider.id, workforceType = workforceType, vehicleType = "MOTO"))
        val product = products.save(ProductRecord("settlement-product-${UUID.randomUUID()}", merchantId = merchantId,
            name = "Fixture sealed item", kind = CatalogProductKind.GenericSealedItem, basePriceCfa = 2000, imageUrl = "https://fixture.invalid/product.jpg"))
        val address = addresses.save(CustomerAddressRecord("settlement-address-${UUID.randomUUID()}", customer.id, CustomerAddressKind.HOME,
            "Home", "Fixture recipient", "+22890000000", "Lomé", latitude = 0.0, longitude = 0.01))
        if (subscribed) {
            val now = Instant.now()
            val plan = plans.save(SubscriptionPlanRecord("settlement-plan-${UUID.randomUUID()}", "LOCAL_FIXTURE_ONLY", 1000, 20,
                approvalReference = "LOCAL_FIXTURE_ONLY"))
            subscriptions.save(CustomerSubscriptionRecord("settlement-sub-${UUID.randomUUID()}", customer.id, plan.id,
                status = CustomerSubscriptionStatus.ACTIVE, startedAt = now.minusSeconds(86400), currentPeriodStartsAt = now.minusSeconds(86400),
                currentPeriodEndsAt = now.plusSeconds(30L * 86400), activationReference = "LOCAL_FIXTURE_ONLY"))
        }
        val fixture = Fixture(customer, merchant, rider, actor(RoleCode.ADMIN).token, merchantId, product.id, address.id, courierId, if (subscribed) 320 else 400)
        assertEquals(200, call("PUT", "/api/cart/items", customer.token, """{"productId":"${product.id}","quantity":2}""").statusCode())
        return fixture
    }

    private fun payAndPrepare(fixture: Fixture): Journey {
        val quote = read("/api/cart/quote?addressId=${fixture.addressId}", fixture.customer.token)
        assertEquals(4000, quote["productTotalCfa"].asInt())
        assertEquals(fixture.deliveryFee, quote["deliveryFeeCfa"].asInt())
        assertEquals(4000 + fixture.deliveryFee, quote["totalCfa"].asInt())
        val checkout = post("/api/orders/checkouts", fixture.customer.token,
            """{"addressId":"${fixture.addressId}","quoteRevision":"${quote["revision"].asText()}","paymentProvider":"yas_togo"}""", 202)
        val checkoutId = checkout["checkoutId"].asText()
        assertEquals(4000 + fixture.deliveryFee, pending.findById(checkoutId).orElseThrow().amountCfa)
        assertEquals(202, webhook(checkoutId).statusCode())
        val subOrder = read("/api/merchant/sub-orders?merchantId=${fixture.merchantId}", fixture.merchant.token).single()
        val subOrderId = subOrder["id"].asText()
        val orderId = subOrder["orderId"].asText()
        assertEquals(4000 + fixture.deliveryFee, read("/api/orders/$orderId", fixture.customer.token)["order"]["totalCfa"].asInt())
        for (action in listOf("accept", "start-preparation", "mark-packed")) {
            val packed = if (action == "mark-packed") ",\"packageCount\":1" else ""
            post("/api/merchant/sub-orders/$subOrderId/$action", fixture.merchant.token, """{"merchantId":"${fixture.merchantId}"$packed}""")
        }
        val dispatched = post("/api/delivery/missions/dispatch-ready?limit=200", fixture.admin)
        val missionId = dispatched["createdMissions"].single { it["orderId"].asText() == orderId }["id"].asText()
        return Journey(fixture, checkoutId, orderId, subOrderId, missionId)
    }

    private fun pickUp(journey: Journey): JsonNode {
        val fixture = journey.fixture
        post("/api/delivery/missions/${journey.missionId}/assign", fixture.admin, """{"courierId":"${fixture.courierId}"}""")
        post("/api/delivery/missions/${journey.missionId}/offer", fixture.admin)
        post("/api/rider/missions/${journey.missionId}/accept", fixture.rider.token)
        post("/api/rider/missions/${journey.missionId}/pickup/complete", fixture.rider.token,
            """{"proofMetadata":"Merchant handed over the fixture package","idempotencyKey":"pickup-${journey.missionId}"}""")
        return read("/api/delivery/missions/${journey.missionId}", fixture.admin)
    }

    private fun complete(journey: Journey) {
        val fixture = journey.fixture
        post("/api/delivery/missions/${journey.missionId}/delivery-pin", fixture.admin,
            """{"rawPin":"123456","expiresAt":"${Instant.now().plusSeconds(3600)}"}""")
        val body = """{"deliveryPin":"123456","idempotencyKey":"delivered-${journey.missionId}"}"""
        val concurrent = (1..2).map { CompletableFuture.supplyAsync { call("POST", "/api/rider/missions/${journey.missionId}/delivery/complete", fixture.rider.token, body) } }
        concurrent.map { it.join() }.forEach { assertEquals(200, it.statusCode(), it.body()) }
        post("/api/rider/missions/${journey.missionId}/delivery/complete", fixture.rider.token, body)
        assertEquals(202, webhook(journey.checkoutId).statusCode())
        assertEquals("DELIVERED", read("/api/orders/${journey.orderId}", fixture.customer.token)["order"]["orderStatus"].asText())
    }

    private fun assertSubOrder(journey: Journey, rate: Int, commission: Int, net: Int) {
        val sub = read("/api/merchant/sub-orders/${journey.subOrderId}", journey.fixture.merchant.token)
        assertEquals(4000, sub["itemSubtotalCfa"].asInt())
        assertEquals(rate, sub["commissionRateBps"].asInt())
        assertEquals(commission, sub["commissionCfa"].asInt())
        assertEquals(net, sub["merchantNetCfa"].asInt())
    }

    private fun assertAccessBoundaries(journey: Journey) {
        val fixture = journey.fixture
        val otherMerchantId = "foreign-merchant-${UUID.randomUUID()}"
        merchants.save(MerchantRecord(otherMerchantId, name = "Other fixture merchant"))
        val otherMerchant = actor(RoleCode.MERCHANT_OWNER, otherMerchantId)
        val otherRider = actor(RoleCode.COURIER)
        couriers.save(CourierRecord("courier-${otherRider.id}", userId = otherRider.id, workforceType = "FREELANCER", vehicleType = "MOTO"))
        val payoutPath = "/api/settlements/merchant-payouts?merchantId=${fixture.merchantId}"
        assertEquals(403, call("GET", payoutPath, otherMerchant.token).statusCode())
        assertEquals(403, call("GET", "/api/merchant/sub-orders/${journey.subOrderId}", otherMerchant.token).statusCode())
        assertEquals(403, call("GET", payoutPath, fixture.customer.token).statusCode())
        assertTrue(call("GET", payoutPath, null).statusCode() in setOf(401, 403))
        assertTrue(read("/api/settlements/merchant-payouts?merchantId=$otherMerchantId", otherMerchant.token).isEmpty())
        assertTrue(read("/api/rider/payouts/history?courierId=${fixture.courierId}", otherRider.token).isEmpty())
        assertEquals(0, read("/api/rider/payouts/summary?courierId=${fixture.courierId}", otherRider.token)["availableBalanceCfa"].asInt())
        val ledgerPath = "/api/settlements/ledger?sourceType=DeliveryMission&sourceId=${journey.missionId}"
        for (token in listOf(fixture.merchant.token, fixture.rider.token, otherRider.token, fixture.customer.token)) {
            assertEquals(403, call("GET", ledgerPath, token).statusCode())
        }
        assertTrue(call("GET", ledgerPath, null).statusCode() in setOf(401, 403))
        assertEquals(403, call("GET", "/api/rider/payouts/summary", fixture.customer.token).statusCode())
    }

    private fun assertEntry(entries: JsonNode, account: String, direction: String, amount: Int) {
        val entry = entries.single { it["account"].asText() == account }
        assertEquals(direction, entry["direction"].asText())
        assertEquals(amount, entry["amountCfa"].asInt())
    }
    private fun ownPayouts(journey: Journey) = read("/api/settlements/merchant-payouts?merchantId=${journey.fixture.merchantId}", journey.fixture.merchant.token)
    private fun ledger(journey: Journey, type: String, id: String) = read("/api/settlements/ledger?sourceType=$type&sourceId=$id", journey.fixture.admin)
    private fun setCommission(fixture: Fixture, rate: Int) {
        val result = call("PUT", "/api/commissions/merchant-overrides/${fixture.merchantId}", fixture.admin,
            """{"commissionRateBps":$rate,"reason":"LOCAL_FIXTURE_ONLY approved test override"}""")
        assertEquals(200, result.statusCode(), result.body())
        val currentMerchant = read("/api/admin/web/merchants?limit=100", fixture.admin).single { it["id"].asText() == fixture.merchantId }
        assertEquals(rate, currentMerchant["commissionRateBps"].asInt())
    }
    private fun webhook(checkoutId: String): HttpResponse<String> {
        val payload = json.writeValueAsString(mapOf("eventId" to "settlement-event-${UUID.randomUUID()}", "checkoutId" to checkoutId,
            "paymentReference" to checkoutId, "amountCfa" to pending.findById(checkoutId).orElseThrow().amountCfa,
            "currency" to "XOF", "status" to "VALIDATED", "occurredAt" to Instant.now().toString()))
        val timestamp = Instant.now().epochSecond
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("isolated-fixture-webhook-secret".toByteArray(), "HmacSHA256")) }
        val signature = mac.doFinal("$timestamp.$payload".toByteArray()).toHexString()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/payments/webhooks/yas_togo"))
            .header("Content-Type", "application/json").header("X-Sequo-Webhook-Timestamp", timestamp.toString())
            .header("X-Sequo-Webhook-Signature", signature).POST(HttpRequest.BodyPublishers.ofString(payload)).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
    private fun read(path: String, token: String): JsonNode {
        val response = call("GET", path, token)
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }
    private fun post(path: String, token: String, body: String? = null, status: Int = 200): JsonNode {
        val response = call("POST", path, token, body)
        assertEquals(status, response.statusCode(), response.body())
        return json.readTree(response.body())
    }
    private fun call(method: String, path: String, token: String?, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.header("Content-Type", "application/json") }
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
