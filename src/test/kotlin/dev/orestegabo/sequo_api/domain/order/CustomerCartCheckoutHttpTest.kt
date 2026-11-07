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
) {
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
