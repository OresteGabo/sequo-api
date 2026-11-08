package dev.orestegabo.sequo_api.domain.subscription

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.delivery.DeliveryReadinessDispatchService
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentService
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentServiceResult
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRepository
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutStatus
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.platform.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.bean.override.mockito.MockitoBean
import dev.orestegabo.sequo_api.domain.platform.CustomerSubscriptionStatus as StoredSubscriptionStatus

@org.springframework.context.annotation.Import(dev.orestegabo.sequo_api.domain.payment.PaymentWebhookFixtureConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:delivery_benefits_http;DB_CLOSE_DELAY=-1",
        "sequo.cart.straight-line-dispatch-origin=0,0",
        "sequo.wallets.yas-togo.webhook-secret=isolated-fixture-webhook-secret",
        "sequo.wallets.yas-togo.api-base-url=https://fixture.invalid",
        "sequo.wallets.yas-togo.api-key=isolated-fixture-key",
        "sequo.wallets.yas-togo.merchant-account-id=isolated-fixture-account",
        // LOCAL_FIXTURE_ONLY: exercise an explicitly configured award; these are not production defaults.
        "sequo.referrals.credit-cfa=500",
        "sequo.referrals.approval-reference=LOCAL_FIXTURE_ONLY",
        "sequo.referrals.expiry-days=30",
    ],
)
class DeliveryBenefitsCheckoutHttpTest @Autowired constructor(
    private val users: UserRepository,
    private val jwt: JwtService,
    private val memberships: MerchantMembershipRepository,
    private val merchants: CommerceMerchantRepository,
    private val products: CommerceProductRepository,
    private val addresses: CustomerAddressRepository,
    private val plans: SubscriptionPlanRepository,
    private val subscriptions: CustomerSubscriptionRepository,
    private val credits: ReferralDeliveryCreditRepository,
    private val applications: DeliveryBenefitApplicationRepository,
    private val pending: PendingPaymentCheckoutRepository,
    private val orders: CustomerOrderRecordRepository,
    private val merchantFulfillment: MerchantFulfillmentService,
    private val dispatch: DeliveryReadinessDispatchService,
    private val missions: DeliveryMissionRepository,
    private val rateLimiter: InMemoryRateLimiter,
) {
    companion object {
        @JvmStatic @org.springframework.test.context.DynamicPropertySource
        fun isolatedPostgres(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_BENEFITS_JOURNEY_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) {
                "Use a disposable local delivery benefits fixture database."
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

    @MockitoBean(name = "benefitsClock") private lateinit var clock: Clock
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var now: Instant

    @org.junit.jupiter.api.BeforeEach
    fun resetRequestBudgetAndTime() {
        rateLimiter.resetForTests()
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        Mockito.`when`(clock.instant()).thenReturn(now)
        Mockito.`when`(clock.zone).thenReturn(ZoneOffset.UTC)
    }

    @Test
    fun verifiedSubscriptionAndSeparatelyVerifiedApprovedLoyaltyDetermineTheQuote() {
        val ordinary = fixture()
        val subscribed = fixture()
        val plan = approvedPlan(loyaltyPercent = 20)
        var subscription = verifiedSubscription(subscribed, plan, oldTenure = true)
        assertQuote(quote(ordinary), 0, 0, 0, 400)
        assertQuote(quote(subscribed), 80, 0, 0, 320)
        subscription.cancelAtPeriodEnd = true; subscription = subscriptions.save(subscription)
        assertQuote(quote(subscribed), 80, 0, 0, 320)
        assertFalse(read("/api/subscriptions/benefits", subscribed.actor.token)["loyaltyEnabled"].asBoolean())
        subscription.loyaltyTenureVerified = true; subscriptions.save(subscription)
        assertQuote(quote(subscribed), 80, 64, 0, 256)
        assertTrue(read("/api/subscriptions/benefits", subscribed.actor.token)["loyaltyEnabled"].asBoolean())
        plan.approvalReference = null; plans.save(plan)
        assertQuote(quote(subscribed), 0, 0, 0, 400)
        assertFalse(read("/api/subscriptions/benefits", subscribed.actor.token)["subscriptionActive"].asBoolean())
    }

    @Test
    fun pendingFailedAndInvalidPaymentEvidenceCannotConsumeDeliveryCredit() {
        val fixture = fixture()
        verifiedSubscription(fixture, approvedPlan())
        val credit = approvedCredit(fixture.actor)
        val reviewed = quote(fixture)
        assertQuote(reviewed, 80, 0, 320, 0)
        val first = submit(fixture, reviewed)
        assertEquals(500, credits.findById(credit.id).orElseThrow().remainingCfa)
        assertEquals(DeliveryBenefitApplicationStatus.RESERVED, applications.findById(first).orElseThrow().status)
        assertEquals(180, read("/api/subscriptions/benefits", fixture.actor.token)["availableReferralCreditCfa"].asInt())
        assertEquals(202, webhook(first, "PENDING").statusCode())
        for (response in listOf(webhook(first, "VALIDATED", amount = 4001), webhook(first, "VALIDATED", currency = "USD"),
            webhook(first, "VALIDATED", reference = "wrong-reference"))) {
            assertTrue(response.statusCode() in setOf(202, 400), response.body())
        }
        assertEquals(PendingPaymentCheckoutStatus.AWAITING_WEBHOOK, pending.findById(first).orElseThrow().status)
        assertNull(orders.findByCheckoutId(first))
        assertEquals(500, credits.findById(credit.id).orElseThrow().remainingCfa)
        assertEquals(202, webhook(first, "FAILED").statusCode())
        assertEquals(DeliveryBenefitApplicationStatus.RELEASED, applications.findById(first).orElseThrow().status)
        assertEquals(500, credits.findById(credit.id).orElseThrow().remainingCfa)
        assertEquals(500, read("/api/subscriptions/benefits", fixture.actor.token)["availableReferralCreditCfa"].asInt())

        putCart(fixture)
        val second = submit(fixture, quote(fixture))
        val paidEvents = (1..2).map { CompletableFuture.supplyAsync { webhook(second, "VALIDATED") } }.map { it.join() }
        paidEvents.forEach { assertEquals(202, it.statusCode(), it.body()) }
        val applied = applications.findById(second).orElseThrow()
        assertEquals(DeliveryBenefitApplicationStatus.APPLIED, applied.status)
        assertEquals(320, applied.creditAppliedCfa)
        assertEquals(80, applied.subscriptionDiscountCfa)
        assertEquals(180, credits.findById(credit.id).orElseThrow().remainingCfa)
        assertEquals(ReferralCreditStatus.AVAILABLE, credits.findById(credit.id).orElseThrow().status)
        assertEquals(202, webhook(second, "VALIDATED").statusCode())
        assertEquals(180, credits.findById(credit.id).orElseThrow().remainingCfa)
        assertEquals(1, orders.findByCustomerIdOrderByCreatedAtDesc(fixture.actor.id).size)
        val order = requireNotNull(orders.findByCheckoutId(second))
        assertEquals(4000, order.itemSubtotalCfa)
        assertEquals(0, order.deliveryFeeCfa)
        assertEquals(4000, order.totalCfa)
        val part = merchantFulfillment.listForOrder(order.id).single()
        assertTrue(merchantFulfillment.accept(part.id, fixture.merchantId) is MerchantFulfillmentServiceResult.Success)
        assertTrue(merchantFulfillment.startPreparation(part.id, fixture.merchantId) is MerchantFulfillmentServiceResult.Success)
        assertTrue(merchantFulfillment.markPacked(part.id, fixture.merchantId, 1) is MerchantFulfillmentServiceResult.Success)
        val mission = dispatch.dispatchReadySubOrders(200).createdMissions.single { it.orderId == order.id }
        assertEquals(0, mission.customerDeliveryFeeCfa)
        assertEquals(400, mission.courierFeeCfa)
        assertEquals(400, mission.shortfallCfa)
        assertEquals(400, missions.findById(mission.id).orElseThrow().freelancerFeeEstimateCfa)
        val frozen = json.readTree(pending.findById(second).orElseThrow().pricingPayload)
        assertEquals(320, frozen["delivery"]["referralCreditAppliedCfa"].asInt())
        assertEquals(80, frozen["delivery"]["subscriptionDiscountCfa"].asInt())
        assertEquals(2, applications.findByCustomerId(fixture.actor.id).size)
    }

    @Test
    fun customerRequestsCannotSelfActivateBenefitsOrSpendAnotherCustomersCredit() {
        val fixture = fixture()
        val referred = actor()
        val plan = approvedPlan()
        val payload = """{"planId":"${plan.id}","status":"ACTIVE","activationReference":"forged","loyaltyTenureVerified":true,"currentPeriodEndsAt":"2099-01-01T00:00:00Z"}"""
        val starts = (1..2).map { CompletableFuture.supplyAsync { call("POST", "/api/subscriptions", fixture.actor.token, payload) } }.map { it.join() }
        starts.forEach { assertEquals(200, it.statusCode(), it.body()) }
        assertEquals(1, starts.map { json.readTree(it.body())["id"].asText() }.toSet().size)
        val subscription = subscriptions.findTopByUserIdOrderByCreatedAtDesc(fixture.actor.id)!!
        assertEquals(StoredSubscriptionStatus.PAUSED, subscription.status)
        assertNull(subscription.activationReference)
        assertNull(subscription.currentPeriodStartsAt)
        assertFalse(subscription.loyaltyTenureVerified)
        val referralPayload = """{"referredUserId":"${referred.id}","creditCfa":1000000,"remainingCfa":1000000,"status":"AVAILABLE","approvalReference":"forged"}"""
        val referrals = (1..2).map { CompletableFuture.supplyAsync { call("POST", "/api/referrals", fixture.actor.token, referralPayload) } }.map { it.join() }
        referrals.forEach { assertEquals(200, it.statusCode(), it.body()) }
        assertEquals(1, referrals.map { json.readTree(it.body())["id"].asText() }.toSet().size)
        val requestedCredit = credits.findFirstByReferrerUserIdAndReferredUserIdOrderByCreatedAtAsc(fixture.actor.id, referred.id)!!
        assertEquals(500, requestedCredit.creditCfa)
        assertEquals(ReferralCreditStatus.RESERVED, requestedCredit.status)
        assertNull(requestedCredit.approvalReference)
        val otherParticipant = read("/api/referrals/credits", referred.token).single { it["id"].asText() == requestedCredit.id }
        assertTrue(otherParticipant.path("remainingCfa").isNull || otherParticipant.path("remainingCfa").isMissingNode)
        assertEquals(0, read("/api/subscriptions/benefits", referred.token)["availableReferralCreditCfa"].asInt())
        val borrowed = approvedCredit(referred)
        assertQuote(quote(fixture), 0, 0, 0, 400)
        assertEquals(0, read("/api/subscriptions/benefits", fixture.actor.token)["availableReferralCreditCfa"].asInt())
        val id = submit(fixture, quote(fixture), mapOf("referralCreditId" to borrowed.id, "subscriptionDiscountPercent" to 100,
            "referralCreditCfa" to 1000000, "subscriberPriority" to true, "totalCfa" to 1))
        assertEquals(4400, pending.findById(id).orElseThrow().amountCfa)
        assertEquals(0, applications.findById(id).orElseThrow().creditAppliedCfa)
        assertNull(applications.findById(id).orElseThrow().creditId)
        assertEquals(500, credits.findById(borrowed.id).orElseThrow().remainingCfa)
        val unapproved = plans.save(SubscriptionPlanRecord("unapproved-${UUID.randomUUID()}", "Unapproved fixture", 1000, 90))
        assertEquals(400, call("POST", "/api/subscriptions", fixture.actor.token, """{"planId":"${unapproved.id}"}""").statusCode())
        assertTrue(read("/api/subscriptions/plans", fixture.actor.token).none { it["id"].asText() == unapproved.id })
        val vendor = actor(RoleCode.MERCHANT_OWNER, fixture.merchantId)
        assertEquals(403, call("POST", "/api/subscriptions", vendor.token, payload).statusCode())
        assertEquals(403, call("POST", "/api/referrals", vendor.token, referralPayload).statusCode())
        assertEquals(403, call("GET", "/api/subscriptions/benefits", vendor.token).statusCode())
        assertEquals(400, call("POST", "/api/referrals", fixture.actor.token, """{"referredUserId":"missing-account"}""").statusCode())
    }

    @Test
    fun staleBenefitsRequireQuoteReviewAndPendingReservationsRespectTheMonthlyCap() {
        val stale = fixture()
        var subscription = verifiedSubscription(stale, approvedPlan())
        val reviewed = quote(stale)
        subscription.currentPeriodEndsAt = now.minusSeconds(1); subscription = subscriptions.save(subscription)
        assertEquals(409, call("POST", "/api/orders/checkouts", stale.actor.token, checkoutBody(stale, reviewed)).statusCode())
        assertTrue(pending.findByCustomerIdOrderByCreatedAtDesc(stale.actor.id).isEmpty())
        assertTrue(applications.findByCustomerId(stale.actor.id).isEmpty())
        subscription.currentPeriodEndsAt = now.plusSeconds(30L * 86400); subscriptions.save(subscription)
        val credit = approvedCredit(stale.actor)
        val creditedQuote = quote(stale)
        credit.approvalReference = null; credits.save(credit)
        assertEquals(409, call("POST", "/api/orders/checkouts", stale.actor.token, checkoutBody(stale, creditedQuote)).statusCode())
        assertEquals(500, credits.findById(credit.id).orElseThrow().remainingCfa)

        val capped = fixture()
        verifiedSubscription(capped, approvedPlan(loyaltyPercent = 20, cap = 100), oldTenure = true).also {
            it.loyaltyTenureVerified = true; subscriptions.save(it)
        }
        val cappedQuote = quote(capped)
        assertQuote(cappedQuote, 80, 20, 0, 300)
        val first = submit(capped, cappedQuote)
        assertEquals(80, applications.findById(first).orElseThrow().subscriptionDiscountCfa)
        assertEquals(20, applications.findById(first).orElseThrow().loyaltyDiscountCfa)
        putCart(capped)
        val heldCapQuote = quote(capped)
        assertQuote(heldCapQuote, 0, 0, 0, 400)
        val second = submit(capped, heldCapQuote)
        val paid = listOf(first, second).map { id -> CompletableFuture.supplyAsync { webhook(id, "VALIDATED") } }.map { it.join() }
        paid.forEach { assertEquals(202, it.statusCode(), it.body()) }
        assertEquals(100, applications.findByCustomerId(capped.actor.id).sumOf { it.subscriptionDiscountCfa + it.loyaltyDiscountCfa })
        assertTrue(applications.findByCustomerId(capped.actor.id).all { it.status == DeliveryBenefitApplicationStatus.APPLIED })
        putCart(capped)
        assertQuote(quote(capped), 0, 0, 0, 400)
    }

    private data class Actor(val id: String, val token: String)
    private data class Fixture(val actor: Actor, val merchantId: String, val productId: String, val addressId: String)
    private fun actor(role: RoleCode = RoleCode.CUSTOMER, merchantId: String? = null): Actor {
        val user = users.save(User(email = "benefit-${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL, roles = mutableSetOf(role)))
        val id = requireNotNull(user.id)
        merchantId?.let { memberships.save(MerchantMembership(userId = id, merchantId = it, role = role)) }
        return Actor(id, jwt.generateAccessToken(UserSession(id, user.email, user.provider, setOf(role), setOfNotNull(merchantId))))
    }
    private fun fixture(): Fixture {
        val actor = actor(); val merchant = "benefit-merchant-${UUID.randomUUID()}"
        merchants.save(MerchantRecord(id = merchant, name = "Fixture benefit merchant"))
        val product = products.save(ProductRecord("benefit-product-${UUID.randomUUID()}", merchantId = merchant, name = "Fixture sealed product",
            kind = CatalogProductKind.GenericSealedItem, basePriceCfa = 2000, imageUrl = "https://fixture.invalid/image.jpg"))
        val address = addresses.save(CustomerAddressRecord("benefit-address-${UUID.randomUUID()}", actor.id, CustomerAddressKind.HOME,
            "Home", "Fixture recipient", "+22890000000", "Lomé", latitude = 0.0, longitude = 0.01))
        return Fixture(actor, merchant, product.id, address.id).also(::putCart)
    }
    private fun approvedPlan(loyaltyPercent: Int = 0, cap: Int? = null) = plans.save(SubscriptionPlanRecord(
        "benefit-plan-${UUID.randomUUID()}", "LOCAL_FIXTURE_ONLY", 1000, 20, cap,
        loyaltyMonthsThreshold = 12, loyaltyDiscountPercent = loyaltyPercent, approvalReference = "LOCAL_FIXTURE_ONLY"))
    private fun verifiedSubscription(fixture: Fixture, plan: SubscriptionPlanRecord, oldTenure: Boolean = false) = subscriptions.save(CustomerSubscriptionRecord(
        "benefit-sub-${UUID.randomUUID()}", fixture.actor.id, plan.id, status = StoredSubscriptionStatus.ACTIVE,
        startedAt = now.minusSeconds((if (oldTenure) 800L else 7L) * 86400), currentPeriodEndsAt = now.plusSeconds(23L * 86400),
        currentPeriodStartsAt = now.minusSeconds(7L * 86400), activationReference = "LOCAL_FIXTURE_ONLY"))
    private fun approvedCredit(owner: Actor): ReferralDeliveryCreditRecord {
        val referred = actor()
        return credits.save(ReferralDeliveryCreditRecord("benefit-credit-${UUID.randomUUID()}", owner.id, referred.id, 500,
            ReferralCreditStatus.AVAILABLE, createdAt = now.minusSeconds(1), expiresAt = now.plusSeconds(30L * 86400), approvalReference = "LOCAL_FIXTURE_ONLY"))
    }
    private fun putCart(fixture: Fixture) {
        assertEquals(200, call("PUT", "/api/cart/items", fixture.actor.token, """{"productId":"${fixture.productId}","quantity":2}""").statusCode())
    }
    private fun quote(fixture: Fixture) = read("/api/cart/quote?addressId=${fixture.addressId}", fixture.actor.token)
    private fun assertQuote(quote: JsonNode, subscription: Int, loyalty: Int, credit: Int, fee: Int) {
        assertEquals(400, quote["deliveryBaseFeeCfa"].asInt())
        assertEquals(subscription, quote["subscriptionDiscountCfa"].asInt())
        assertEquals(loyalty, quote["loyaltyDiscountCfa"].asInt())
        assertEquals(credit, quote["referralCreditAppliedCfa"].asInt())
        assertEquals(fee, quote["deliveryFeeCfa"].asInt())
        assertEquals(4000, quote["productTotalCfa"].asInt())
        assertEquals(4000 + fee, quote["totalCfa"].asInt())
    }
    private fun checkoutBody(fixture: Fixture, quote: JsonNode, extras: Map<String, Any?> = emptyMap()) = json.writeValueAsString(mapOf(
        "addressId" to fixture.addressId, "quoteRevision" to quote["revision"].asText(), "paymentProvider" to "yas_togo") + extras)
    private fun submit(fixture: Fixture, quote: JsonNode, extras: Map<String, Any?> = emptyMap()): String {
        val response = call("POST", "/api/orders/checkouts", fixture.actor.token, checkoutBody(fixture, quote, extras))
        assertEquals(202, response.statusCode(), response.body())
        return json.readTree(response.body())["checkoutId"].asText()
    }
    private fun webhook(checkoutId: String, status: String, amount: Int? = null, currency: String = "XOF", reference: String = checkoutId): HttpResponse<String> {
        val payload = json.writeValueAsString(mapOf("eventId" to "benefit-event-${UUID.randomUUID()}", "checkoutId" to checkoutId,
            "paymentReference" to reference, "amountCfa" to (amount ?: pending.findById(checkoutId).orElseThrow().amountCfa),
            "currency" to currency, "status" to status, "occurredAt" to Instant.now().toString()))
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
    private fun call(method: String, path: String, token: String, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("Authorization", "Bearer $token")
        body?.let { request.header("Content-Type", "application/json") }
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
