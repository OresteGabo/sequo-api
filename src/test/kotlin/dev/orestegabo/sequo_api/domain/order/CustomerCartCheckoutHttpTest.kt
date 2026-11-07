package dev.orestegabo.sequo_api.domain.order

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressKind
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressRecord
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressRepository
import dev.orestegabo.sequo_api.domain.platform.PaymentProviderConfigurationRepository
import dev.orestegabo.sequo_api.domain.platform.PaymentProviderConfigurationRecord
import dev.orestegabo.sequo_api.domain.platform.PaymentProviderReadiness
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRepository
import dev.orestegabo.sequo_api.domain.delivery.DeliveryPinRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:cart_checkout_http;DB_CLOSE_DELAY=-1",
        "sequo.cart.straight-line-dispatch-origin=0,0",
        "sequo.wallets.yas-togo.webhook-secret=isolated-fixture-webhook-secret",
        "sequo.wallets.yas-togo.api-base-url=https://fixture.invalid",
        "sequo.wallets.yas-togo.api-key=isolated-fixture-key",
        "sequo.wallets.yas-togo.merchant-account-id=isolated-fixture-account",
    ],
)
class CustomerCartCheckoutHttpTest @Autowired constructor(
    private val users: UserRepository,
    private val jwt: JwtService,
    private val products: CommerceProductRepository,
    private val merchants: CommerceMerchantRepository,
    private val addresses: CustomerAddressRepository,
    private val pending: PendingPaymentCheckoutRepository,
    private val reconciliation: PendingPaymentCheckoutService,
    private val orders: CustomerOrderRecordRepository,
    private val providerConfigurations: PaymentProviderConfigurationRepository,
    private val couriers: SettlementCourierRecordRepository,
    private val deliveryMissions: DeliveryMissionRepository,
    private val deliveryPins: DeliveryPinRepository,
    private val memberships: MerchantMembershipRepository,
    private val relayPoints: dev.orestegabo.sequo_api.domain.relay.RelayPointRepository,
    private val relayLockers: dev.orestegabo.sequo_api.domain.hub.RelayLockerRepository,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) {
    companion object {
        @JvmStatic @org.springframework.test.context.DynamicPropertySource
        fun isolatedPostgres(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_RELAY_JOURNEY_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) { "Use a disposable local Task 14 fixture database." }
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
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    @Test
    fun submitConcurrentRetryAndReconciliationPreserveOwnershipDestinationAndPaymentGate() {
        val fixture = fixture()
        val quote = quote(fixture)
        val payload = submitBody(fixture, quote)
        val responses = (1..2).map {
            CompletableFuture.supplyAsync { call("POST", "/api/orders/checkouts", fixture.token, payload) }
        }.map { it.join() }
        responses.forEach { assertEquals(202, it.statusCode(), it.body()) }
        val checkout = json.readTree(responses.first().body())
        val id = checkout["checkoutId"].asText()
        assertEquals(id, json.readTree(responses.last().body())["checkoutId"].asText())
        assertEquals(1, pending.findByCustomerIdOrderByCreatedAtDesc(fixture.userId).size)
        assertNull(orders.findByCheckoutId(id))
        assertEquals(0, read("GET", "/api/cart", fixture.token)["totalItems"].asInt())
        val saved = pending.findById(id).orElseThrow()
        val request = json.readPendingRequest(saved.requestPayload)
        assertEquals(fixture.addressId, request.homeDestination?.addressId)
        assertEquals("Original recipient", request.homeDestination?.recipientName)
        assertEquals(fixture.productId, request.lines.single().productId)
        assertEquals(2, request.lines.single().quantity)
        assertEquals(2000, request.lines.single().unitPriceCfa)

        val other = fixture()
        assertEquals(404, call("GET", "/api/orders/checkouts/$id", other.token).statusCode())
        assertEquals(403, call("GET", "/api/orders/checkouts/$id").statusCode())
        assertTrue(read("GET", "/api/orders/checkouts", other.token).none { it["checkoutId"].asText() == id })
        addresses.findById(fixture.addressId).orElseThrow().also {
            it.recipientName = "Changed recipient"
            addresses.save(it)
        }
        assertEquals(202, call("POST", "/api/orders/checkouts", fixture.token, payload).statusCode())
        assertEquals("Original recipient", json.readPendingRequest(pending.findById(id).orElseThrow().requestPayload).homeDestination?.recipientName)

        val now = Instant.now()
        val event = PaymentWebhookAcceptedEvent(
            SequoPaymentProviders.YasTogo, "fixture-event-$id", id, id, "fixture-provider-reference",
            saved.amountCfa, PaymentWebhookProviderStatus.VALIDATED, now, now,
        )
        assertTrue(reconciliation.reconcile(event) is PaymentReconciliationResult.Fulfilled)
        assertTrue(reconciliation.reconcile(event) is PaymentReconciliationResult.Fulfilled)
        assertEquals(1, orders.findByCustomerIdOrderByCreatedAtDesc(fixture.userId).size)
        val paid = assertNotNull(orders.findByCheckoutId(id))
        assertEquals(200, call("GET", "/api/orders/${paid.id}", fixture.token).statusCode())
        assertEquals(404, call("GET", "/api/orders/${paid.id}", other.token).statusCode())
        assertEquals("COMPLETED", read("GET", "/api/orders/checkouts/$id", fixture.token)["status"].asText())
        assertEquals(200, call("POST", "/api/orders/checkouts", fixture.token, payload).statusCode())
    }

    @Test
    fun changedQuoteRequiresReviewAndRejectedOrderNeverEntersFulfillment() {
        val fixture = fixture()
        val quote = quote(fixture)
        products.findById(fixture.productId).orElseThrow().also {
            it.basePriceCfa = 2200
            products.save(it)
        }
        val changed = call("POST", "/api/orders/checkouts", fixture.token, submitBody(fixture, quote))
        assertEquals(409, changed.statusCode(), changed.body())
        assertEquals("quote_changed", json.readTree(changed.body())["code"].asText())
        assertTrue(pending.findByCustomerIdOrderByCreatedAtDesc(fixture.userId).isEmpty())
        assertEquals(2, read("GET", "/api/cart", fixture.token)["totalItems"].asInt())

        products.findById(fixture.productId).orElseThrow().also {
            it.imageUrl = null
            products.save(it)
        }
        val rejected = call("POST", "/api/orders/checkouts", fixture.token, submitBody(fixture, quote(fixture)))
        assertEquals(422, rejected.statusCode(), rejected.body())
        val id = json.readTree(rejected.body())["checkoutId"].asText()
        assertEquals("FAILED", read("GET", "/api/orders/checkouts/$id", fixture.token)["status"].asText())
        assertNull(orders.findByCheckoutId(id))

        products.findById(fixture.productId).orElseThrow().also {
            it.imageUrl = "https://fixture.invalid/corrected.jpg"
            products.save(it)
        }
        val corrected = call("POST", "/api/orders/checkouts", fixture.token, submitBody(fixture, quote(fixture)))
        assertEquals(202, corrected.statusCode(), corrected.body())
        assertEquals(false, id == json.readTree(corrected.body())["checkoutId"].asText())
    }

    @Test
    fun signedPaymentFixturesExercisePendingRejectionMismatchAndDuplicateSuccess() {
        val fixture = fixture()
        val submitted = call("POST", "/api/orders/checkouts", fixture.token, submitBody(fixture, quote(fixture)))
        assertEquals(202, submitted.statusCode(), submitted.body())
        val checkout = json.readTree(submitted.body())
        val id = checkout["checkoutId"].asText()
        val total = checkout["totalCfa"].asInt()
        fun state() = read("GET", "/api/orders/checkouts/$id", fixture.token)["status"].asText()
        fun event(eventId: String, status: String, amount: Int = total, reference: String = id, currency: String = "XOF"): String =
            """{"eventId":"$eventId","checkoutId":"$id","paymentReference":"$reference","amountCfa":$amount,"currency":"$currency","status":"$status","occurredAt":"2026-11-07T00:00:00Z"}"""

        for (payload in listOf(
            event("pending-$id", "PENDING"),
            event("wrong-amount-$id", "VALIDATED", amount = total + 1),
            event("wrong-reference-$id", "VALIDATED", reference = "wrong-reference"),
        )) {
            val response = webhook(payload)
            assertEquals(202, response.statusCode(), response.body())
            assertEquals("AWAITING_WEBHOOK", state())
            assertNull(orders.findByCheckoutId(id))
        }
        val currency = webhook(event("wrong-currency-$id", "VALIDATED", currency = "USD"))
        assertEquals(400, currency.statusCode(), currency.body())
        assertEquals("AWAITING_WEBHOOK", state())
        val forged = webhook(event("forged-$id", "VALIDATED"), signatureOverride = "00".repeat(32))
        assertEquals(400, forged.statusCode())
        assertNull(orders.findByCheckoutId(id))

        val success = event("success-$id", "VALIDATED")
        assertEquals(202, webhook(success).statusCode())
        assertEquals("COMPLETED", state())
        val duplicate = webhook(success)
        assertEquals(202, duplicate.statusCode(), duplicate.body())
        assertEquals(true, json.readTree(duplicate.body())["duplicate"].asBoolean())
        assertEquals(1, orders.findByCustomerIdOrderByCreatedAtDesc(fixture.userId).size)
        val details = read("GET", "/api/orders/${orders.findByCheckoutId(id)!!.id}", fixture.token)
        assertEquals(1, details["merchantSubOrders"].size())
        assertEquals(2, details["lines"].single()["quantity"].asInt())

        val rejectedFixture = fixture()
        val rejected = call("POST", "/api/orders/checkouts", rejectedFixture.token, submitBody(rejectedFixture, quote(rejectedFixture)))
        val failed = json.readTree(rejected.body())
        val failedId = failed["checkoutId"].asText()
        val failure = """{"eventId":"failed-$failedId","checkoutId":"$failedId","paymentReference":"$failedId","amountCfa":${failed["totalCfa"].asInt()},"currency":"XOF","status":"FAILED","occurredAt":"2026-11-07T00:00:00Z"}"""
        assertEquals(202, webhook(failure).statusCode())
        assertEquals("FAILED", read("GET", "/api/orders/checkouts/$failedId", rejectedFixture.token)["status"].asText())
        assertEquals(202, webhook(failure.replace("failed-$failedId", "late-$failedId").replace("FAILED", "VALIDATED")).statusCode())
        assertNull(orders.findByCheckoutId(failedId))
    }

    @Test
    fun providerReadinessExposesConfigurationWithoutCredentialsOrClaimingConnection() {
        providerConfigurations.save(PaymentProviderConfigurationRecord(
            "yas_togo", "Mixx by Yas", "TG", PaymentProviderReadiness.LIVE_READY,
            customerCheckoutEnabled = true,
        ))
        val response = call("GET", "/api/payments/providers")
        assertEquals(200, response.statusCode(), response.body())
        val provider = json.readTree(response.body()).single { it["providerId"].asText() == "yas_togo" }
        assertEquals(true, provider["apiConfigured"].asBoolean())
        assertEquals(false, provider["checkoutConnected"].asBoolean())
        assertEquals(false, response.body().contains("isolated-fixture"))
        assertEquals(false, response.body().contains("fixture.invalid"))
    }

    @Test
    fun paidBasketReachesScopedMerchantPreparationAndDispatchWithQuantitiesAndReplaySafeActions() {
        val fixture = fixture()
        val merchantId = requireNotNull(products.findById(fixture.productId).orElseThrow().merchantId)
        val merchant = jwt.generateAccessToken(UserSession(
            "preparer-${fixture.userId}", null, AuthProvider.EMAIL, setOf(RoleCode.MERCHANT_OWNER), setOf(merchantId),
        ))
        val otherFixture = fixture()
        val otherMerchantId = requireNotNull(products.findById(otherFixture.productId).orElseThrow().merchantId)
        val otherMerchant = jwt.generateAccessToken(UserSession(
            "other-preparer", null, AuthProvider.EMAIL, setOf(RoleCode.MERCHANT_STAFF), setOf(otherMerchantId),
        ))
        val foodId = "food-${fixture.userId}"
        products.save(ProductRecord(
            id = foodId, merchantId = merchantId, name = "Rice plate", category = "food", basePriceCfa = 1500,
            photoRequired = true, cameraVerified = true, capturedAtLabel = Instant.now().toString(),
        ))
        val options = call("PUT", "/api/merchant/catalog/products/$foodId/customizations?merchantId=$merchantId", merchant,
            """{"groups":[{"name":"Side","minChoices":1,"maxChoices":1,"options":[{"name":"Plantain","priceDeltaCfa":500}]}]}""")
        assertEquals(200, options.statusCode(), options.body())
        val group = json.readTree(options.body()).single()
        val selection = """[{"groupId":"${group["id"].asText()}","optionIds":["${group["options"].single()["id"].asText()}"]}]"""
        assertEquals(200, call("PUT", "/api/cart/items", fixture.token,
            """{"productId":"$foodId","quantity":3,"selections":$selection}""").statusCode())
        val submitted = call("POST", "/api/orders/checkouts", fixture.token, submitBody(fixture, quote(fixture)))
        assertEquals(202, submitted.statusCode(), submitted.body())
        val checkout = json.readTree(submitted.body())
        val checkoutId = checkout["checkoutId"].asText()
        val queue = "/api/merchant/sub-orders?merchantId=$merchantId"
        assertEquals(0, read("GET", queue, merchant).size())
        assertEquals(403, call("GET", queue, otherMerchant).statusCode())
        val unpaid = call("POST", "/api/merchant/sub-orders/$checkoutId/accept", merchant, """{"merchantId":"$merchantId"}""")
        assertEquals(400, unpaid.statusCode())
        assertNull(orders.findByCheckoutId(checkoutId))

        val event = """{"eventId":"merchant-paid-$checkoutId","checkoutId":"$checkoutId","paymentReference":"$checkoutId","amountCfa":${checkout["totalCfa"].asInt()},"currency":"XOF","status":"VALIDATED","occurredAt":"2026-11-07T00:00:00Z"}"""
        assertEquals(202, webhook(event).statusCode())
        val queued = read("GET", queue, merchant).single()
        assertEquals("MERCHANT_PENDING", queued["status"].asText())
        assertEquals(setOf(2, 3), queued["lines"].map { it["quantity"].asInt() }.toSet())
        assertEquals("Plantain", queued["lines"].single { it["productId"].asText() == foodId }["optionLabel"].asText())
        assertTrue(Instant.parse(queued["packingDueAt"].asText()).isAfter(Instant.now()))
        val subOrderId = queued["id"].asText()
        val actionPath = "/api/merchant/sub-orders/$subOrderId"
        assertEquals(403, call("GET", "$actionPath?merchantId=$merchantId", otherMerchant).statusCode())
        assertEquals(403, call("POST", "$actionPath/accept", fixture.token, """{"merchantId":"$merchantId"}""").statusCode())
        assertEquals(403, call("POST", "$actionPath/accept", otherMerchant, """{"merchantId":"$merchantId"}""").statusCode())
        assertEquals(400, call("POST", "$actionPath/accept", otherMerchant, """{"merchantId":"$otherMerchantId"}""").statusCode())

        fun replayAction(action: String, body: String): JsonNode {
            val results = (1..2).map { CompletableFuture.supplyAsync { call("POST", "$actionPath/$action", merchant, body) } }.map { it.join() }
            results.forEach { assertEquals(200, it.statusCode(), it.body()) }
            assertEquals(json.readTree(results.first().body()), json.readTree(results.last().body()))
            return json.readTree(results.first().body())
        }
        assertEquals("ACCEPTED", replayAction("accept", """{"merchantId":"$merchantId"}""")["status"].asText())
        assertEquals("PREPARING", replayAction("start-preparation", """{"merchantId":"$merchantId"}""")["status"].asText())
        val ready = replayAction("mark-packed", """{"merchantId":"$merchantId","packageCount":2}""")
        assertEquals("PACKED_READY", ready["status"].asText())
        assertEquals(2, ready["packageCount"].asInt())
        assertEquals(400, call("POST", "$actionPath/mark-packed", merchant, """{"merchantId":"$merchantId","packageCount":3}""").statusCode())
        val sla = read("GET", "$actionPath/sla?merchantId=$merchantId", merchant)
        assertEquals(queued["packingDueAt"], sla["packingDueAt"])
        val admin = jwt.generateAccessToken(UserSession("fixture-operations", null, AuthProvider.EMAIL, setOf(RoleCode.ADMIN)))
        assertEquals(ready, read("GET", actionPath, admin))
        assertEquals(403, call("POST", "/api/delivery/missions/dispatch-ready", merchant).statusCode())
        val dispatched = call("POST", "/api/delivery/missions/dispatch-ready", admin)
        assertEquals(200, dispatched.statusCode(), dispatched.body())
        val mission = json.readTree(dispatched.body())["createdMissions"].single { it["merchantSubOrderId"].asText() == subOrderId }
        assertEquals(ready["orderId"].asText(), mission["orderId"].asText())
    }

    @Test
    fun homeJourneyUsesRealRoleAccountsExclusiveAcceptanceProofAndPersistedTracking() {
        val fixture = fixture()
        val merchantId = requireNotNull(products.findById(fixture.productId).orElseThrow().merchantId)
        val merchantUser = users.save(User(email = "merchant-${fixture.userId}@sequo.test", provider = AuthProvider.EMAIL,
            roles = mutableSetOf(RoleCode.MERCHANT_OWNER)))
        memberships.save(MerchantMembership(userId = requireNotNull(merchantUser.id), merchantId = merchantId, role = RoleCode.MERCHANT_OWNER))
        val merchant = jwt.generateAccessToken(UserSession(requireNotNull(merchantUser.id), merchantUser.email,
            AuthProvider.EMAIL, setOf(RoleCode.MERCHANT_OWNER), setOf(merchantId)))
        fun account(role: RoleCode): Pair<String, String> {
            val user = users.save(User(email = "${UUID.randomUUID()}@sequo.test", provider = AuthProvider.EMAIL, roles = mutableSetOf(role)))
            val id = requireNotNull(user.id)
            return id to jwt.generateAccessToken(UserSession(id, user.email, AuthProvider.EMAIL, setOf(role)))
        }
        val (adminId, admin) = account(RoleCode.ADMIN)
        val (riderOneId, riderOne) = account(RoleCode.COURIER)
        val (riderTwoId, riderTwo) = account(RoleCode.COURIER)
        val courierOne = couriers.save(CourierRecord(id = "courier-$riderOneId", userId = riderOneId, workforceType = "FREELANCER"))
        val courierTwo = couriers.save(CourierRecord(id = "courier-$riderTwoId", userId = riderTwoId, workforceType = "FREELANCER"))
        for (token in listOf(fixture.token, merchant, admin, riderOne, riderTwo)) {
            assertEquals(200, call("GET", "/api/auth/me", token).statusCode())
        }
        assertNotNull(users.findById(adminId).orElse(null))
        val submitted = call("POST", "/api/orders/checkouts", fixture.token, submitBody(fixture, quote(fixture)))
        assertEquals(202, submitted.statusCode(), submitted.body())
        val checkout = json.readTree(submitted.body())
        val checkoutId = checkout["checkoutId"].asText()
        assertEquals(202, webhook("""{"eventId":"journey-$checkoutId","checkoutId":"$checkoutId","paymentReference":"$checkoutId","amountCfa":${checkout["totalCfa"].asInt()},"currency":"XOF","status":"VALIDATED","occurredAt":"2026-11-07T00:00:00Z"}""").statusCode())
        val subOrder = read("GET", "/api/merchant/sub-orders?merchantId=$merchantId", merchant).single()
        val subId = subOrder["id"].asText()
        val orderId = subOrder["orderId"].asText()
        assertEquals(200, call("POST", "/api/merchant/sub-orders/$subId/accept", merchant, """{"merchantId":"$merchantId"}""").statusCode())
        assertEquals(200, call("POST", "/api/merchant/sub-orders/$subId/start-preparation", merchant, """{"merchantId":"$merchantId"}""").statusCode())
        assertEquals(200, call("POST", "/api/merchant/sub-orders/$subId/mark-packed", merchant, """{"merchantId":"$merchantId","packageCount":2}""").statusCode())
        val dispatched = call("POST", "/api/delivery/missions/dispatch-ready", admin)
        assertEquals(200, dispatched.statusCode(), dispatched.body())
        val mission = json.readTree(dispatched.body())["createdMissions"].single { it["orderId"].asText() == orderId }
        val missionId = mission["id"].asText()
        val deliveryCode = mission["deliveryCode"].asText()
        assertEquals(200, call("POST", "/api/delivery/missions/$missionId/offer", admin).statusCode())
        val offered = read("GET", "/api/rider/missions/$missionId", riderOne)
        assertTrue(offered["homeDestination"].isNull)
        val race = listOf(riderOne, riderTwo).map { token ->
            CompletableFuture.supplyAsync { token to call("POST", "/api/rider/missions/$missionId/accept", token) }
        }.map { it.join() }
        assertEquals(1, race.count { it.second.statusCode() == 200 })
        assertEquals(1, race.count { it.second.statusCode() == 400 })
        val winning = race.single { it.second.statusCode() == 200 }.first
        val losing = race.single { it.second.statusCode() != 200 }.first
        val winningId = if (winning == riderOne) riderOneId else riderTwoId
        val courierId = if (winning == riderOne) courierOne.id else courierTwo.id
        assertEquals(courierId, read("GET", "/api/rider/missions/$missionId", winning)["courierId"].asText())
        assertEquals(200, call("POST", "/api/rider/missions/$missionId/accept", winning).statusCode())
        assertEquals(403, call("GET", "/api/rider/missions/$missionId", losing).statusCode())
        assertEquals(403, call("GET", "/api/admin/web/deliveries", winning).statusCode())

        fun consistentState(expected: String) {
            val customer = read("GET", "/api/orders/$orderId", fixture.token)
            assertEquals(expected, customer["deliveries"].single { it["deliveryCode"].asText() == deliveryCode }["status"].asText())
            assertTrue(!customer["deliveries"].toString().contains("proofMetadata"))
            assertTrue(!customer["deliveries"].toString().contains("phoneNumber"))
            val operations = read("GET", "/api/admin/web/deliveries", admin)["missions"].single { it["id"].asText() == missionId }
            assertEquals(expected, operations["status"].asText())
        }
        consistentState("ACCEPTED_BY_COURIER")
        val assigned = read("GET", "/api/rider/missions/$missionId", winning)
        assertEquals(fixture.addressId, assigned["homeDestination"]["addressId"].asText())
        assertEquals(2, assigned["packageCount"].asInt())
        assertEquals(400, call("POST", "/api/rider/missions/$missionId/pickup/complete", winning,
            """{"proofMetadata":"","idempotencyKey":"pickup-$missionId"}""").statusCode())
        assertEquals(400, call("POST", "/api/rider/missions/$missionId/pickup/complete", losing,
            """{"proofMetadata":"Wrong rider receipt","idempotencyKey":"wrong-pickup-$missionId"}""").statusCode())
        val pickupBody = """{"proofMetadata":"Merchant handed over two packages","idempotencyKey":"pickup-$missionId"}"""
        assertEquals(200, call("POST", "/api/rider/missions/$missionId/pickup/complete", winning, pickupBody).statusCode())
        assertEquals(200, call("POST", "/api/rider/missions/$missionId/pickup/complete", winning, pickupBody).statusCode())
        consistentState("PICKED_UP_FROM_SELLER")
        assertEquals(200, call("POST", "/api/delivery/missions/$missionId/delivery-pin", admin,
            """{"rawPin":"123456","expiresAt":"${Instant.now().plusSeconds(3600)}"}""").statusCode())
        val wrong = call("POST", "/api/rider/missions/$missionId/delivery/complete", winning,
            """{"deliveryPin":"111111","idempotencyKey":"delivery-$missionId"}""")
        assertEquals(400, wrong.statusCode(), wrong.body())
        val pin = deliveryPins.findFirstByDeliveryMissionIdAndUsedAtIsNullOrderByCreatedAtDesc(missionId)
        assertNotNull(pin)
        assertEquals(1, pin.attemptCount)
        assertEquals(400, call("POST", "/api/rider/missions/$missionId/delivery/complete", losing,
            """{"deliveryPin":"123456","idempotencyKey":"other-delivery-$missionId"}""").statusCode())
        assertEquals(1, deliveryPins.findById(requireNotNull(pin.id)).orElseThrow().attemptCount)
        consistentState("PICKED_UP_FROM_SELLER")
        val deliveryBody = """{"deliveryPin":"123456","idempotencyKey":"delivery-$missionId"}"""
        val completions = (1..2).map {
            CompletableFuture.supplyAsync { call("POST", "/api/rider/missions/$missionId/delivery/complete", winning, deliveryBody) }
        }.map { it.join() }
        completions.forEach { assertEquals(200, it.statusCode(), it.body()) }
        consistentState("DELIVERED_TO_CUSTOMER")
        assertEquals(2, deliveryPins.findById(requireNotNull(pin.id)).orElseThrow().attemptCount)
        assertEquals(winningId, deliveryMissions.findById(missionId).orElseThrow().dropoffProofActorId)
        val history = read("GET", "/api/rider/missions", winning).single { it["id"].asText() == missionId }
        assertEquals("DELIVERED_TO_CUSTOMER", history["status"].asText())
        assertTrue(history["homeDestination"].isNull)
        assertTrue(read("GET", "/api/rider/missions", losing).none { it["id"].asText() == missionId })
        assertEquals("DELIVERED", read("GET", "/api/orders/$orderId", fixture.token)["order"]["orderStatus"].asText())
        assertEquals(400, call("POST", "/api/rider/missions/$missionId/pickup/complete", winning,
            """{"proofMetadata":"Stale pickup attempt","idempotencyKey":"stale-$missionId"}""").statusCode())
        assertEquals(404, call("GET", "/api/orders/$orderId", fixture().token).statusCode())
    }

    @Test
    fun relaySelectionPersistsThroughPaidDispatchAndAtomicDeposit() {
        val site = relayFixture()
        val other = relayFixture()
        val cart = fixture()
        assertTrue(read("GET", "/api/cart/relay-points", cart.token).any { it["id"].asText() == site.id })
        relayPoints.findById(site.id).orElseThrow().also { it.latitude = null; relayPoints.save(it) }
        assertFalse(read("GET", "/api/cart/relay-points", cart.token).any { it["id"].asText() == site.id })
        relayPoints.findById(site.id).orElseThrow().also { it.latitude = 0.0; relayPoints.save(it) }
        relayLockers.findById(site.slotId).orElseThrow().also { it.status = dev.orestegabo.sequo_api.domain.hub.HubLockerStatus.MAINTENANCE; relayLockers.save(it) }
        assertFalse(read("GET", "/api/cart/relay-points", cart.token).any { it["id"].asText() == site.id })
        relayLockers.findById(site.slotId).orElseThrow().also { it.status = dev.orestegabo.sequo_api.domain.hub.HubLockerStatus.AVAILABLE; relayLockers.save(it) }
        for (category in listOf("food", "perishable", "sensitive", "grocery", "pharmacy")) {
            products.findById(cart.productId).orElseThrow().also { it.category = category; products.save(it) }
            assertTrue(read("GET", "/api/cart/relay-points", cart.token).isEmpty)
            assertEquals(400, call("GET", "/api/cart/quote?addressId=${cart.addressId}&relayPointId=${site.id}", cart.token).statusCode())
        }
        products.findById(cart.productId).orElseThrow().also { it.category = null; products.save(it) }
        val reviewed = read("GET", "/api/cart/quote?addressId=${cart.addressId}&relayPointId=${site.id}", cart.token)
        assertEquals("RELAY", reviewed["fulfillmentMode"].asText())
        assertEquals(site.id, reviewed["relayPointId"].asText())
        assertEquals(2224, reviewed["distanceMeters"].asInt())
        val unavailableBody = """{"addressId":"${cart.addressId}","relayPointId":"${site.id}","quoteRevision":"${reviewed["revision"].asText()}","paymentProvider":"yas_togo"}"""
        relayPoints.findById(site.id).orElseThrow().also { it.status = "INACTIVE"; relayPoints.save(it) }
        assertFalse(read("GET", "/api/cart/relay-points", cart.token).any { it["id"].asText() == site.id })
        assertEquals(409, call("POST", "/api/orders/checkouts", cart.token, unavailableBody).statusCode())
        assertTrue(pending.findByCustomerIdOrderByCreatedAtDesc(cart.userId).isEmpty())
        relayPoints.findById(site.id).orElseThrow().also { it.status = "ACTIVE"; relayPoints.save(it) }
        val journey = paidPickedUp(site.id)
        val riderRead = read("GET", "/api/rider/missions/${journey.missionId}", journey.rider)
        assertEquals(site.id, riderRead["relayDestination"]["id"].asText())
        assertTrue(riderRead["homeDestination"].isNull)
        assertEquals("PointDeRelai", read("GET", "/api/orders/${journey.orderId}", journey.customer.token)["order"]["route"].asText())
        val key = "deposit-${journey.missionId}"
        fun deposit(relay: String, slot: String = "SLOT-1") = """{"relayPointId":"$relay","lockerCode":"$slot","proofMetadata":"Operator accepted staged parcel","idempotencyKey":"$key"}"""
        val path = "/api/rider/missions/${journey.missionId}/relay-deposit"
        assertEquals(400, call("POST", path, journey.rider, deposit(other.id)).statusCode())
        assertEquals(400, call("POST", path, journey.rider, deposit(site.id, "guessed-slot")).statusCode())
        assertEquals(400, call("POST", "/api/delivery/missions/${journey.missionId}/relay-deposit", journey.rider,
            """{"proofMetadata":"Legacy unscoped deposit","idempotencyKey":"old-key"}""").statusCode())
        val calls = (1..2).map { CompletableFuture.supplyAsync { call("POST", path, journey.rider, deposit(site.id)) } }.map { it.join() }
        calls.forEach { assertEquals(200, it.statusCode(), it.body()) }
        assertEquals(400, call("POST", path, journey.rider, deposit(site.id, "changed-slot-on-retry")).statusCode())
        assertEquals(dev.orestegabo.sequo_api.domain.hub.HubLockerStatus.OCCUPIED, relayLockers.findById(site.slotId).orElseThrow().status)
        val parcels = read("GET", "/api/relay/parcels?relayPointId=${site.id}", site.operator)
        val parcel = parcels.single { it["deliveryMissionId"].asText() == journey.missionId }
        assertEquals(journey.orderId, parcel["orderId"].asText())
        assertEquals(site.slotId, parcel["lockerId"].asText())
        assertEquals("Deposited", parcel["status"].asText())
        assertEquals(403, call("GET", "/api/relay/parcels/${parcel["id"].asText()}", other.operator).statusCode())
        val tracking = read("GET", "/api/orders/${journey.orderId}", journey.customer.token)["deliveries"].single()
        assertEquals(site.id, tracking["relayDestination"]["id"].asText())
        assertEquals("DEPOSITED_AT_RELAY", tracking["status"].asText())
        assertFalse(tracking["relayDepositedAt"].isNull)
        val ops = read("GET", "/api/delivery/missions/${journey.missionId}", journey.admin)
        assertEquals(tracking["relayDestination"], ops["relayDestination"])
        assertFalse(read("GET", "/api/cart/relay-points", cart.token).any { it["id"].asText() == site.id })
        val parcelId = parcel["id"].asText()
        assertEquals(200, call("POST", "/api/relay/parcels/$parcelId/pickup-code", site.operator,
            """{"codeId":"code-$parcelId","rawNumericCode":"123456","identityCheckRequired":true,"expiresAt":"${Instant.now().plusSeconds(3600)}"}""").statusCode())
        assertEquals(200, call("POST", "/api/relay/parcels/$parcelId/release", site.operator,
            """{"relayPointId":"${site.id}","rawNumericCode":"123456","identityDocumentMatched":true,"eventId":"release-$parcelId","idempotencyKey":"release-$parcelId"}""").statusCode())
        assertEquals(dev.orestegabo.sequo_api.domain.hub.HubLockerStatus.AVAILABLE, relayLockers.findById(site.slotId).orElseThrow().status)
        assertTrue(read("GET", "/api/cart/relay-points", cart.token).any { it["id"].asText() == site.id })
    }

    @Test
    fun failedHomeHandoffRequiresStaffCustomerApprovalAndSharedExclusionsForReroute() {
        val site = relayFixture()
        val home = paidPickedUp()
        val path = "/api/delivery/missions/${home.missionId}/relay-reroute"
        val request = """{"relayPointId":"${site.id}","customerApproved":true,"reason":"Customer unavailable at home; agreed relay","idempotencyKey":"reroute-${home.missionId}"}"""
        assertEquals(400, call("POST", path, home.admin, request).statusCode())
        assertEquals(200, call("POST", "/api/rider/missions/${home.missionId}/problem", home.rider,
            """{"reason":"Customer unavailable at home","idempotencyKey":"problem-${home.missionId}"}""").statusCode())
        assertEquals(403, call("POST", path, home.rider, request).statusCode())
        assertEquals(403, call("POST", path, home.customer.token, request).statusCode())
        assertEquals(400, call("POST", path, home.admin, request.replace("true", "false")).statusCode())
        relayPoints.findById(site.id).orElseThrow().also { it.status = "INACTIVE"; relayPoints.save(it) }
        assertEquals(400, call("POST", path, home.admin, request).statusCode())
        relayPoints.findById(site.id).orElseThrow().also { it.status = "ACTIVE"; relayPoints.save(it) }
        val approved = call("POST", path, home.admin, request)
        assertEquals(200, approved.statusCode(), approved.body())
        assertEquals(200, call("POST", path, home.admin, request).statusCode())
        val mission = read("GET", "/api/rider/missions/${home.missionId}", home.rider)
        assertEquals(site.id, mission["relayDestination"]["id"].asText())
        assertEquals("RELAY_POINT", mission["destinationType"].asText())
        assertTrue(mission["homeDestination"].isNull)
        assertEquals(200, call("POST", "/api/rider/missions/${home.missionId}/relay-deposit", home.rider,
            """{"relayPointId":"${site.id}","lockerCode":"SLOT-1","proofMetadata":"Relay operator accepted custody","idempotencyKey":"rerouted-deposit-${home.missionId}"}""").statusCode())
        val tracking = read("GET", "/api/orders/${home.orderId}", home.customer.token)["deliveries"].single()
        assertEquals("DEPOSITED_AT_RELAY", tracking["status"].asText())
        assertEquals(site.id, tracking["relayDestination"]["id"].asText())
        assertNotNull(deliveryMissions.findById(home.missionId).orElseThrow().relayReroutedAt)
        val freeSite = relayFixture()
        for (category in listOf("food", "perishable", "sensitive", "grocery", "pharmacy")) {
            val excluded = paidPickedUp(category = category)
            assertEquals(200, call("POST", "/api/rider/missions/${excluded.missionId}/problem", excluded.rider,
                """{"reason":"Home handoff failed","idempotencyKey":"problem-${excluded.missionId}"}""").statusCode())
            assertEquals(400, call("POST", "/api/delivery/missions/${excluded.missionId}/relay-reroute", excluded.admin,
                """{"relayPointId":"${freeSite.id}","customerApproved":true,"reason":"Attempt excluded category","idempotencyKey":"reroute-${excluded.missionId}"}""").statusCode())
        }
    }

    private data class RelayFixture(val id: String, val slotId: String, val operator: String)
    private fun relayFixture(): RelayFixture {
        val user = users.save(User(email = "operator-${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL, roles = mutableSetOf(RoleCode.RELAY_PARTNER)))
        val id = "relay-${UUID.randomUUID()}"
        relayPoints.save(dev.orestegabo.sequo_api.domain.party.RelayPointRecord(id = id, operatorUserId = user.id, name = "Fixture relay", city = "Lomé", neighborhood = "Fixture area", latitude = 0.0, longitude = 0.02))
        val time = java.sql.Timestamp.from(Instant.now())
        jdbc.update("insert into relay_locker_grids (id,relay_point_id,grid_code,label,created_at,updated_at,version) values (?,?,?,?,?,?,0)", id, id, id, "Staging logical grid", time, time)
        val slotId = "slot-$id"
        relayLockers.save(dev.orestegabo.sequo_api.domain.hub.RelayLockerRecord(id = slotId, relayPointId = id, relayLockerGridId = id, lockerCode = "SLOT-1"))
        return RelayFixture(id, slotId, jwt.generateAccessToken(UserSession(requireNotNull(user.id), user.email, AuthProvider.EMAIL, setOf(RoleCode.RELAY_PARTNER))))
    }

    private data class RelayJourney(val customer: Fixture, val orderId: String, val missionId: String, val rider: String, val admin: String)
    private fun paidPickedUp(relayId: String? = null, category: String? = null): RelayJourney {
        val fixture = fixture()
        if (category != null) products.findById(fixture.productId).orElseThrow().also {
            it.category = category
            if (category in listOf("food", "perishable")) { it.cameraVerified = true; it.capturedAtLabel = Instant.now().toString() }
            products.save(it)
        }
        fun account(role: RoleCode): Pair<String, String> {
            val user = users.save(User(email = "${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL, roles = mutableSetOf(role)))
            return requireNotNull(user.id) to jwt.generateAccessToken(UserSession(requireNotNull(user.id), user.email, AuthProvider.EMAIL, setOf(role)))
        }
        val merchantId = requireNotNull(products.findById(fixture.productId).orElseThrow().merchantId)
        val owner = account(RoleCode.MERCHANT_OWNER)
        memberships.save(MerchantMembership(userId = owner.first, merchantId = merchantId, role = RoleCode.MERCHANT_OWNER))
        val merchant = jwt.generateAccessToken(UserSession(owner.first, null, AuthProvider.EMAIL, setOf(RoleCode.MERCHANT_OWNER), setOf(merchantId)))
        val admin = account(RoleCode.ADMIN).second
        val rider = account(RoleCode.COURIER)
        couriers.save(CourierRecord(id = "courier-${rider.first}", userId = rider.first, workforceType = "FREELANCER"))
        val relayQuery = relayId?.let { "&relayPointId=$it" }.orEmpty()
        val quote = read("GET", "/api/cart/quote?addressId=${fixture.addressId}$relayQuery", fixture.token)
        val relayField = relayId?.let { ",\"relayPointId\":\"$it\"" }.orEmpty()
        val submit = call("POST", "/api/orders/checkouts", fixture.token,
            """{"addressId":"${fixture.addressId}","quoteRevision":"${quote["revision"].asText()}","paymentProvider":"yas_togo"$relayField}""")
        assertEquals(202, submit.statusCode(), submit.body())
        val checkout = json.readTree(submit.body()); val id = checkout["checkoutId"].asText()
        assertEquals(202, webhook("""{"eventId":"relay-journey-$id","checkoutId":"$id","paymentReference":"$id","amountCfa":${checkout["totalCfa"].asInt()},"currency":"XOF","status":"VALIDATED","occurredAt":"${Instant.now()}"}""").statusCode())
        val sub = read("GET", "/api/merchant/sub-orders?merchantId=$merchantId", merchant).single()
        val subId = sub["id"].asText(); val orderId = sub["orderId"].asText()
        for (action in listOf("accept", "start-preparation", "mark-packed")) {
            val packed = if (action == "mark-packed") ",\"packageCount\":2" else ""
            val result = call("POST", "/api/merchant/sub-orders/$subId/$action", merchant, """{"merchantId":"$merchantId"$packed}""")
            assertEquals(200, result.statusCode(), result.body())
        }
        val dispatched = readPost("/api/delivery/missions/dispatch-ready", admin)
        val missionId = dispatched["createdMissions"].single { it["orderId"].asText() == orderId }["id"].asText()
        assertEquals(200, call("POST", "/api/delivery/missions/$missionId/offer", admin).statusCode())
        assertEquals(200, call("POST", "/api/rider/missions/$missionId/accept", rider.second).statusCode())
        assertEquals(200, call("POST", "/api/rider/missions/$missionId/pickup/complete", rider.second,
            """{"proofMetadata":"Merchant custody proof","idempotencyKey":"pickup-$missionId"}""").statusCode())
        return RelayJourney(fixture, orderId, missionId, rider.second, admin)
    }
    private fun readPost(path: String, token: String): JsonNode {
        val result = call("POST", path, token); assertEquals(200, result.statusCode(), result.body()); return json.readTree(result.body())
    }

    private fun webhook(payload: String, signatureOverride: String? = null): HttpResponse<String> {
        val timestamp = Instant.now().epochSecond
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("isolated-fixture-webhook-secret".toByteArray(), "HmacSHA256"))
        val signature = signatureOverride ?: mac.doFinal("$timestamp.$payload".toByteArray()).toHexString()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/payments/webhooks/yas_togo"))
            .header("Content-Type", "application/json")
            .header("X-Sequo-Webhook-Timestamp", timestamp.toString())
            .header("X-Sequo-Webhook-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private data class Fixture(val userId: String, val token: String, val productId: String, val addressId: String)

    private fun fixture(): Fixture {
        val suffix = UUID.randomUUID().toString()
        val user = users.save(User(email = "$suffix@sequo.test", provider = AuthProvider.EMAIL, status = UserStatus.ACTIVE))
        val userId = requireNotNull(user.id)
        val merchantId = "merchant-$suffix"
        merchants.save(MerchantRecord(id = merchantId, name = "Fixture shop"))
        val product = products.save(ProductRecord(
            id = "product-$suffix", merchantId = merchantId, name = "Sealed item",
            kind = CatalogProductKind.GenericSealedItem, basePriceCfa = 2000,
            imageUrl = "https://fixture.invalid/approved.jpg",
        ))
        val address = addresses.save(CustomerAddressRecord(
            "address-$suffix", userId, CustomerAddressKind.HOME, "Home", "Original recipient",
            "+22890000000", "Lomé", latitude = 0.0, longitude = 0.01,
        ))
        val token = jwt.generateAccessToken(UserSession(userId, user.email, AuthProvider.EMAIL))
        val put = call("PUT", "/api/cart/items", token, """{"productId":"${product.id}","quantity":2}""")
        assertEquals(200, put.statusCode(), put.body())
        return Fixture(userId, token, product.id, address.id)
    }

    private fun quote(fixture: Fixture) = read("GET", "/api/cart/quote?addressId=${fixture.addressId}", fixture.token)
    private fun submitBody(fixture: Fixture, quote: JsonNode) =
        """{"addressId":"${fixture.addressId}","quoteRevision":"${quote["revision"].asText()}","paymentProvider":"yas_togo","totalCfa":1,"paymentStatus":"VALIDATED"}"""

    private fun read(method: String, path: String, token: String): JsonNode {
        val response = call(method, path, token)
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }

    private fun call(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.header("Content-Type", "application/json") }
        request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
