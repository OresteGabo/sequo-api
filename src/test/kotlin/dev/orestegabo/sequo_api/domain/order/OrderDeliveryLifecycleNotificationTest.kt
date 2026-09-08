package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.delivery.CreateDeliveryMissionCommand
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionEvent
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordDestination
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordMode
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionService
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionServiceResult
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxRepository
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxStatus
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

@SpringBootTest
class OrderDeliveryLifecycleNotificationTest @Autowired constructor(
    private val fulfillmentPersistence: OrderFulfillmentPersistenceService,
    private val lifecycle: OrderDeliveryLifecycleService,
    private val deliveryMissions: DeliveryMissionService,
    private val outbox: NotificationOutboxRepository,
) {
    @Test
    fun `delivery completion publishes notification outbox event after commit`() {
        val request = request("checkout-delivery-notification")
        val processor = RegularOrderProcessor(
            paymentProcessor = PaymentProcessor(
                listOf(YasTogoPaymentMethod(validateHandler = { validatedPayment(it) }))
            ),
            pricingService = DeliveryPricingService(),
        )
        fulfillmentPersistence.persistAcceptedOrder(
            request = request,
            accepted = processor.process(request) as OrderProcessingResult.AcceptedForFulfillment,
            createdAt = Instant.parse("2026-09-08T10:00:00Z"),
        )
        val mission = deliveryMissions.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "ORDER-LIFECYCLE-NOTIF",
                orderId = "SQ-${request.checkoutId}",
                deliveryMode = DeliveryMissionRecordMode.EXPRESS,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            )
        )
        deliveryMissions.assignCourier(mission.id, "courier-lifecycle-notif")
        deliveryMissions.transition(mission.id, "admin-lifecycle-notif", DeliveryMissionEvent.OfferToCourier)
        deliveryMissions.transition(mission.id, "courier-lifecycle-notif", DeliveryMissionEvent.CourierAccepts)
        deliveryMissions.transition(mission.id, "courier-lifecycle-notif", DeliveryMissionEvent.CourierPicksUpFromSeller, proof = "pickup")
        val delivered = deliveryMissions.transition(
            mission.id,
            "courier-lifecycle-notif",
            DeliveryMissionEvent.CourierDeliversToCustomer,
            proof = "dropoff",
            at = Instant.parse("2026-09-09T12:00:00Z"),
        ) as DeliveryMissionServiceResult.Success

        lifecycle.markDeliveredFromMission(delivered.mission, "courier-lifecycle-notif")

        val event = outbox.findByEventId("SQ-${request.checkoutId}:delivery-completed")
        assertTrue(event != null)
        assertEquals(NotificationOutboxStatus.PENDING, event.status)
        assertTrue(requireNotNull(event.payload).contains("returnWindowEndsAt"))
    }

    private fun request(checkoutId: String) =
        OrderProcessingRequest(
            checkoutId = checkoutId,
            customerId = "customer-delivery-notification",
            serviceLevel = OrderServiceLevel.Regular,
            route = OrderRoute.FastDelivery,
            lines = listOf(
                OrderLineRequest(
                    productId = "food-notif",
                    sellerId = "merchant-delivery-notification",
                    sellerName = "Merchant Delivery Notification",
                    productName = "Prepared dish",
                    category = OrderProductCategory.Food,
                    quantity = 1,
                    unitPriceCfa = 2_500,
                    photoEvidence = ProductPhotoEvidence.LiveCameraCapture(1_722_000_000_000),
                )
            ),
            deliveryDistanceKm = 3.0,
            paymentProvider = SequoPaymentProviders.YasTogo,
            paymentReference = "payment-delivery-notification",
        )

    private fun validatedPayment(request: PaymentValidationRequest): PaymentValidationResult =
        PaymentValidationResult.validated(request.paymentReference)
}
