package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.payment.PaymentProcessor
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationRequest
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationResult
import dev.orestegabo.sequo_api.domain.payment.SequoPaymentProviders
import dev.orestegabo.sequo_api.domain.payment.YasTogoPaymentMethod
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class OrderFulfillmentPersistenceServiceTest @Autowired constructor(
    private val service: OrderFulfillmentPersistenceService,
    private val orders: CustomerOrderRecordRepository,
    private val lines: CustomerOrderLineRecordRepository,
    private val merchantSubOrders: MerchantSubOrderRepository,
) {
    @Test
    fun `accepted paid order is persisted and split into merchant sub-orders idempotently`() {
        val request = request()
        val accepted = acceptedOrder(request)

        val first = service.persistAcceptedOrder(request, accepted, Instant.parse("2026-09-08T10:00:00Z"))
        val second = service.persistAcceptedOrder(request, accepted, Instant.parse("2026-09-08T10:05:00Z"))

        assertEquals(1, orders.count())
        assertEquals(3, lines.count())
        assertEquals(2, merchantSubOrders.findByOrderId(accepted.order.orderId).size)
        assertEquals(first.order, second.order)
        assertEquals(first.lines.map { it.id }, second.lines.map { it.id })
        assertEquals(
            setOf("SQ-checkout-order-persistence:merchant-food", "SQ-checkout-order-persistence:merchant-grocery"),
            second.merchantSubOrders.map { it.subOrderCode }.toSet(),
        )
        assertEquals(8_000, second.merchantSubOrders.single { it.merchantId == "merchant-food" }.itemSubtotalCfa)
        assertEquals(3_000, second.merchantSubOrders.single { it.merchantId == "merchant-grocery" }.itemSubtotalCfa)
    }

    @Test
    fun `pending payment is not persisted before provider validation`() {
        val processor = RegularOrderProcessor(
            paymentProcessor = PaymentProcessor(
                listOf(YasTogoPaymentMethod(validateHandler = { PaymentValidationResult.pending("Waiting provider callback.") }))
            ),
            pricingService = DeliveryPricingService(),
        )

        val result = processor.process(request(lines = listOf(foodLine())))

        assertTrue(result is OrderProcessingResult.AwaitingPaymentValidation)
        assertEquals(0, orders.count())
        assertEquals(0, lines.count())
        assertEquals(0, merchantSubOrders.count())
    }

    private fun acceptedOrder(request: OrderProcessingRequest): OrderProcessingResult.AcceptedForFulfillment {
        val processor = RegularOrderProcessor(
            paymentProcessor = PaymentProcessor(
                listOf(YasTogoPaymentMethod(validateHandler = { validatedPayment(it) }))
            ),
            pricingService = DeliveryPricingService(),
        )
        return processor.process(request) as OrderProcessingResult.AcceptedForFulfillment
    }

    private fun validatedPayment(request: PaymentValidationRequest): PaymentValidationResult =
        PaymentValidationResult.validated(request.paymentReference)

    private fun request(lines: List<OrderLineRequest> = listOf(foodLine(), secondFoodLine(), groceryLine())) =
        OrderProcessingRequest(
            checkoutId = "checkout-order-persistence",
            customerId = "customer-order-persistence",
            serviceLevel = OrderServiceLevel.Regular,
            route = OrderRoute.FastDelivery,
            lines = lines,
            deliveryDistanceKm = 4.1,
            paymentProvider = SequoPaymentProviders.YasTogo,
            paymentReference = "yas-paid-order-persistence",
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

    private fun secondFoodLine() = OrderLineRequest(
        productId = "food-2",
        sellerId = "merchant-food",
        sellerName = "Merchant Food",
        productName = "Meal two",
        category = OrderProductCategory.Food,
        quantity = 1,
        unitPriceCfa = 3_000,
        photoEvidence = ProductPhotoEvidence.LiveCameraCapture(1_722_000_010_000),
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
