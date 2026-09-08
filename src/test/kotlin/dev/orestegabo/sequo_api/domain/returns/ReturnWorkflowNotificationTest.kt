package dev.orestegabo.sequo_api.domain.returns

import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxRepository
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxStatus
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.order.OrderFulfillmentPersistenceService
import dev.orestegabo.sequo_api.domain.order.OrderLineRequest
import dev.orestegabo.sequo_api.domain.order.OrderProcessingRequest
import dev.orestegabo.sequo_api.domain.order.OrderProcessingResult
import dev.orestegabo.sequo_api.domain.order.OrderProductCategory
import dev.orestegabo.sequo_api.domain.order.OrderRoute
import dev.orestegabo.sequo_api.domain.order.OrderServiceLevel
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:return_workflow_notifications;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class ReturnWorkflowNotificationTest @Autowired constructor(
    private val returns: ReturnPersistenceService,
    private val orderFulfillment: OrderFulfillmentPersistenceService,
    private val orders: CustomerOrderRecordRepository,
    private val outbox: NotificationOutboxRepository,
) {
    @Test
    fun `return workflow publishes outbox events after transaction commit`() {
        createDeliveredOrder()

        returns.requestReturn(
            PersistedReturnRequestCommand(
                returnId = "return-notification-1",
                orderId = "SQ-checkout-return-notification",
                customerId = "customer-return-notification",
                reason = "Wrong size delivered.",
                requestedRefundCfa = 3_000,
                rawReturnPin = "123456",
                requestedAt = Instant.parse("2026-09-10T10:00:00Z"),
            )
        )
        returns.recordRelayDropoff(
            PersistedRelayDropoffCommand(
                returnId = "return-notification-1",
                relayPointId = "relay-lome-1",
                rawReturnPin = "123456",
                droppedAt = Instant.parse("2026-09-10T12:00:00Z"),
            )
        )
        returns.recordPhysicalReceipt(
            PersistedPhysicalReceiptCommand(
                returnId = "return-notification-1",
                operatorId = "support-return-notification",
                receivedAt = Instant.parse("2026-09-11T09:00:00Z"),
                conditionAssessment = "Sealed product received by Sequo.",
                responsibility = RefundResponsibility.Merchant,
                idempotencyKey = "receipt-return-notification-1",
            )
        )
        returns.triggerRefund(
            PersistedRefundTriggerCommand(
                returnId = "return-notification-1",
                amountCfa = 3_000,
                idempotencyKey = "refund-return-notification-1",
            )
        )

        assertOutboxEvent("return-notification-1:return-requested", NotificationEventType.RETURN_REQUESTED)
        assertOutboxEvent("return-notification-1:return-pin-created", NotificationEventType.RETURN_PIN_CREATED)
        assertOutboxEvent("return-notification-1:received-by-sequo", NotificationEventType.RETURN_RECEIVED_BY_SEQUO)
        assertOutboxEvent("return-notification-1:refund-triggered", NotificationEventType.REFUND_TRIGGERED)
        assertEquals(4, outbox.findAll().count { it.aggregateId == "return-notification-1" })
    }

    private fun assertOutboxEvent(eventId: String, eventType: NotificationEventType) {
        val event = outbox.findByEventId(eventId)
        assertNotNull(event)
        assertEquals(NotificationOutboxStatus.PENDING, event.status)
        assertEquals(eventType, event.eventType)
        assertTrue(requireNotNull(event.payload).contains("customer-return-notification"))
        assertTrue(requireNotNull(event.payload).contains("return-notification-1"))
        assertTrue(!requireNotNull(event.payload).contains("123456"))
    }

    private fun createDeliveredOrder() {
        val request = OrderProcessingRequest(
            checkoutId = "checkout-return-notification",
            customerId = "customer-return-notification",
            serviceLevel = OrderServiceLevel.Regular,
            route = OrderRoute.FastDelivery,
            lines = listOf(
                OrderLineRequest(
                    productId = "return-notification-product",
                    sellerId = "merchant-return-notification",
                    sellerName = "Merchant Return Notification",
                    productName = "Returnable product",
                    category = OrderProductCategory.GeneralGoods,
                    quantity = 1,
                    unitPriceCfa = 3_000,
                    photoEvidence = ProductPhotoEvidence.LiveCameraCapture(1_722_000_000_000),
                )
            ),
            deliveryDistanceKm = 4.1,
            paymentProvider = SequoPaymentProviders.YasTogo,
            paymentReference = "yas-paid-return-notification",
        )
        orderFulfillment.persistAcceptedOrder(
            request = request,
            accepted = acceptedOrder(request),
            createdAt = Instant.parse("2026-09-08T10:00:00Z"),
        )
        val order = orders.findById("SQ-${request.checkoutId}").orElseThrow()
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
}
