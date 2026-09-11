package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookCommand
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookProviderStatus
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookService
import dev.orestegabo.sequo_api.domain.payment.SequoPaymentProviders
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

@SpringBootTest(
    properties = [
        "sequo.wallets.yas-togo.webhook-secret=yas-secret",
        "sequo.wallets.moov-africa.webhook-secret=moov-secret",
    ]
)
class PendingPaymentCheckoutServiceTest @Autowired constructor(
    private val orderController: OrderController,
    private val paymentWebhooks: PaymentWebhookService,
    private val pendingCheckouts: PendingPaymentCheckoutRepository,
    private val orders: CustomerOrderRecordRepository,
    private val lines: CustomerOrderLineRecordRepository,
    private val merchantSubOrders: MerchantSubOrderRepository,
) {
    @Test
    fun `validated wallet webhook reconciles pending checkout into merchant fulfillment`() {
        val request = request()
        val pendingResponse = orderController.processOrder("customer-webhook-reconcile", request)

        assertEquals(HttpStatus.ACCEPTED, pendingResponse.statusCode)
        assertEquals(null, orders.findByCheckoutId(request.checkoutId))
        assertEquals(PendingPaymentCheckoutStatus.AWAITING_WEBHOOK, pendingCheckouts.findById(request.checkoutId).orElseThrow().status)

        val accepted = pendingResponse.body as OrderProcessingResult.AwaitingPaymentValidation
        val receivedAt = Instant.parse("2026-09-09T10:05:00Z")
        val rawPayload = """{"eventId":"event-webhook-reconcile","checkoutId":"${request.checkoutId}","paymentReference":"${request.paymentReference}","providerReference":"yas-provider-tx-1","amountCfa":${accepted.pricing.totalCfa},"status":"VALIDATED","occurredAt":"2026-09-09T10:04:30Z"}"""
        paymentWebhooks.accept(
            PaymentWebhookCommand(
                provider = SequoPaymentProviders.YasTogo,
                eventId = "event-webhook-reconcile",
                checkoutId = request.checkoutId,
                paymentReference = request.paymentReference,
                providerReference = "yas-provider-tx-1",
                amountCfa = accepted.pricing.totalCfa,
                status = PaymentWebhookProviderStatus.VALIDATED,
                occurredAt = Instant.parse("2026-09-09T10:04:30Z"),
                receivedAt = receivedAt,
                signatureTimestamp = receivedAt,
                signature = "sha256=${hmac("yas-secret", "${receivedAt.epochSecond}.$rawPayload")}",
                rawPayload = rawPayload,
            )
        )

        val order = assertNotNull(orders.findByCheckoutId(request.checkoutId))
        assertEquals("SQ-checkout-webhook-reconcile", order.id)
        assertEquals("customer-webhook-reconcile", order.customerId)
        assertEquals("yas-provider-tx-1", order.providerReference)
        assertEquals(accepted.pricing.totalCfa, order.totalCfa)
        assertEquals(2, lines.findByOrderIdOrderByLineIndexAsc(order.id).size)
        assertEquals(2, merchantSubOrders.findByOrderId(order.id).size)

        val completed = pendingCheckouts.findById(request.checkoutId).orElseThrow()
        assertEquals(PendingPaymentCheckoutStatus.COMPLETED, completed.status)
        assertEquals("yas-provider-tx-1", completed.providerReference)
    }

    @Test
    fun `amount mismatch webhook is stored but does not fulfill pending checkout`() {
        val request = request(checkoutId = "checkout-webhook-mismatch")
        val pendingResponse = orderController.processOrder("customer-webhook-mismatch", request)
        val accepted = pendingResponse.body as OrderProcessingResult.AwaitingPaymentValidation
        val receivedAt = Instant.parse("2026-09-09T10:10:00Z")
        val wrongAmount = accepted.pricing.totalCfa + 1
        val rawPayload = """{"eventId":"event-webhook-mismatch","checkoutId":"${request.checkoutId}","paymentReference":"${request.paymentReference}","amountCfa":$wrongAmount,"status":"VALIDATED","occurredAt":"2026-09-09T10:09:30Z"}"""

        paymentWebhooks.accept(
            PaymentWebhookCommand(
                provider = SequoPaymentProviders.YasTogo,
                eventId = "event-webhook-mismatch",
                checkoutId = request.checkoutId,
                paymentReference = request.paymentReference,
                amountCfa = wrongAmount,
                status = PaymentWebhookProviderStatus.VALIDATED,
                occurredAt = Instant.parse("2026-09-09T10:09:30Z"),
                receivedAt = receivedAt,
                signatureTimestamp = receivedAt,
                signature = "sha256=${hmac("yas-secret", "${receivedAt.epochSecond}.$rawPayload")}",
                rawPayload = rawPayload,
            )
        )

        assertEquals(null, orders.findByCheckoutId(request.checkoutId))
        assertEquals(PendingPaymentCheckoutStatus.AWAITING_WEBHOOK, pendingCheckouts.findById(request.checkoutId).orElseThrow().status)
    }

    private fun request(checkoutId: String = "checkout-webhook-reconcile") =
        OrderProcessingRequest(
            checkoutId = checkoutId,
            customerId = "untrusted-client-customer",
            serviceLevel = OrderServiceLevel.Regular,
            route = OrderRoute.GroupedSequo,
            lines = listOf(foodLine(), groceryLine()),
            deliveryDistanceKm = 4.1,
            paymentProvider = SequoPaymentProviders.YasTogo,
            paymentReference = "yas-$checkoutId",
        )

    private fun foodLine() = OrderLineRequest(
        productId = "food-1",
        sellerId = "merchant-food",
        sellerName = "Merchant Food",
        productName = "Meal one",
        category = OrderProductCategory.Food,
        quantity = 2,
        unitPriceCfa = 2_500,
        photoEvidence = ProductPhotoEvidence.LiveCameraCapture(1_722_000_000_000),
    )

    private fun groceryLine() = OrderLineRequest(
        productId = "grocery-1",
        sellerId = "merchant-grocery",
        sellerName = "Merchant Grocery",
        productName = "Rice bag",
        category = OrderProductCategory.GenericSealedItem,
        quantity = 1,
        unitPriceCfa = 3_000,
        photoEvidence = ProductPhotoEvidence.GenericCatalogImage,
    )
}

private fun hmac(secret: String, value: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
