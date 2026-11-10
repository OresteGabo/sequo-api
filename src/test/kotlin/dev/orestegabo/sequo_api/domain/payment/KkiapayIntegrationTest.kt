package dev.orestegabo.sequo_api.domain.payment

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.*
import dev.orestegabo.sequo_api.domain.commerce.*
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.platform.*
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.jdbc.core.JdbcTemplate
import org.mockito.Mockito
import java.net.URI
import java.net.http.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:kkiapay_checkout;DB_CLOSE_DELAY=-1",
    "sequo.testing.orders.enabled=true", "sequo.cart.straight-line-dispatch-origin=0,0",
    "sequo.kkiapay.enabled=true", "sequo.kkiapay.sandbox=true",
    "sequo.kkiapay.public-key=synthetic-public-key-123456",
    "sequo.kkiapay.private-key=synthetic-private-key-123456",
    "sequo.kkiapay.secret-key=synthetic-secret-key-123456",
    "sequo.kkiapay.webhook-secret=synthetic-webhook-secret-12345678901234567890"
])
class KkiapayIntegrationTest @Autowired constructor(
    private val users: UserRepository, private val jwt: JwtService,
    private val merchants: CommerceMerchantRepository, private val products: CommerceProductRepository,
    private val addresses: CustomerAddressRepository, private val orders: CustomerOrderRecordRepository,
    private val jdbc: JdbcTemplate, private val sessions: KkiapayCheckoutSessionRepository,
    private val providers: PaymentProviderConfigurationRepository,
) {
    companion object {
        @JvmStatic @org.springframework.test.context.DynamicPropertySource
        fun postgresFixture(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_KKIAPAY_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_kkiapay_fixture")))
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
    @MockitoBean private lateinit var gateway: KkiapayGateway
    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()
    private val secret = "synthetic-webhook-secret-12345678901234567890"

    @Test fun sandboxPaymentsRequireOwnershipExactProviderEvidenceAndNeverAccrueRealFunds() {
        providers.save(PaymentProviderConfigurationRecord("kkiapay", "KKiaPay", "TG", PaymentProviderReadiness.PLANNED))
        val user = users.save(User(email = "${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL, status = UserStatus.ACTIVE))
        val userId = requireNotNull(user.id)
        val token = jwt.generateAccessToken(UserSession(userId, user.email, AuthProvider.EMAIL))
        merchants.save(MerchantRecord(id = "demo-v1-shop-mode", name = "[TEST] shop"))
        products.saveAndFlush(ProductRecord("demo-v1-mode-98", "demo-v1-shop-mode", name = "[TEST] item", kind = CatalogProductKind.GenericSealedItem,
            category = "fashion", basePriceCfa = 1000, imageUrl = "https://fixture.invalid/test.jpg"))
        val address = addresses.save(CustomerAddressRecord("address-${UUID.randomUUID()}", userId, CustomerAddressKind.HOME,
            "Home", "Test recipient", "+22890000000", "Lomé", latitude = 0.0, longitude = 0.01))
        assertEquals(200, call("PUT", "/api/cart/items", token, """{"productId":"demo-v1-mode-98","quantity":2}""").statusCode())
        val quote = mapper.readTree(call("GET", "/api/cart/quote?addressId=${address.id}", token).body())
        assertTrue(quote["testOrderAvailable"].asBoolean())
        val body = """{"addressId":"${address.id}","quoteRevision":"${quote["revision"].asText()}","paymentProvider":"kkiapay","testOrder":true}"""
        val checkoutResponse = call("POST", "/api/orders/checkouts", token, body)
        assertEquals(202, checkoutResponse.statusCode(), checkoutResponse.body())
        val checkout = mapper.readTree(checkoutResponse.body()); val id = checkout["checkoutId"].asText()
        val amount = checkout["totalCfa"].asInt()
        assertTrue(checkout["paymentSandbox"].asBoolean())
        assertNull(orders.findByCheckoutId(id))
        assertEquals(202, call("POST", "/api/orders/checkouts", token, body).statusCode())
        assertEquals(403, call("POST", "/api/orders/checkouts/$id/kkiapay-session").statusCode())
        val other = users.save(User(email = "${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL, status = UserStatus.ACTIVE))
        val otherToken = jwt.generateAccessToken(UserSession(requireNotNull(other.id), other.email, AuthProvider.EMAIL))
        assertTrue(call("POST", "/api/orders/checkouts/$id/kkiapay-session", otherToken).statusCode() in listOf(400,404))
        assertEquals(400, call("POST", "/api/orders/checkouts/$id/test-payment", token).statusCode())
        val linkResponse = call("POST", "/api/orders/checkouts/$id/kkiapay-session", token)
        assertEquals(200, linkResponse.statusCode(), linkResponse.body())
        val url = mapper.readTree(linkResponse.body())["url"].asText()
        val launchToken = URI(url).rawQuery.removePrefix("token=")
        val session = assertNotNull(sessions.findByCheckoutId(id))
        val page = call("GET", "/payments/kkiapay?token=$launchToken")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("\"countries\":[\"TG\"]"))
        assertTrue(page.body().contains("\"accept\":[\"mixx-tg\",\"moov-tg\"]"))
        assertTrue(page.body().contains("\"paymentmethod\":[\"momo\"]"))
        assertFalse(page.body().contains("synthetic-secret"))
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(""))
        assertEquals(401, call("GET", "/payments/kkiapay?token=" + launchToken.dropLast(1) + if (launchToken.last() == 'a') "b" else "a").statusCode())
        assertEquals(401, call("POST", "/api/payments/kkiapay/webhook", body = """{"transactionId":"paid"}""").statusCode())
        for (invalid in listOf("null", "[]", "{invalid")) {
            assertEquals(400, call("POST", "/api/payments/kkiapay/webhook", body = invalid, webhookSecret = secret).statusCode())
        }
        Mockito.verifyNoInteractions(gateway)
        fun proof(tx: String = "paid", total: Int = amount, correlation: String = session.id, status: String = "SUCCESS") =
            KkiapayTransaction(tx, status, total, correlation, Instant.parse("2026-11-10T00:00:00Z"), "mixx-tg")
        fun complete(tx: String) = call("POST", "/api/payments/kkiapay/complete", body = """{"token":"$launchToken","transactionId":"$tx"}""")
        Mockito.`when`(gateway.verify("wrong-amount")).thenReturn(proof("wrong-amount", amount + 1))
        assertEquals(400, complete("wrong-amount").statusCode())
        Mockito.`when`(gateway.verify("wrong-owner")).thenReturn(proof("wrong-owner", correlation = UUID.randomUUID().toString()))
        assertEquals(400, complete("wrong-owner").statusCode())
        Mockito.`when`(gateway.verify("waiting")).thenReturn(proof("waiting", status = "PENDING"))
        assertEquals("PENDING", mapper.readTree(complete("waiting").body())["status"].asText())
        assertNull(orders.findByCheckoutId(id))
        Mockito.`when`(gateway.verify("paid")).thenReturn(proof())
        // Webhook payload flags and amounts are ignored: only upstream evidence can settle the checkout.
        val webhook = call("POST", "/api/payments/kkiapay/webhook", body = """{"transactionId":"paid","amount":1,"isPaymentSucces":false,"partnerId":"forged"}""", webhookSecret = secret)
        assertEquals(200, webhook.statusCode(), webhook.body())
        val order = assertNotNull(orders.findByCheckoutId(id))
        assertEquals(200, complete("paid").statusCode())
        assertEquals(1, orders.findByCustomerIdOrderByCreatedAtDesc(userId).size)
        assertEquals("sandbox:paid", sessions.findByCheckoutId(id)?.transactionId)
        assertEquals(0, jdbc.queryForObject("select count(*) from merchant_payout_accruals where order_id=?", Int::class.java, order.id))
        assertEquals(1, jdbc.queryForObject("select count(*) from payment_transaction_claims where provider='kkiapay'", Int::class.java))
        Mockito.`when`(gateway.verify("paid-twice")).thenReturn(proof("paid-twice"))
        assertEquals(409, complete("paid-twice").statusCode())
        assertTrue(call("GET", "/payments/kkiapay?token=$launchToken").body().contains("id=\"pay\" disabled"))
        assertEquals(200, call("POST", "/api/orders/checkouts/$id/kkiapay-refresh", token).statusCode())
        assertEquals(404, call("POST", "/api/orders/checkouts/$id/kkiapay-refresh", otherToken).statusCode())
        providers.findById("kkiapay").orElseThrow().also { it.readiness = PaymentProviderReadiness.DISABLED; providers.saveAndFlush(it) }
        val disabled = mapper.readTree(call("GET", "/api/payments/providers").body()).first { it["providerId"].asText() == "kkiapay" }
        assertFalse(disabled["checkoutConnected"].asBoolean())
        assertEquals(400, call("POST", "/api/orders/checkouts/$id/kkiapay-session", token).statusCode())
        // Disabling new collection must not lose a payment already made.
        assertEquals(200, call("POST", "/api/payments/kkiapay/webhook", body = """{"transactionId":"paid"}""", webhookSecret = secret).statusCode())
        providers.findById("kkiapay").orElseThrow().also { it.readiness = PaymentProviderReadiness.PLANNED; providers.saveAndFlush(it) }
        products.saveAndFlush(ProductRecord("ordinary-item", "demo-v1-shop-mode", name = "Ordinary item", kind = CatalogProductKind.GenericSealedItem,
            basePriceCfa = 2000, imageUrl = "https://fixture.invalid/ordinary.jpg"))
        assertEquals(200, call("PUT", "/api/cart/items", token, """{"productId":"ordinary-item","quantity":1}""").statusCode())
        val ordinary = mapper.readTree(call("GET", "/api/cart/quote?addressId=${address.id}", token).body())
        assertFalse(ordinary["testOrderAvailable"].asBoolean())
        for (test in listOf(true, false)) {
            val denied = call("POST", "/api/orders/checkouts", token,
                """{"addressId":"${address.id}","quoteRevision":"${ordinary["revision"].asText()}","paymentProvider":"kkiapay","testOrder":$test}""")
            assertEquals(400, denied.statusCode(), denied.body())
        }
        assertEquals(1, mapper.readTree(call("GET", "/api/cart", token).body())["totalItems"].asInt())
    }
    private fun call(method: String, path: String, token: String? = null, body: String? = null, webhookSecret: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        webhookSecret?.let { request.header("x-kkiapay-secret", it) }
        body?.let { request.header("Content-Type", "application/json") }
        return http.send(request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
}
