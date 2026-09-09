package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxRepository
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxStatus
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.order.FulfillmentPriority
import dev.orestegabo.sequo_api.domain.order.OrderRoute
import dev.orestegabo.sequo_api.domain.order.OrderServiceLevel
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:merchant_fulfillment_notifications;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class MerchantFulfillmentNotificationTest @Autowired constructor(
    private val service: MerchantFulfillmentService,
    private val orders: CustomerOrderRecordRepository,
    private val outbox: NotificationOutboxRepository,
) {
    private val now = Instant.parse("2026-09-09T10:00:00Z")

    @Test
    fun merchantFulfillmentTransitionsPublishOutboxEventsAfterCommit() {
        orders.save(order("order-merchant-notif-1", "customer-merchant-notif-1"))
        val subOrder = service.create(command("merchant-notif-1", "order-merchant-notif-1"))

        service.accept(subOrder.id, "merchant-notif-1", now)
        service.startPreparation(subOrder.id, "merchant-notif-1", now.plusSeconds(60))
        service.markPacked(subOrder.id, "merchant-notif-1", packageCount = 1, occurredAt = now.plusSeconds(120))

        assertEvent(
            eventId = "${subOrder.id}:merchant-fulfillment:SellerAccepts",
            eventType = NotificationEventType.MERCHANT_ACCEPTED_ORDER,
            expectedPayloadFragments = listOf("customer-merchant-notif-1", "merchant-notif-1", "ACCEPTED"),
        )
        assertEvent(
            eventId = "${subOrder.id}:merchant-fulfillment:SellerStartsPreparing",
            eventType = NotificationEventType.ORDER_PREPARING,
            expectedPayloadFragments = listOf("customer-merchant-notif-1", "merchant-notif-1", "PREPARING"),
        )
        assertEvent(
            eventId = "${subOrder.id}:merchant-fulfillment:SellerMarksPacked",
            eventType = NotificationEventType.ORDER_READY_FOR_PICKUP,
            expectedPayloadFragments = listOf("customer-merchant-notif-1", "merchant-notif-1", "PACKED_READY"),
        )
    }

    @Test
    fun merchantRejectionPublishesOutboxEventWithReason() {
        orders.save(order("order-merchant-notif-2", "customer-merchant-notif-2"))
        val subOrder = service.create(command("merchant-notif-2", "order-merchant-notif-2"))

        service.reject(subOrder.id, "merchant-notif-2", "Product is out of stock.", now)

        assertEvent(
            eventId = "${subOrder.id}:merchant-fulfillment:SellerRejects",
            eventType = NotificationEventType.MERCHANT_REJECTED_ORDER,
            expectedPayloadFragments = listOf(
                "customer-merchant-notif-2",
                "merchant-notif-2",
                "REJECTED",
                "Product is out of stock.",
            ),
        )
    }

    @Test
    fun standaloneSubOrderDoesNotPublishRecipientlessFulfillmentEvents() {
        val subOrder = service.create(command("merchant-notif-3", "missing-order-merchant-notif-3"))

        service.accept(subOrder.id, "merchant-notif-3", now)

        assertNull(outbox.findByEventId("${subOrder.id}:merchant-fulfillment:SellerAccepts"))
    }

    private fun assertEvent(
        eventId: String,
        eventType: NotificationEventType,
        expectedPayloadFragments: List<String>,
    ) {
        val event = outbox.findByEventId(eventId)
        assertNotNull(event)
        assertEquals(NotificationOutboxStatus.PENDING, event.status)
        assertEquals(eventType, event.eventType)
        assertEquals("MERCHANT_SUB_ORDER", event.aggregateType)
        expectedPayloadFragments.forEach { fragment ->
            assertTrue(requireNotNull(event.payload).contains(fragment))
        }
    }

    private fun command(merchantId: String, orderId: String) =
        CreateMerchantSubOrderCommand(
            subOrderCode = "$orderId:$merchantId",
            orderId = orderId,
            merchantId = merchantId,
            itemSubtotalCfa = 10_000,
            commissionRateBps = 1500,
            commissionCfa = 1_500,
            merchantNetCfa = 8_500,
        )

    private fun order(orderId: String, customerId: String) =
        CustomerOrderRecord(
            id = orderId,
            checkoutId = "checkout-$orderId",
            customerId = customerId,
            serviceLevel = OrderServiceLevel.Regular,
            route = OrderRoute.FastDelivery,
            fulfillmentPriority = FulfillmentPriority.Standard,
            requiresConsolidation = false,
            customerFacingStatus = "Accepted for fulfillment",
            itemSubtotalCfa = 10_000,
            deliveryFeeCfa = 400,
            totalCfa = 10_400,
            paymentProvider = "YAS_TOGO",
            paymentReference = "payment-$orderId",
            providerReference = "provider-$orderId",
            paymentStatus = PaymentValidationStatus.Validated,
            orderStatus = CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT,
            createdAt = now,
            updatedAt = now,
        )
}
