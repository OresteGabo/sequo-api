package dev.orestegabo.sequo_api.domain.commerce

import dev.orestegabo.sequo_api.support.assertPersistentJsonEquals
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.relay.RelayPointRepository
import dev.orestegabo.sequo_api.domain.returns.*
import dev.orestegabo.sequo_api.domain.settlement.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:merchant_business_reads_http;DB_CLOSE_DELAY=-1",
        "sequo.auth.jwt.secret=isolated-merchant-business-read-fixture-2026-secret",
    ],
)
class MerchantBusinessReadsHttpTest @Autowired constructor(
    private val context: ApplicationContext,
    private val merchants: CommerceMerchantRepository,
    private val products: CommerceProductRepository,
    private val orders: CustomerOrderRecordRepository,
    private val subOrders: MerchantSubOrderRepository,
    private val pending: PendingPaymentCheckoutService,
    private val fulfillment: OrderFulfillmentPersistenceService,
    private val settlements: SettlementPersistenceService,
    private val payouts: MerchantPayoutAccrualRecordRepository,
    private val ledger: SettlementLedgerEntryRecordRepository,
    private val users: UserRepository,
    private val rateLimiter: InMemoryRateLimiter,
) {
    companion object {
        @JvmStatic @org.springframework.test.context.DynamicPropertySource
        fun isolatedPostgres(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_MERCHANT_READS_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) {
                "Use a disposable local merchant read fixture database."
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
    private lateinit var now: Instant

    @org.junit.jupiter.api.BeforeEach
    fun resetFixtures() {
        rateLimiter.resetForTests()
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        context.getBean(RelayPointRepository::class.java).findAll().forEach {
            it.status = "INACTIVE"; context.getBean(RelayPointRepository::class.java).save(it)
        }
    }

    @Test
    fun historyReadsPaidOwnPartsAcrossMultipleAndMixedOrdersWithoutExpandingTheActiveQueue() {
        val first = merchant("first", 1500)
        val second = merchant("second", 7000)
        val mixed = order(listOf(first to 2, second to 1), delivered = true)
        val ownActive = order(listOf(first to 1))
        val foreignActive = order(listOf(second to 1))
        val rejected = order(listOf(first to 1), status = MerchantSubOrderStatus.REJECTED)
        val cancelled = order(listOf(first to 1), status = MerchantSubOrderStatus.CANCELLED)
        val pendingOnly = order(listOf(first to 1), pendingOnly = true)
        val unpaid = order(listOf(first to 1))
        // Supported pre-existing unpaid record: preserve the FK graph but do not report a sale.
        context.getBean(org.springframework.jdbc.core.JdbcTemplate::class.java).update(
            "update customer_orders set payment_status = ? where id = ?", "Pending", unpaid.orderId)

        val active = get(historyPath(first, history = false), first.owner.token)
        assertEquals(setOf(ownActive.part(first.id)), active.map { it["id"].asText() }.toSet())
        val history = get(historyPath(first), first.owner.token)
        assertEquals(setOf(mixed.part(first.id), ownActive.part(first.id), rejected.part(first.id), cancelled.part(first.id)),
            history.map { it["id"].asText() }.toSet())
        assertFalse(history.toString().contains(pendingOnly.checkoutId))
        assertFalse(history.toString().contains(unpaid.orderId))
        assertFalse(history.toString().contains(foreignActive.orderId))
        val ownMixed = history.single { it["orderId"].asText() == mixed.orderId }
        assertEquals("DELIVERED", ownMixed["orderStatus"].asText())
        assertEquals("Validated", ownMixed["orderPaymentStatus"].asText())
        assertEquals(mixed.createdAt, Instant.parse(ownMixed["orderCreatedAt"].asText()))
        assertEquals(mixed.deliveredAt, Instant.parse(ownMixed["deliveredAt"].asText()))
        assertEquals(3000, ownMixed["itemSubtotalCfa"].asInt())
        assertEquals(450, ownMixed["commissionCfa"].asInt())
        assertEquals(2550, ownMixed["merchantNetCfa"].asInt())
        assertEquals(listOf(first.productId), ownMixed["lines"].map { it["productId"].asText() })
        assertEquals(2, ownMixed["lines"].single()["quantity"].asInt())
        assertPrivacy(ownMixed, mixed.owner.id, second)
        assertPersistentJsonEquals(ownMixed, get("/api/merchant/sub-orders/${mixed.part(first.id)}", first.owner.token))
        assertPersistentJsonEquals(history, get(historyPath(first), first.owner.token))
        assertEquals(listOf(rejected.part(first.id)), get(historyPath(first) + "&status=REJECTED", first.owner.token).map { it["id"].asText() })
        val secondHistory = get(historyPath(second), second.owner.token)
        assertEquals(setOf(mixed.part(second.id), foreignActive.part(second.id)), secondHistory.map { it["id"].asText() }.toSet())
        val secondMixed = secondHistory.single { it["orderId"].asText() == mixed.orderId }
        assertEquals(7000, secondMixed["itemSubtotalCfa"].asInt())
        assertEquals(1050, secondMixed["commissionCfa"].asInt())
        assertEquals(5950, secondMixed["merchantNetCfa"].asInt())
        assertPrivacy(secondMixed, mixed.owner.id, first)
        assertEquals(403, call("GET", historyPath(second), first.owner.token).statusCode())
        assertEquals(403, call("GET", "/api/merchant/sub-orders/${mixed.part(second.id)}?merchantId=${first.id}", first.owner.token).statusCode())
        assertEquals(403, call("GET", "/api/merchant/sub-orders/${mixed.part(first.id)}", second.owner.token).statusCode())
        assertFailClosedAndEmpty(first)
    }

    @Test
    fun returnedMixedOrderExposesOwnLinesAndSeparatesRefundInstructionFromRefundPayment() {
        val first = merchant("return-first", 1500)
        val second = merchant("return-second", 7000)
        val paid = order(listOf(first to 2, second to 1), delivered = true)
        val relay = ReturnJourneyFixtures.relayWithOperator(context)
        val support = ReturnCustodyFixtures.actor(context, RoleCode.SUPPORT_AGENT)
        val admin = ReturnCustodyFixtures.actor(context, RoleCode.ADMIN)
        val id = "merchant-read-return-${UUID.randomUUID()}"
        val requested = post("/api/returns", paid.owner.token, ReturnJourneyFixtures.body(id, paid.orderId, "PRIVATE_CUSTOMER_REASON"))
        fun ownReturn(merchant: Merchant): JsonNode = get("/api/returns/merchant?merchantId=${merchant.id}", merchant.owner.token).single()
        val before = ownReturn(first)
        assertEquals("NOT_INSTRUCTED", before["refundInstructionState"].asText())
        assertEquals("UNCONFIRMED", before["refundPaymentStatus"].asText())
        assertEquals("Validated", before["orderPaymentStatus"].asText())
        post("/api/returns/$id/relay-dropoff", relay.operator.token,
            ReturnCustodyFixtures.dropoffBody(relay.id, requested["returnPin"].asText(), "dropoff-$id"))
        post("/api/returns/$id/collection", support.token, ReturnCustodyFixtures.collectionBody("collection-$id"))
        post("/api/returns/$id/physical-receipt", support.token, ReturnCustodyFixtures.receiptBody("receipt-$id"))
        post("/api/returns/$id/inspection", support.token, ReturnCustodyFixtures.inspectionBody("inspection-$id"))
        post("/api/returns/$id/refund", admin.token, ReturnCustodyFixtures.refundBody(10000, "refund-$id"))
        for ((merchant, foreign) in listOf(first to second, second to first)) {
            val row = ownReturn(merchant)
            assertEquals(id, row["id"].asText())
            assertEquals("RefundPending", row["status"].asText())
            assertEquals("REQUESTED", row["refundInstructionState"].asText())
            assertEquals("NOT_SUBMITTED", row["refundPaymentStatus"].asText())
            assertEquals("Validated", row["orderPaymentStatus"].asText())
            assertEquals(listOf(merchant.productId), row["lines"].map { it["productId"].asText() })
            assertPrivacy(row, paid.owner.id, foreign)
            assertFalse(row.toString().contains("PRIVATE_CUSTOMER_REASON"))
            assertFalse(row.toString().contains(requested["returnPin"].asText()))
            val sale = get(historyPath(merchant), merchant.owner.token).single()
            assertEquals("RETURN_REQUESTED", sale["orderStatus"].asText())
            assertEquals("Validated", sale["orderPaymentStatus"].asText())
            assertEquals(row, ownReturn(merchant))
        }
        assertEquals(403, call("GET", "/api/returns/merchant?merchantId=${second.id}", first.owner.token).statusCode())
        assertEquals(403, call("GET", "/api/returns/$id", first.owner.token).statusCode())

        val legacyOrder = order(listOf(first to 1), delivered = true)
        val legacyId = "merchant-legacy-return-${UUID.randomUUID()}"
        post("/api/returns", legacyOrder.owner.token, ReturnJourneyFixtures.body(legacyId, legacyOrder.orderId))
        val returnRepository = context.getBean(ReturnRequestRecordRepository::class.java)
        returnRepository.findById(legacyId).orElseThrow().also {
            it.status = ReturnStatus.Requested
            it.refundIdempotencyKey = "legacy-unconfirmed-$legacyId"
            assertNull(it.refundInstructionId)
            returnRepository.saveAndFlush(it)
        }
        val legacyRead = get("/api/returns/merchant?merchantId=${first.id}", first.owner.token).single { it["id"].asText() == legacyId }
        val canonical = get("/api/returns/$legacyId", admin.token)
        assertEquals("LEGACY_REVIEW_REQUIRED", legacyRead["refundInstructionState"].asText())
        assertEquals(canonical["refundInstructionState"], legacyRead["refundInstructionState"])
        assertEquals("UNCONFIRMED", legacyRead["refundPaymentStatus"].asText())
        assertTrue(canonical["refundInstruction"].isNull)
        val blocked = call("POST", "/api/returns/$legacyId/refund", admin.token, ReturnCustodyFixtures.refundBody(1500, "new-$legacyId"))
        assertEquals(400, blocked.statusCode(), blocked.body())
        assertEquals("legacy_refund_review_required", json.readTree(blocked.body())["code"].asText())
    }

    @Test
    fun recordedPayoutStagesRemainDistinctAndForeignLegacyLedgerRowsStayOutOfMerchantReads() {
        val first = merchant("payout-first", 2000)
        val second = merchant("payout-second", 9000)
        val statuses = listOf(MerchantPayoutStatus.Accrued, MerchantPayoutStatus.HeldReturnWindow,
            MerchantPayoutStatus.Batched, MerchantPayoutStatus.Approved, MerchantPayoutStatus.SentToProvider,
            MerchantPayoutStatus.Paid, MerchantPayoutStatus.Failed, MerchantPayoutStatus.Adjusted)
        val sourceIds = mutableListOf<String>()
        for (status in statuses) {
            val paid = order(listOf(first to 1), delivered = true)
            val part = subOrders.findById(paid.part(first.id)).orElseThrow()
            val id = "merchant-read-payout-${UUID.randomUUID()}"
            settlements.accrueMerchantPayout(MerchantPayoutAccrualCommand(id, first.id, paid.orderId,
                requireNotNull(part.id), part.merchantNetCfa, part.commissionCfa, 0, requireNotNull(paid.deliveredAt),
                SettlementWorkflowType.DeliveryConfirmed))
            // Existing recorded states only; this fixture never executes a provider transfer.
            payouts.findById(id).orElseThrow().also { it.status = status; payouts.save(it) }
            sourceIds += requireNotNull(part.id)
        }
        val foreignEntryId = "foreign-legacy-ledger-${UUID.randomUUID()}"
        ledger.saveAndFlush(SettlementLedgerEntryRecord(foreignEntryId, SettlementLedgerAccount.MerchantPayable,
            SettlementLedgerDirection.Credit, 999999, merchantId = second.id, sourceType = SettlementSourceType.OrderItem,
            sourceId = sourceIds.first(), description = "FOREIGN_PRIVATE_LEDGER", createdAt = now))
        val courier = ReturnCustodyFixtures.actor(context, RoleCode.COURIER)
        val courierId = "merchant-read-courier-${UUID.randomUUID()}"
        context.getBean(SettlementCourierRecordRepository::class.java).save(CourierRecord(courierId, userId = courier.id,
            workforceType = "FREELANCER", vehicleType = "MOTO"))
        val relay = ReturnJourneyFixtures.relayWithOperator(context)
        val ownLegacyEntryId = "own-legacy-ledger-${UUID.randomUUID()}"
        ledger.saveAndFlush(SettlementLedgerEntryRecord(ownLegacyEntryId, SettlementLedgerAccount.Adjustment,
            SettlementLedgerDirection.Debit, 17, merchantId = first.id, courierId = courierId, relayPointId = relay.id,
            sourceType = SettlementSourceType.OrderItem, sourceId = sourceIds.first(),
            description = "Own recorded adjustment with legacy custody identifiers.", createdAt = now))
        val rows = get(payoutPath(first), first.owner.token)
        assertEquals(statuses.toSet(), rows.map { MerchantPayoutStatus.valueOf(it["status"].asText()) }.toSet())
        assertEquals(8, rows.size())
        for (row in rows) {
            assertEquals(1700, row["merchantNetCfa"].asInt())
            assertEquals(300, row["commissionCfa"].asInt())
            assertTrue(row["ledgerEntries"].all { it["merchantId"].asText() == first.id })
            assertFalse(row.toString().contains(foreignEntryId))
            assertFalse(row.toString().contains("FOREIGN_PRIVATE_LEDGER"))
            assertFalse(row.hasNonNull("providerPaymentConfirmed"))
            assertFalse(row.hasNonNull("paidAt"))
        }
        val redacted = rows.flatMap { it["ledgerEntries"].toList() }.single { it["id"].asText() == ownLegacyEntryId }
        assertTrue(redacted["courierId"].isNull)
        assertTrue(redacted["relayPointId"].isNull)
        assertEquals(rows, get(payoutPath(first), first.owner.token))
        assertTrue(get(payoutPath(second), second.owner.token).isEmpty())
        assertEquals(403, call("GET", payoutPath(first), second.owner.token).statusCode())
        val admin = ReturnCustodyFixtures.actor(context, RoleCode.ADMIN)
        val raw = get("/api/settlements/ledger?sourceType=OrderItem&sourceId=${sourceIds.first()}", admin.token, requireNoStore = false)
        assertTrue(raw.any { it["id"].asText() == foreignEntryId })
        val retained = raw.single { it["id"].asText() == ownLegacyEntryId }
        assertEquals(courierId, retained["courierId"].asText())
        assertEquals(relay.id, retained["relayPointId"].asText())
        assertTrue(ledger.existsById(foreignEntryId))
    }

    private data class Merchant(val id: String, val owner: ReturnJourneyActor, val productId: String, val name: String, val price: Int)
    private data class PaidOrder(val owner: ReturnJourneyActor, val checkoutId: String, val orderId: String,
        val parts: Map<String, String>, val createdAt: Instant, val deliveredAt: Instant?) {
        fun part(merchant: String) = requireNotNull(parts[merchant])
    }
    private fun merchant(label: String, price: Int): Merchant {
        val id = "merchant-read-${UUID.randomUUID()}"
        merchants.save(MerchantRecord(id, name = label))
        val owner = ReturnCustodyFixtures.actor(context, RoleCode.MERCHANT_OWNER, id)
        val product = products.save(ProductRecord("merchant-read-product-${UUID.randomUUID()}", merchantId = id,
            name = "${label}_PRIVATE_PRODUCT", kind = CatalogProductKind.GenericSealedItem, basePriceCfa = price,
            imageUrl = "https://fixture.invalid/product.jpg"))
        return Merchant(id, owner, product.id, product.name, price)
    }
    private fun order(items: List<Pair<Merchant, Int>>, delivered: Boolean = false,
        status: MerchantSubOrderStatus? = null, pendingOnly: Boolean = false): PaidOrder {
        val owner = ReturnCustodyFixtures.actor(context, RoleCode.CUSTOMER)
        val checkoutId = "merchant-read-checkout-${UUID.randomUUID()}"
        val createdAt = now.minusSeconds(2 * 86400L)
        val request = OrderProcessingRequest(checkoutId, owner.id, OrderServiceLevel.Regular, OrderRoute.FastDelivery,
            items.map { (merchant, quantity) -> OrderLineRequest(merchant.productId, merchant.id, merchant.name, merchant.name,
                OrderProductCategory.GenericSealedItem, quantity, merchant.price, photoEvidence = ProductPhotoEvidence.GenericCatalogImage) },
            1.0, paymentProvider = SequoPaymentProviders.YasTogo, paymentReference = "PRIVATE_PAYMENT_$checkoutId")
        val processor = RegularOrderProcessor(PaymentProcessor(listOf(YasTogoPaymentMethod(validateHandler = {
            PaymentValidationResult.validated(it.paymentReference)
        }))), DeliveryPricingService())
        val accepted = processor.process(request) as OrderProcessingResult.AcceptedForFulfillment
        pending.recordCartCheckout(request, accepted.order.pricing,
            if (pendingOnly) PendingPaymentCheckoutStatus.AWAITING_WEBHOOK else PendingPaymentCheckoutStatus.COMPLETED, createdAt)
        if (pendingOnly) return PaidOrder(owner, checkoutId, accepted.order.orderId, emptyMap(), createdAt, null)
        val persisted = fulfillment.persistAcceptedOrder(request, accepted, createdAt)
        val deliveredAt = now.minusSeconds(3600).takeIf { delivered }
        if (delivered) orders.findById(persisted.order.id).orElseThrow().also {
            it.orderStatus = CustomerOrderStatus.DELIVERED; it.deliveredAt = deliveredAt
            it.returnWindowEndsAt = requireNotNull(deliveredAt).plus(Duration.ofHours(72)); orders.save(it)
        }
        val partStatus = status ?: if (delivered) MerchantSubOrderStatus.HANDED_TO_COURIER else MerchantSubOrderStatus.MERCHANT_PENDING
        persisted.merchantSubOrders.forEach { snapshot -> subOrders.findById(snapshot.id).orElseThrow().also {
            it.status = partStatus; subOrders.save(it)
        } }
        return PaidOrder(owner, checkoutId, persisted.order.id, persisted.merchantSubOrders.associate { it.merchantId to it.id }, createdAt, deliveredAt)
    }
    private fun assertFailClosedAndEmpty(existing: Merchant) {
        val empty = merchant("empty", 1000)
        val customer = ReturnCustodyFixtures.actor(context, RoleCode.CUSTOMER)
        val owner = users.findById(existing.owner.id).orElseThrow()
        val expired = JwtService("isolated-merchant-business-read-fixture-2026-secret", -3600000, 3600000, "sequo-api", "sequo-mobile")
            .generateAccessToken(UserSession(requireNotNull(owner.id), owner.email, owner.provider, owner.roles, setOf(existing.id)))
        for (path in listOf(historyPath(empty), "/api/returns/merchant?merchantId=${empty.id}", payoutPath(empty))) {
            assertTrue(get(path, empty.owner.token).isEmpty())
            assertEquals(403, call("GET", path, existing.owner.token).statusCode())
        }
        for (path in listOf(historyPath(existing), "/api/returns/merchant?merchantId=${existing.id}", payoutPath(existing))) {
            for (token in listOf(null, expired, "malformed-token", customer.token)) {
                val response = call("GET", path, token)
                assertTrue(response.statusCode() in setOf(401, 403), response.body())
                assertFalse(response.body().contains(existing.productId))
            }
        }
    }
    private fun assertPrivacy(row: JsonNode, customerId: String, foreign: Merchant) {
        val forbidden = setOf("customerId", "homeDestination", "recipientName", "phoneNumber", "paymentReference", "providerReference",
            "returnPin", "reason", "requestedRefundCfa", "refundAmountCfa", "history", "metadata", "courierId", "relayPointId")
        fun check(node: JsonNode) {
            if (node.isObject) node.fields().forEachRemaining { (name, value) ->
                assertFalse(name in forbidden && !value.isNull, "Unexpected merchant disclosure: $name")
                check(value)
            } else if (node.isArray) node.forEach(::check)
        }
        check(row)
        assertFalse(row.toString().contains(customerId))
        assertFalse(row.toString().contains(foreign.productId))
        assertFalse(row.toString().contains(foreign.name))
        assertFalse(row.toString().contains("PRIVATE_PAYMENT_"))
    }
    private fun historyPath(merchant: Merchant, history: Boolean = true) = "/api/merchant/sub-orders?merchantId=${merchant.id}&history=$history"
    private fun payoutPath(merchant: Merchant) = "/api/settlements/merchant-payouts?merchantId=${merchant.id}"
    private fun get(path: String, token: String, requireNoStore: Boolean = true): JsonNode {
        val response = call("GET", path, token)
        assertEquals(200, response.statusCode(), response.body())
        if (requireNoStore) assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"), "Missing no-store: $path")
        return json.readTree(response.body())
    }
    private fun post(path: String, token: String, body: String): JsonNode {
        val response = call("POST", path, token, body)
        assertEquals(200, response.statusCode(), response.body())
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
