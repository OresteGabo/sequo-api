package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.order.OrderFlowStage
import dev.orestegabo.sequo_api.domain.order.OrderFlowStepStatus
import dev.orestegabo.sequo_api.domain.order.OrderFulfillmentPersistenceService
import dev.orestegabo.sequo_api.domain.order.OrderLineRequest
import dev.orestegabo.sequo_api.domain.order.OrderProcessingRequest
import dev.orestegabo.sequo_api.domain.order.OrderProcessingResult
import dev.orestegabo.sequo_api.domain.order.OrderProductCategory
import dev.orestegabo.sequo_api.domain.order.OrderRoute
import dev.orestegabo.sequo_api.domain.order.OrderServiceLevel
import dev.orestegabo.sequo_api.domain.order.ProcessedOrder
import dev.orestegabo.sequo_api.domain.order.ProductPhotoEvidence
import dev.orestegabo.sequo_api.domain.order.FulfillmentPlan
import dev.orestegabo.sequo_api.domain.order.FulfillmentPriority
import dev.orestegabo.sequo_api.domain.order.OrderConfirmation
import dev.orestegabo.sequo_api.domain.order.OrderPricingSnapshot
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationResult
import dev.orestegabo.sequo_api.domain.payment.SequoPaymentProviders
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingBreakdown
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class DeliveryReadinessDispatchServiceTest @Autowired constructor(
    private val fulfillmentPersistence: OrderFulfillmentPersistenceService,
    private val merchantFulfillment: MerchantFulfillmentService,
    private val dispatch: DeliveryReadinessDispatchService,
    private val missions: DeliveryMissionRepository,
) {
    @Test
    fun `packed merchant sub-order creates one idempotent delivery mission`() {
        val request = orderRequest(route = OrderRoute.FastDelivery)
        val fulfillment = fulfillmentPersistence.persistAcceptedOrder(request, accepted(request))
        val subOrder = fulfillment.merchantSubOrders.single()
        merchantFulfillment.accept(subOrder.id, subOrder.merchantId)
        merchantFulfillment.startPreparation(subOrder.id, subOrder.merchantId)
        merchantFulfillment.markPacked(subOrder.id, subOrder.merchantId, packageCount = 1)

        val first = dispatch.dispatchReadySubOrders(at = Instant.parse("2026-09-08T11:00:00Z"))
        val second = dispatch.dispatchReadySubOrders(at = Instant.parse("2026-09-08T11:05:00Z"))

        assertEquals(1, first.createdMissions.size)
        assertEquals(DeliveryMissionRecordDestination.CUSTOMER_ADDRESS, first.createdMissions.single().destinationType)
        assertEquals(DeliveryMissionRecordMode.EXPRESS, first.createdMissions.single().deliveryMode)
        assertEquals(0, second.createdMissions.size)
        assertEquals(listOf(first.createdMissions.single().id), second.existingMissions.map { it.id })
        assertEquals(1, missions.findAll().count { it.merchantSubOrderId == subOrder.id })
    }

    @Test
    fun `pickup route is skipped because no courier mission is required`() {
        val request = orderRequest(route = OrderRoute.Pickup, checkoutId = "checkout-pickup-dispatch")
        val fulfillment = fulfillmentPersistence.persistAcceptedOrder(request, accepted(request))
        val subOrder = fulfillment.merchantSubOrders.single()
        merchantFulfillment.accept(subOrder.id, subOrder.merchantId)
        merchantFulfillment.startPreparation(subOrder.id, subOrder.merchantId)
        merchantFulfillment.markPacked(subOrder.id, subOrder.merchantId, packageCount = 1)

        val result = dispatch.dispatchReadySubOrders()

        assertEquals(0, result.createdMissions.size)
        assertEquals(listOf("customer_pickup_does_not_need_courier_mission"), result.skippedSubOrders.map { it.reason })
    }

    private fun accepted(request: OrderProcessingRequest) = OrderProcessingResult.AcceptedForFulfillment(
        order = ProcessedOrder(
            orderId = "SQ-${request.checkoutId}",
            checkoutId = request.checkoutId,
            serviceLevel = request.serviceLevel,
            pricing = OrderPricingSnapshot(
                itemSubtotalCfa = request.lines.sumOf { it.effectiveUnitPriceCfa * it.quantity },
                delivery = DeliveryPricingBreakdown(
                    billableKm = 5,
                    baseFeeCfa = 500,
                    subscriptionDiscountCfa = 0,
                    referralCreditAppliedCfa = 0,
                    finalDeliveryFeeCfa = if (request.route == OrderRoute.Pickup) 0 else 500,
                ),
                totalCfa = request.lines.sumOf { it.effectiveUnitPriceCfa * it.quantity } +
                    if (request.route == OrderRoute.Pickup) 0 else 500,
            ),
            payment = PaymentValidationResult.validated("validated-${request.checkoutId}"),
            fulfillmentPlan = FulfillmentPlan(
                route = request.route,
                serviceLevel = request.serviceLevel,
                priority = FulfillmentPriority.Standard,
                requiresConsolidation = false,
                customerFacingStatus = "Paid, waiting for seller preparation",
            ),
            confirmation = OrderConfirmation("Paid", "Order paid."),
        ),
        events = listOf(OrderFlowStage.ProcessPayment.let { stage ->
            dev.orestegabo.sequo_api.domain.order.OrderFlowEvent(stage, OrderFlowStepStatus.Passed, "Payment validated.")
        }),
    )

    private fun orderRequest(
        route: OrderRoute,
        checkoutId: String = "checkout-dispatch",
    ) = OrderProcessingRequest(
        checkoutId = checkoutId,
        customerId = "customer-dispatch",
        serviceLevel = OrderServiceLevel.Regular,
        route = route,
        lines = listOf(
            OrderLineRequest(
                productId = "product-dispatch",
                sellerId = "merchant-dispatch",
                sellerName = "Merchant Dispatch",
                productName = "Prepared product",
                category = OrderProductCategory.Food,
                quantity = 1,
                unitPriceCfa = 2_000,
                photoEvidence = ProductPhotoEvidence.LiveCameraCapture(1_722_000_000_000),
            )
        ),
        deliveryDistanceKm = 4.0,
        paymentProvider = SequoPaymentProviders.YasTogo,
        paymentReference = "payment-dispatch",
    )
}
