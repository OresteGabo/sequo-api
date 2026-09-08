package dev.orestegabo.sequo_api.domain.returns

import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.order.OrderLineRequest
import dev.orestegabo.sequo_api.domain.order.OrderProcessingRequest
import dev.orestegabo.sequo_api.domain.order.OrderProcessingResult
import dev.orestegabo.sequo_api.domain.order.OrderProductCategory
import dev.orestegabo.sequo_api.domain.order.OrderRoute
import dev.orestegabo.sequo_api.domain.order.OrderServiceLevel
import dev.orestegabo.sequo_api.domain.order.OrderFulfillmentPersistenceService
import dev.orestegabo.sequo_api.domain.order.ProductPhotoEvidence
import dev.orestegabo.sequo_api.domain.order.RegularOrderProcessor
import dev.orestegabo.sequo_api.domain.payment.PaymentProcessor
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationRequest
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationResult
import dev.orestegabo.sequo_api.domain.payment.SequoPaymentProviders
import dev.orestegabo.sequo_api.domain.payment.YasTogoPaymentMethod
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class ReturnPersistenceServiceTest @Autowired constructor(
    private val service: ReturnPersistenceService,
    private val orderFulfillment: OrderFulfillmentPersistenceService,
    private val orders: CustomerOrderRecordRepository,
    private val returns: ReturnRequestRecordRepository,
) {
    @Test
    fun `return workflow is persisted from customer request through refund trigger`() {
        createDeliveredOrder()

        val requested = service.requestReturn(
            PersistedReturnRequestCommand(
                returnId = "return-persisted-1",
                orderId = "SQ-checkout-return-persistence",
                customerId = "customer-return",
                reason = "Wrong size delivered.",
                requestedRefundCfa = 3_000,
                rawReturnPin = "123456",
                requestedAt = Instant.parse("2026-09-10T10:00:00Z"),
            )
        ) as PersistedReturnResult.Accepted
        val stored = returns.findById("return-persisted-1").orElseThrow()

        assertEquals(ReturnStatus.AwaitingRelayDropoff, requested.returnRequest.status)
        assertEquals(CustomerOrderStatus.RETURN_REQUESTED, orders.findById("SQ-checkout-return-persistence").orElseThrow().orderStatus)
        assertNotEquals("123456", stored.returnPinHash)
        assertTrue(stored.returnPinHash.startsWith("sha256:"))

        val wrongPin = service.recordRelayDropoff(
            PersistedRelayDropoffCommand(
                returnId = "return-persisted-1",
                relayPointId = "relay-lome-1",
                rawReturnPin = "000000",
                droppedAt = Instant.parse("2026-09-10T12:00:00Z"),
            )
        )
        assertTrue(wrongPin is PersistedReturnResult.Rejected)
        assertEquals("invalid_return_pin", wrongPin.rejection.code)

        val dropped = service.recordRelayDropoff(
            PersistedRelayDropoffCommand(
                returnId = "return-persisted-1",
                relayPointId = "relay-lome-1",
                rawReturnPin = "123456",
                droppedAt = Instant.parse("2026-09-10T12:00:00Z"),
            )
        ) as PersistedReturnResult.Accepted
        assertEquals(ReturnStatus.DroppedAtRelay, dropped.returnRequest.status)
        assertEquals("relay-lome-1", dropped.returnRequest.relayPointId)

        val blockedRefund = service.triggerRefund(
            PersistedRefundTriggerCommand(
                returnId = "return-persisted-1",
                amountCfa = 3_000,
                idempotencyKey = "refund-before-receipt",
            )
        )
        assertTrue(blockedRefund is PersistedReturnResult.Rejected)
        assertEquals("physical_receipt_required", blockedRefund.rejection.code)

        val received = service.recordPhysicalReceipt(
            PersistedPhysicalReceiptCommand(
                returnId = "return-persisted-1",
                operatorId = "support-1",
                receivedAt = Instant.parse("2026-09-11T09:00:00Z"),
                conditionAssessment = "Sealed product received by Sequo in resellable condition.",
                responsibility = RefundResponsibility.Merchant,
                idempotencyKey = "receipt-return-persisted-1",
            )
        ) as PersistedReturnResult.Accepted
        assertEquals(ReturnStatus.ReceivedBySequo, received.returnRequest.status)

        val refunded = service.triggerRefund(
            PersistedRefundTriggerCommand(
                returnId = "return-persisted-1",
                amountCfa = 3_000,
                idempotencyKey = "refund-return-persisted-1",
            )
        ) as PersistedReturnResult.Accepted
        val duplicateRefund = service.triggerRefund(
            PersistedRefundTriggerCommand(
                returnId = "return-persisted-1",
                amountCfa = 3_000,
                idempotencyKey = "refund-return-persisted-1",
            )
        ) as PersistedReturnResult.Accepted

        assertEquals(ReturnStatus.RefundPending, refunded.returnRequest.status)
        assertEquals(3_000, refunded.returnRequest.refundAmountCfa)
        assertEquals(refunded.returnRequest, duplicateRefund.returnRequest)
        assertEquals(1, service.listForCustomer("customer-return").size)
        assertEquals(1, service.listByStatus(ReturnStatus.RefundPending).size)
    }

    @Test
    fun `persisted return request rejects food unless admin overrides`() {
        createDeliveredOrder(checkoutId = "checkout-return-food", category = OrderProductCategory.Food)

        val rejected = service.requestReturn(
            PersistedReturnRequestCommand(
                returnId = "return-food-rejected",
                orderId = "SQ-checkout-return-food",
                customerId = "customer-return",
                reason = "Food issue.",
                requestedRefundCfa = 3_000,
                rawReturnPin = "123456",
                requestedAt = Instant.parse("2026-09-10T10:00:00Z"),
            )
        )
        val accepted = service.requestReturn(
            PersistedReturnRequestCommand(
                returnId = "return-food-admin",
                orderId = "SQ-checkout-return-food",
                customerId = "customer-return",
                reason = "Support exception.",
                requestedRefundCfa = 3_000,
                rawReturnPin = "654321",
                requestedAt = Instant.parse("2026-09-10T10:00:00Z"),
                adminOverride = true,
            )
        )

        assertTrue(rejected is PersistedReturnResult.Rejected)
        assertEquals("item_not_returnable", rejected.rejection.code)
        assertTrue(accepted is PersistedReturnResult.Accepted)
        assertEquals(ReturnStatus.AwaitingRelayDropoff, accepted.returnRequest.status)
    }

    private fun createDeliveredOrder(
        checkoutId: String = "checkout-return-persistence",
        category: OrderProductCategory = OrderProductCategory.GeneralGoods,
    ) {
        val request = OrderProcessingRequest(
            checkoutId = checkoutId,
            customerId = "customer-return",
            serviceLevel = OrderServiceLevel.Regular,
            route = OrderRoute.FastDelivery,
            lines = listOf(line(category)),
            deliveryDistanceKm = 4.1,
            paymentProvider = SequoPaymentProviders.YasTogo,
            paymentReference = "yas-paid-$checkoutId",
        )
        val accepted = acceptedOrder(request)
        orderFulfillment.persistAcceptedOrder(request, accepted, Instant.parse("2026-09-08T10:00:00Z"))
        val order = orders.findById("SQ-$checkoutId").orElseThrow()
        order.orderStatus = CustomerOrderStatus.DELIVERED
        order.deliveredAt = Instant.parse("2026-09-09T10:00:00Z")
        order.returnWindowEndsAt = Instant.parse("2026-09-12T10:00:00Z")
        order.updatedAt = Instant.parse("2026-09-09T10:00:00Z")
        orders.save(order)
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

    private fun line(category: OrderProductCategory) = OrderLineRequest(
        productId = "returnable-1",
        sellerId = "merchant-return",
        sellerName = "Merchant Return",
        productName = "Returnable product",
        category = category,
        quantity = 1,
        unitPriceCfa = 3_000,
        photoEvidence = ProductPhotoEvidence.LiveCameraCapture(1_722_000_000_000),
    )
}
