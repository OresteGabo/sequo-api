package dev.orestegabo.sequo_api.domain.payment

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.InMemoryRateLimiter
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderStatus
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingInput
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:production_callback_gate;DB_CLOSE_DELAY=-1",
    "sequo.wallets.yas-togo.api-base-url=https://wallet-fixture.sequo.tg",
    "sequo.wallets.yas-togo.api-key=synthetic-yas-key", "sequo.wallets.yas-togo.merchant-account-id=synthetic-yas-account",
    "sequo.wallets.yas-togo.webhook-secret=isolated-callback-yas-secret",
    "sequo.wallets.moov-africa.api-base-url=https://wallet-fixture.sequo.tg",
    "sequo.wallets.moov-africa.api-key=synthetic-moov-key", "sequo.wallets.moov-africa.merchant-account-id=synthetic-moov-account",
    "sequo.wallets.moov-africa.webhook-secret=isolated-callback-moov-secret",
])
class PaymentWebhookFailClosedHttpTest @Autowired constructor(private val context: ApplicationContext) {
    companion object {
        @JvmStatic @DynamicPropertySource fun postgres(registry: DynamicPropertyRegistry) = callbackPostgres(registry)
    }
    @LocalServerPort private var port = 0

    @Test
    fun credentialLikeConfigurationAndNormalizedSignaturesCannotAcceptEventsOrFulfillOrders() {
        context.getBean(InMemoryRateLimiter::class.java).resetForTests()
        for (provider in listOf(SequoPaymentProviders.YasTogo, SequoPaymentProviders.MoovAfrica)) {
            val pending = CallbackFixtures.pending(context, provider)
            val eventId = "production-rejected-${UUID.randomUUID()}"
            val body = CallbackFixtures.event(pending, eventId)
            assertTrue(CallbackFixtures.send(port, provider, body, includeHeaders = false).statusCode() in setOf(400, 403))
            assertEquals(400, CallbackFixtures.send(port, provider, body, signature = "00".repeat(32)).statusCode())
            repeat(2) {
                val rejection = CallbackFixtures.send(port, provider, body)
                assertEquals(400, rejection.statusCode(), rejection.body())
            }
            assertNull(context.getBean(PaymentWebhookEventRepository::class.java).findByProviderAndEventId(provider.value, eventId))
            assertNull(context.getBean(CustomerOrderRecordRepository::class.java).findByCheckoutId(pending.checkoutId))
            assertTrue(context.getBean(MerchantSubOrderRepository::class.java)
                .findByMerchantIdAndStatusInOrderByUpdatedAtDesc(pending.merchantId, MerchantSubOrderStatus.entries.toSet()).isEmpty())
            assertEquals(PendingPaymentCheckoutStatus.AWAITING_WEBHOOK,
                context.getBean(PendingPaymentCheckoutRepository::class.java).findById(pending.checkoutId).orElseThrow().status)
        }
    }
}

@Import(PaymentWebhookFixtureConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:internal_callback_fixture;DB_CLOSE_DELAY=-1",
    "sequo.wallets.yas-togo.api-base-url=https://wallet-fixture.sequo.tg",
    "sequo.wallets.yas-togo.api-key=synthetic-yas-key", "sequo.wallets.yas-togo.merchant-account-id=synthetic-yas-account",
    "sequo.wallets.yas-togo.webhook-secret=isolated-callback-yas-secret",
    "sequo.wallets.moov-africa.api-base-url=https://wallet-fixture.sequo.tg",
    "sequo.wallets.moov-africa.api-key=synthetic-moov-key", "sequo.wallets.moov-africa.merchant-account-id=synthetic-moov-account",
    "sequo.wallets.moov-africa.webhook-secret=isolated-callback-moov-secret",
])
class PaymentWebhookInternalFixtureHttpTest @Autowired constructor(private val context: ApplicationContext) {
    companion object {
        @JvmStatic @DynamicPropertySource fun postgres(registry: DynamicPropertyRegistry) = callbackPostgres(registry)
    }
    @LocalServerPort private var port = 0

    @Test
    fun explicitTestSourceVerifierReconcilesBothProvidersAndReplaysWithoutDuplicateOrders() {
        context.getBean(InMemoryRateLimiter::class.java).resetForTests()
        for (provider in listOf(SequoPaymentProviders.YasTogo, SequoPaymentProviders.MoovAfrica)) {
            val pending = CallbackFixtures.pending(context, provider)
            val waiting = CallbackFixtures.event(pending, "pending-${UUID.randomUUID()}", status = "PENDING")
            assertEquals(202, CallbackFixtures.send(port, provider, waiting).statusCode())
            val orders = context.getBean(CustomerOrderRecordRepository::class.java)
            assertNull(orders.findByCheckoutId(pending.checkoutId))
            val wrongProvider = if (provider == SequoPaymentProviders.YasTogo) SequoPaymentProviders.MoovAfrica else SequoPaymentProviders.YasTogo
            val crossProvider = CallbackFixtures.event(pending, "wrong-provider-${UUID.randomUUID()}")
            assertEquals(202, CallbackFixtures.send(port, wrongProvider, crossProvider).statusCode())
            assertNull(orders.findByCheckoutId(pending.checkoutId))
            val id = "validated-${UUID.randomUUID()}"
            val valid = CallbackFixtures.event(pending, id)
            assertEquals(400, CallbackFixtures.send(port, provider, valid, signature = "00".repeat(32)).statusCode())
            repeat(2) {
                val response = CallbackFixtures.send(port, provider, valid)
                assertEquals(202, response.statusCode(), response.body())
                assertEquals(it > 0, CallbackFixtures.json.readTree(response.body())["duplicate"].asBoolean())
            }
            assertEquals(202, CallbackFixtures.send(port, provider,
                CallbackFixtures.event(pending, "retry-new-id-${UUID.randomUUID()}")).statusCode())
            val order = assertNotNull(orders.findByCheckoutId(pending.checkoutId))
            assertEquals(provider.value, order.paymentProvider)
            assertEquals(PaymentValidationStatus.Validated, order.paymentStatus)
            assertEquals(2400, order.totalCfa)
            assertEquals(1, orders.findByCustomerIdOrderByCreatedAtDesc(pending.customerId).size)
            assertEquals(1, context.getBean(MerchantSubOrderRepository::class.java).findByOrderId(order.id).size)
            assertEquals(PendingPaymentCheckoutStatus.COMPLETED,
                context.getBean(PendingPaymentCheckoutRepository::class.java).findById(pending.checkoutId).orElseThrow().status)
            assertNotNull(context.getBean(PaymentWebhookEventRepository::class.java).findByProviderAndEventId(provider.value, id))
        }
    }
}

private fun callbackPostgres(registry: DynamicPropertyRegistry) {
    val url = System.getenv("SEQUO_PAYMENT_CALLBACK_TEST_URL") ?: return
    require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) {
        "Use a disposable local callback fixture database."
    }
    registry.add("spring.datasource.url") { url }
    registry.add("spring.datasource.username") { "sequo_fixture" }
    registry.add("spring.datasource.password") { "" }
    registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
    registry.add("spring.jpa.database-platform") { "org.hibernate.dialect.PostgreSQLDialect" }
    registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
    registry.add("spring.flyway.enabled") { "true" }
}

private data class CallbackPending(val checkoutId: String, val customerId: String, val merchantId: String, val provider: PaymentProviderId)
private object CallbackFixtures {
    val json = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    fun pending(context: ApplicationContext, provider: PaymentProviderId): CallbackPending {
        val suffix = UUID.randomUUID().toString()
        val customer = context.getBean(UserRepository::class.java).save(User(email = "callback-$suffix@fixture.invalid", provider = AuthProvider.EMAIL))
        val merchantId = "callback-merchant-$suffix"
        context.getBean(CommerceMerchantRepository::class.java).save(MerchantRecord(merchantId, name = "Callback fixture merchant"))
        val product = context.getBean(CommerceProductRepository::class.java).save(ProductRecord("callback-product-$suffix", merchantId = merchantId,
            name = "Callback fixture item", kind = CatalogProductKind.GenericSealedItem, basePriceCfa = 2000,
            imageUrl = "https://fixture.invalid/product.jpg"))
        val checkoutId = "callback-checkout-$suffix"
        val request = OrderProcessingRequest(checkoutId, requireNotNull(customer.id), OrderServiceLevel.Regular, OrderRoute.FastDelivery,
            listOf(OrderLineRequest(product.id, merchantId, "Callback fixture merchant", product.name, OrderProductCategory.GenericSealedItem,
                1, 2000, photoEvidence = ProductPhotoEvidence.GenericCatalogImage)), 1.0,
            paymentProvider = provider, paymentReference = checkoutId)
        val delivery = DeliveryPricingService().calculate(DeliveryPricingInput(1.0))
        context.getBean(PendingPaymentCheckoutService::class.java).recordCartCheckout(request,
            OrderPricingSnapshot(2000, delivery, 2000 + delivery.finalDeliveryFeeCfa), PendingPaymentCheckoutStatus.AWAITING_WEBHOOK)
        return CallbackPending(checkoutId, requireNotNull(customer.id), merchantId, provider)
    }
    fun event(pending: CallbackPending, id: String, status: String = "VALIDATED") = json.writeValueAsString(mapOf(
        "eventId" to id, "checkoutId" to pending.checkoutId, "paymentReference" to pending.checkoutId,
        "providerReference" to "internal-fixture-${pending.provider.value}", "amountCfa" to 2400, "currency" to "XOF",
        "status" to status, "occurredAt" to Instant.now().toString()))
    fun send(port: Int, provider: PaymentProviderId, body: String, signature: String? = null, includeHeaders: Boolean = true): HttpResponse<String> {
        val timestamp = Instant.now().epochSecond
        val secret = if (provider == SequoPaymentProviders.YasTogo) "isolated-callback-yas-secret" else "isolated-callback-moov-secret"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val signed = signature ?: mac.doFinal("$timestamp.$body".toByteArray()).joinToString("") { "%02x".format(it) }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/payments/webhooks/${provider.value}"))
            .header("Content-Type", "application/json")
        if (includeHeaders) request.header("X-Sequo-Webhook-Timestamp", timestamp.toString()).header("X-Sequo-Webhook-Signature", signed)
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }
}
