package dev.orestegabo.sequo_api.domain.commerce

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.SequoPaymentProviders
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressKind
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressRecord
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:bargaining_checkout_http;DB_CLOSE_DELAY=-1",
        "sequo.cart.straight-line-dispatch-origin=0,0",
        "sequo.wallets.yas-togo.webhook-secret=isolated-fixture-webhook-secret",
        "sequo.wallets.yas-togo.api-base-url=https://fixture.invalid",
        "sequo.wallets.yas-togo.api-key=isolated-fixture-key",
        "sequo.wallets.yas-togo.merchant-account-id=isolated-fixture-account",
    ],
)
class BargainingCheckoutHttpTest @Autowired constructor(
    private val users: UserRepository,
    private val jwt: JwtService,
    private val memberships: MerchantMembershipRepository,
    private val products: CommerceProductRepository,
    private val merchants: CommerceMerchantRepository,
    private val addresses: CustomerAddressRepository,
    private val pending: PendingPaymentCheckoutRepository,
    private val bargaining: PersistedBargainingService,
    private val legacyOrders: OrderController,
    private val jdbc: JdbcTemplate,
    private val rateLimiter: InMemoryRateLimiter,
) {
    companion object {
        @JvmStatic @org.springframework.test.context.DynamicPropertySource
        fun isolatedPostgres(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_BARGAINING_JOURNEY_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) {
                "Use a disposable local bargaining fixture database."
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
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    @org.junit.jupiter.api.BeforeEach
    fun resetRequestBudget() = rateLimiter.resetForTests()

    @Test
    fun separateSessionsCounterAcceptAndCheckoutWithOwnedUnexpiredPrice() {
        val fixture = fixture()
        val stranger = fixture()
        for (token in listOf(fixture.customer.token, fixture.vendor.token, stranger.customer.token, stranger.vendor.token)) {
            assertEquals(200, call("GET", "/api/auth/me", token).statusCode())
        }
        denied(call("GET", "/api/bargaining/threads"))
        denied(call("POST", "/api/bargaining/threads", fixture.vendor.token, """{"productId":"${fixture.productId}","amountCfa":1400}"""))
        denied(call("GET", "/api/bargaining/threads?merchantId=${fixture.merchantId}", fixture.customer.token))
        val initialBody = """{"productId":"${fixture.productId}","amountCfa":1400,"idempotencyKey":"open-${UUID.randomUUID()}"}"""
        val opened = (1..2).map { CompletableFuture.supplyAsync { call("POST", "/api/bargaining/threads", fixture.customer.token, initialBody) } }.map { it.join() }
        opened.forEach { assertEquals(200, it.statusCode(), it.body()) }
        val thread = json.readTree(opened.first().body())
        val id = thread["id"].asText()
        assertEquals(id, json.readTree(opened.last().body())["id"].asText())
        assertEquals(1, thread["offers"].size())
        assertEquals(1, thread["customerAttemptsUsed"].asInt())
        assertEquals(3, thread["maxCustomerAttempts"].asInt())
        assertEquals(3, thread["maxMerchantAttempts"].asInt())
        assertEquals("MERCHANT", thread["nextActor"].asText())
        assertEquals(fixture.productId, read("/api/bargaining/threads?merchantId=${fixture.merchantId}", fixture.vendor.token).single()["productId"].asText())
        denied(call("GET", "/api/bargaining/threads?merchantId=${fixture.merchantId}", stranger.vendor.token))
        denied(call("GET", "/api/bargaining/threads/$id", stranger.customer.token))
        denied(call("GET", "/api/bargaining/threads/$id", stranger.vendor.token))

        val firstOffer = monetaryOffer(thread)
        denied(action(id, stranger.vendor.token, "COUNTER_OFFER", firstOffer, 1600))
        denied(action(id, fixture.customer.token, "COUNTER_OFFER", firstOffer, 1600))
        denied(action(id, fixture.vendor.token, "COUNTER_OFFER", "stale-offer-id", 1600))
        val counterBody = offerBody("COUNTER_OFFER", firstOffer, 1600, "counter-${UUID.randomUUID()}")
        val counterCalls = (1..2).map { CompletableFuture.supplyAsync { call("POST", "/api/bargaining/threads/$id/offers", fixture.vendor.token, counterBody) } }.map { it.join() }
        counterCalls.forEach { assertEquals(200, it.statusCode(), it.body()) }
        val counter = json.readTree(counterCalls.first().body())
        assertEquals(2, counter["offers"].size())
        assertEquals(1, counter["merchantAttemptsUsed"].asInt())
        assertEquals("CUSTOMER", counter["nextActor"].asText())
        denied(call("POST", "/api/bargaining/threads/$id/offers", fixture.vendor.token, counterBody.replace("1600", "1700")))
        denied(action(id, fixture.customer.token, "ACCEPT", firstOffer))
        val acceptBody = offerBody("ACCEPT", monetaryOffer(counter), null, "accept-${UUID.randomUUID()}")
        val accepted = readPost("/api/bargaining/threads/$id/offers", fixture.customer.token, acceptBody)
        assertEquals("ACCEPTED", accepted["status"].asText())
        assertEquals(1600, accepted["acceptedPriceCfa"].asInt())
        assertEquals(Duration.ofHours(24), Duration.between(Instant.parse(accepted["acceptedAt"].asText()), Instant.parse(accepted["expiresAt"].asText())))
        val product = products.findById(fixture.productId).orElseThrow()
        val expiry = bargaining.acceptedPrice(fixture.customer.id, product, id).expiresAt
        assertEquals(1600, bargaining.acceptedPrice(fixture.customer.id, product, id, expiry.minusNanos(1)).unitPriceCfa)
        assertFailsWith<IllegalArgumentException> { bargaining.acceptedPrice(fixture.customer.id, product, id, expiry) }
        assertFailsWith<IllegalArgumentException> { bargaining.acceptedPrice(fixture.customer.id, product, id, expiry.plusNanos(1)) }
        assertLegacyCheckoutClaimsRejected(fixture, id)
        for (reloaded in listOf(readPost("/api/bargaining/threads/$id/offers", fixture.customer.token, acceptBody),
            read("/api/bargaining/threads/$id", fixture.vendor.token))) {
            assertEquals(id, reloaded["id"].asText())
            assertEquals("ACCEPTED", reloaded["status"].asText())
            assertEquals(1600, reloaded["acceptedPriceCfa"].asInt())
            assertEquals(accepted["offers"].map { it["id"].asText() }, reloaded["offers"].map { it["id"].asText() })
            assertTrue(Duration.between(Instant.parse(accepted["expiresAt"].asText()), Instant.parse(reloaded["expiresAt"].asText())).abs() < Duration.ofMillis(1))
        }
        denied(call("PUT", "/api/cart/items", stranger.customer.token,
            """{"productId":"${fixture.productId}","quantity":2,"bargainingThreadId":"$id"}"""))

        val catalogPut = call("PUT", "/api/cart/items", fixture.customer.token, """{"productId":"${fixture.productId}","quantity":1}""")
        assertEquals(200, catalogPut.statusCode(), catalogPut.body())
        val catalogLineId = json.readTree(catalogPut.body())["items"].single()["id"].asText()
        putCart(fixture, id)
        val mixed = read("/api/cart", fixture.customer.token)["items"]
        assertEquals(2, mixed.size())
        assertEquals(2, mixed.map { it["id"].asText() }.toSet().size)
        assertEquals(1, mixed.single { it["id"].asText() == catalogLineId }["quantity"].asInt())
        assertEquals(2, mixed.single { it["bargainingThreadId"].asText() == id }["quantity"].asInt())
        val changedQuantity = call("PUT", "/api/cart/items", fixture.customer.token,
            """{"productId":"${fixture.productId}","quantity":3,"bargainingThreadId":"$id"}""")
        assertEquals(200, changedQuantity.statusCode(), changedQuantity.body())
        val changedItems = json.readTree(changedQuantity.body())["items"]
        assertEquals(1, changedItems.single { it["id"].asText() == catalogLineId }["quantity"].asInt())
        assertEquals(3, changedItems.single { it["bargainingThreadId"].asText() == id }["quantity"].asInt())
        putCart(fixture, id)
        assertEquals(200, call("DELETE", "/api/cart/items/$catalogLineId", fixture.customer.token).statusCode())
        val validQuote = quote(fixture)
        assertEquals(3200, validQuote["productTotalCfa"].asInt())
        assertEquals(3600, validQuote["totalCfa"].asInt())
        val checkoutBody = checkoutBody(fixture, validQuote)
        val submitted = call("POST", "/api/orders/checkouts", fixture.customer.token, checkoutBody)
        assertEquals(202, submitted.statusCode(), submitted.body())
        val checkoutId = json.readTree(submitted.body())["checkoutId"].asText()
        val saved = pending.findById(checkoutId).orElseThrow()
        assertEquals(3600, saved.amountCfa)
        val storedLine = json.readTree(saved.requestPayload)["lines"].single()
        assertEquals(1600, storedLine["negotiatedUnitPriceCfa"].asInt())
        assertEquals(fixture.productId, storedLine["productId"].asText())
        assertEquals(2, storedLine["quantity"].asInt())

        putCart(fixture, id)
        val productEndpoint = "/api/merchant/catalog/products/${fixture.productId}"
        assertEquals(200, call("POST", "$productEndpoint/deactivate?merchantId=${fixture.merchantId}", fixture.vendor.token).statusCode())
        assertFalse(read("/api/cart", fixture.customer.token)["items"].single()["available"].asBoolean())
        assertEquals(200, call("POST", "$productEndpoint/activate?merchantId=${fixture.merchantId}", fixture.vendor.token).statusCode())
        val beforeExpiry = quote(fixture)
        jdbc.update("update bargaining_threads set expires_at = ? where id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id)
        val expiredCart = read("/api/cart", fixture.customer.token)
        assertFalse(expiredCart["items"].single()["available"].asBoolean())
        assertEquals(400, call("GET", "/api/cart/quote?addressId=${fixture.addressId}", fixture.customer.token).statusCode())
        assertEquals(409, call("POST", "/api/orders/checkouts", fixture.customer.token, checkoutBody(fixture, beforeExpiry)).statusCode())
        assertEquals(1, pending.findByCustomerIdOrderByCreatedAtDesc(fixture.customer.id).size)
        val retry = call("POST", "/api/orders/checkouts", fixture.customer.token, checkoutBody)
        assertEquals(202, retry.statusCode(), retry.body())
        assertEquals(checkoutId, json.readTree(retry.body())["checkoutId"].asText())
        val expiredLineId = expiredCart["items"].single()["id"].asText()
        assertEquals(200, call("DELETE", "/api/cart/items/$expiredLineId", fixture.customer.token).statusCode())
        putCart(fixture, null)
        val reviewedCatalog = quote(fixture)
        assertEquals(4000, reviewedCatalog["productTotalCfa"].asInt())
        assertEquals(4400, reviewedCatalog["totalCfa"].asInt())
        assertEquals(202, call("POST", "/api/orders/checkouts", fixture.customer.token, checkoutBody(fixture, reviewedCatalog)).statusCode())
    }

    @Test
    fun exhaustionCannotBeResetByNewThreadOrReloadAndIsScopedToPartiesAndProduct() {
        val fixture = fixture()
        var state = open(fixture, 1400)
        val id = state["id"].asText()
        for (attempt in 1..3) {
            val counter = action(id, fixture.vendor.token, "COUNTER_OFFER", monetaryOffer(state), 1800 - attempt * 10)
            assertEquals(200, counter.statusCode(), counter.body())
            state = json.readTree(counter.body())
            val rejected = action(id, fixture.customer.token, "REJECT", monetaryOffer(state))
            assertEquals(200, rejected.statusCode(), rejected.body())
            state = json.readTree(rejected.body())
            if (attempt < 3) {
                val next = action(id, fixture.customer.token, "OFFER", null, 1400 + attempt * 10)
                assertEquals(200, next.statusCode(), next.body())
                state = json.readTree(next.body())
            }
        }
        assertEquals("LOCKED_ATTEMPTS_EXHAUSTED", state["status"].asText())
        assertEquals(3, state["customerAttemptsUsed"].asInt())
        assertEquals(3, state["merchantAttemptsUsed"].asInt())
        assertTrue(state["nextActor"].isNull)
        val attempts = state["offers"].size()
        denied(action(id, fixture.customer.token, "OFFER", null, 1500))
        denied(action(id, fixture.vendor.token, "COUNTER_OFFER", monetaryOffer(state), 1700))
        denied(call("POST", "/api/bargaining/threads/$id/offers", fixture.customer.token, """{"amountCfa":1500}"""))
        val reopened = call("POST", "/api/bargaining/threads", fixture.customer.token,
            """{"productId":"${fixture.productId}","amountCfa":1500,"idempotencyKey":"new-key-${UUID.randomUUID()}"}""")
        if (reopened.statusCode() == 200) {
            val retained = json.readTree(reopened.body())
            assertEquals(id, retained["id"].asText())
            assertEquals("LOCKED_ATTEMPTS_EXHAUSTED", retained["status"].asText())
        } else denied(reopened)
        val reloaded = read("/api/bargaining/threads/$id", fixture.customer.token)
        assertEquals(attempts, reloaded["offers"].size())
        assertEquals(1, read("/api/bargaining/threads", fixture.customer.token).size())
        val otherCustomer = account(RoleCode.CUSTOMER)
        assertEquals(200, call("POST", "/api/bargaining/threads", otherCustomer.token,
            """{"productId":"${fixture.productId}","amountCfa":1500,"idempotencyKey":"other-${UUID.randomUUID()}"}""").statusCode())
        val anotherProduct = product(fixture.merchantId)
        assertEquals(200, call("POST", "/api/bargaining/threads", fixture.customer.token,
            """{"productId":"${anotherProduct.id}","amountCfa":1500,"idempotencyKey":"product-${UUID.randomUUID()}"}""").statusCode())
    }

    @Test
    fun disabledOrInactiveProductsRejectNewAndExistingMonetaryActions() {
        val fixture = fixture()
        val thread = open(fixture, 1400)
        val id = thread["id"].asText()
        for (amount in listOf(0, -1, 2000, 2100)) {
            val otherProduct = product(fixture.merchantId)
            denied(call("POST", "/api/bargaining/threads", fixture.customer.token,
                """{"productId":"${otherProduct.id}","amountCfa":$amount}"""))
        }
        val endpoint = "/api/merchant/catalog/products/${fixture.productId}"
        assertEquals(200, call("PATCH", "$endpoint?merchantId=${fixture.merchantId}", fixture.vendor.token, """{"bargainingEnabled":false}""").statusCode())
        denied(action(id, fixture.vendor.token, "COUNTER_OFFER", monetaryOffer(thread), 1600))
        denied(action(id, fixture.vendor.token, "ACCEPT", monetaryOffer(thread)))
        denied(call("POST", "/api/bargaining/threads", fixture.customer.token, """{"productId":"${fixture.productId}","amountCfa":1500}"""))
        assertEquals(1, read("/api/bargaining/threads/$id", fixture.customer.token)["offers"].size())
        assertEquals(200, call("PATCH", "$endpoint?merchantId=${fixture.merchantId}", fixture.vendor.token, """{"bargainingEnabled":true}""").statusCode())
        assertEquals(200, call("POST", "$endpoint/deactivate?merchantId=${fixture.merchantId}", fixture.vendor.token).statusCode())
        denied(action(id, fixture.vendor.token, "COUNTER_OFFER", monetaryOffer(thread), 1600))
    }

    @Test
    fun concurrentMerchantRetryKeyCannotApplyDifferentCountersToTwoCustomers() {
        val fixture = fixture()
        val anotherCustomer = account(RoleCode.CUSTOMER)
        val first = open(fixture, 1400)
        val second = readPost("/api/bargaining/threads", anotherCustomer.token,
            """{"productId":"${fixture.productId}","amountCfa":1400,"idempotencyKey":"open-${UUID.randomUUID()}"}""")
        val key = "shared-merchant-retry-${UUID.randomUUID()}"
        val threads = listOf(first, second)
        val responses = threads.map { thread -> CompletableFuture.supplyAsync {
            call("POST", "/api/bargaining/threads/${thread["id"].asText()}/offers", fixture.vendor.token,
                offerBody("COUNTER_OFFER", monetaryOffer(thread), 1600, key))
        } }.map { it.join() }
        assertEquals(1, responses.count { it.statusCode() == 200 }, responses.map { it.statusCode() to it.body() }.toString())
        assertEquals(1, responses.count { it.statusCode() in setOf(400, 409) }, responses.map { it.statusCode() to it.body() }.toString())
        val reloaded = threads.map { read("/api/bargaining/threads/${it["id"].asText()}", fixture.vendor.token) }
        assertEquals(1, reloaded.sumOf { it["offers"].count { offer -> offer["offerType"].asText() == "COUNTER_OFFER" } })
        assertEquals(1, reloaded.sumOf { it["merchantAttemptsUsed"].asInt() })
        assertEquals(1, reloaded.count { it["nextActor"].asText() == "MERCHANT" && it["offers"].size() == 1 })
        assertEquals(1, reloaded.count { it["nextActor"].asText() == "CUSTOMER" && it["offers"].size() == 2 })
    }

    private data class Account(val id: String, val token: String)
    private data class Fixture(val customer: Account, val vendor: Account, val merchantId: String, val productId: String, val addressId: String)

    private fun account(role: RoleCode, merchantId: String? = null): Account {
        val user = users.save(User(email = "bargaining-${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL, roles = mutableSetOf(role)))
        val id = requireNotNull(user.id)
        merchantId?.let { memberships.save(MerchantMembership(userId = id, merchantId = it, role = role)) }
        return Account(id, jwt.generateAccessToken(UserSession(id, user.email, AuthProvider.EMAIL, setOf(role), setOfNotNull(merchantId))))
    }

    private fun fixture(): Fixture {
        val merchantId = "merchant-${UUID.randomUUID()}"
        merchants.save(MerchantRecord(id = merchantId, name = "Fixture bargaining merchant"))
        val customer = account(RoleCode.CUSTOMER)
        val vendor = account(RoleCode.MERCHANT_OWNER, merchantId)
        val product = product(merchantId)
        val address = addresses.save(CustomerAddressRecord("address-${UUID.randomUUID()}", customer.id,
            CustomerAddressKind.HOME, "Home", "Fixture recipient", "+22890000000", "Lomé", latitude = 0.0, longitude = 0.01))
        return Fixture(customer, vendor, merchantId, product.id, address.id)
    }

    private fun product(merchantId: String) = products.save(ProductRecord(id = "product-${UUID.randomUUID()}", merchantId = merchantId,
        name = "Fixture sealed item", kind = CatalogProductKind.GenericSealedItem, basePriceCfa = 2000,
        imageUrl = "https://fixture.invalid/approved.jpg", bargainingEnabled = true))

    private fun assertLegacyCheckoutClaimsRejected(fixture: Fixture, acceptedThreadId: String) {
        // The legacy HTTP DTO's sealed photo type has no discriminator. Exercise the real controller
        // with valid typed input so a malformed JSON rejection cannot masquerade as this price guard.
        val line = OrderLineRequest(fixture.productId, fixture.merchantId, "Fixture bargaining merchant",
            "Fixture sealed item", OrderProductCategory.GenericSealedItem, 2, 2000,
            photoEvidence = ProductPhotoEvidence.GenericCatalogImage)
        val negotiatedMessage = "Use the basket quote and checkout endpoints for an accepted negotiated price."
        val claims = listOf(
            line.copy(negotiatedUnitPriceCfa = 1600) to negotiatedMessage,
            line.copy(bargainingLockId = acceptedThreadId) to negotiatedMessage,
            line.copy(negotiatedUnitPriceCfa = 1600, bargainingLockId = acceptedThreadId) to negotiatedMessage,
            line.copy(unitPriceCfa = 1) to "Use the basket quote and checkout endpoints for catalog products.",
        )
        for ((claim, expectedMessage) in claims) {
            val checkoutId = "legacy-negotiated-${UUID.randomUUID()}"
            val request = OrderProcessingRequest(checkoutId, fixture.customer.id, OrderServiceLevel.Regular,
                OrderRoute.FastDelivery, listOf(claim), 1.0, paymentProvider = SequoPaymentProviders.YasTogo,
                paymentReference = checkoutId)
            val error = assertFailsWith<IllegalArgumentException> { legacyOrders.processOrder(fixture.customer.id, request) }
            val response = dev.orestegabo.sequo_api.api.ApiExceptionHandler().illegalArgument(error)
            assertEquals(400, response.statusCode.value())
            assertEquals("invalid_request", response.body?.code)
            assertEquals(expectedMessage, response.body?.message)
            assertNull(pending.findById(checkoutId).orElse(null))
        }
    }

    private fun open(fixture: Fixture, amount: Int) = readPost("/api/bargaining/threads", fixture.customer.token,
        """{"productId":"${fixture.productId}","amountCfa":$amount,"idempotencyKey":"open-${UUID.randomUUID()}"}""")
    private fun monetaryOffer(thread: JsonNode): String = thread["offers"].last { it["offerType"].asText() in setOf("OFFER", "COUNTER_OFFER") }["id"].asText()
    private fun offerBody(type: String, expected: String?, amount: Int?, key: String) = json.writeValueAsString(mapOf(
        "offerType" to type, "expectedOfferId" to expected, "amountCfa" to amount, "idempotencyKey" to key))
    private fun action(id: String, token: String, type: String, expected: String?, amount: Int? = null) =
        call("POST", "/api/bargaining/threads/$id/offers", token, offerBody(type, expected, amount, "action-${UUID.randomUUID()}"))
    private fun putCart(fixture: Fixture, threadId: String?) {
        val response = call("PUT", "/api/cart/items", fixture.customer.token, json.writeValueAsString(mapOf(
            "productId" to fixture.productId, "quantity" to 2, "bargainingThreadId" to threadId)))
        assertEquals(200, response.statusCode(), response.body())
    }
    private fun quote(fixture: Fixture) = read("/api/cart/quote?addressId=${fixture.addressId}", fixture.customer.token)
    private fun checkoutBody(fixture: Fixture, quote: JsonNode) =
        """{"addressId":"${fixture.addressId}","quoteRevision":"${quote["revision"].asText()}","paymentProvider":"yas_togo","negotiatedUnitPriceCfa":1,"totalCfa":1}"""
    private fun denied(response: HttpResponse<String>) = assertTrue(response.statusCode() in 400..499, response.body())
    private fun read(path: String, token: String): JsonNode = parse(call("GET", path, token))
    private fun readPost(path: String, token: String, body: String): JsonNode = parse(call("POST", path, token, body))
    private fun parse(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }
    private fun call(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.header("Content-Type", "application/json") }
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
