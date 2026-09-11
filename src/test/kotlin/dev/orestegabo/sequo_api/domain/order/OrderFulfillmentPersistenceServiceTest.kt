package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionConfigurationService
import dev.orestegabo.sequo_api.domain.delivery.CreateDeliveryMissionCommand
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionEvent
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordDestination
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordMode
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordStatus
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionService
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionServiceResult
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentService
import dev.orestegabo.sequo_api.domain.payment.PaymentProcessor
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationRequest
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationResult
import dev.orestegabo.sequo_api.domain.payment.SequoPaymentProviders
import dev.orestegabo.sequo_api.domain.payment.YasTogoPaymentMethod
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutStatus
import dev.orestegabo.sequo_api.domain.settlement.SettlementLedgerAccount
import dev.orestegabo.sequo_api.domain.settlement.SettlementLedgerDirection
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import dev.orestegabo.sequo_api.domain.settlement.SettlementSourceType
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
    private val lifecycle: OrderDeliveryLifecycleService,
    private val deliveryMissions: DeliveryMissionService,
    private val orders: CustomerOrderRecordRepository,
    private val lines: CustomerOrderLineRecordRepository,
    private val events: CustomerOrderEventRecordRepository,
    private val merchantSubOrders: MerchantSubOrderRepository,
    private val settlements: SettlementPersistenceService,
    private val commissions: MerchantCommissionConfigurationService,
    private val merchantFulfillment: MerchantFulfillmentService,
    private val customerPickup: CustomerPickupConfirmationService,
    private val orderController: OrderController,
) {
    @Test
    fun `accepted paid order is persisted and split into merchant sub-orders idempotently`() {
        val request = request()
        val accepted = acceptedOrder(request)

        val first = service.persistAcceptedOrder(request, accepted, Instant.parse("2026-09-08T10:00:00Z"))
        val second = service.persistAcceptedOrder(request, accepted, Instant.parse("2026-09-08T10:05:00Z"))

        assertTrue(orders.findByCheckoutId(request.checkoutId) != null)
        assertEquals(3, lines.findByOrderIdOrderByLineIndexAsc(accepted.order.orderId).size)
        assertEquals(2, merchantSubOrders.findByOrderId(accepted.order.orderId).size)
        assertEquals(first.order, second.order)
        assertEquals(first.lines.map { it.id }, second.lines.map { it.id })
        assertEquals(
            setOf("SQ-checkout-order-persistence:merchant-food", "SQ-checkout-order-persistence:merchant-grocery"),
            second.merchantSubOrders.map { it.subOrderCode }.toSet(),
        )
        assertEquals(8_000, second.merchantSubOrders.single { it.merchantId == "merchant-food" }.itemSubtotalCfa)
        assertEquals(3_000, second.merchantSubOrders.single { it.merchantId == "merchant-grocery" }.itemSubtotalCfa)

        val listed = service.listForCustomer(request.customerId)
        val detail = service.getForCustomer(accepted.order.orderId, request.customerId)
        assertEquals(listed.single().order.id, detail?.order?.id)
        assertEquals(3, detail?.lines?.size)
        assertEquals(2, detail?.merchantSubOrders?.size)
        assertEquals(listOf(CustomerOrderEventType.ACCEPTED_FOR_FULFILLMENT), detail?.timeline?.map { it.eventType })
        assertEquals(null, service.getForCustomer(accepted.order.orderId, "another-customer"))
    }

    @Test
    fun `accepted paid order snapshots configured merchant commission rate`() {
        commissions.upsertOverride(
            merchantId = "merchant-food",
            commissionRateBps = 500,
            updatedByUserId = "admin-order-persistence",
            reason = "Configured partner rate.",
        )
        val request = request(
            checkoutId = "checkout-order-persistence-commission",
            lines = listOf(foodLine()),
        )
        val accepted = acceptedOrder(request)

        val persisted = service.persistAcceptedOrder(
            request,
            accepted,
            Instant.parse("2026-09-08T10:00:00Z"),
        )

        persisted.merchantSubOrders.single().also {
            assertEquals("merchant-food", it.merchantId)
            assertEquals(500, it.commissionRateBps)
            assertEquals(250, it.commissionCfa)
            assertEquals(4_750, it.merchantNetCfa)
        }
    }

    @Test
    fun `pending payment is not persisted before provider validation`() {
        val processor = RegularOrderProcessor(
            paymentProcessor = PaymentProcessor(
                listOf(YasTogoPaymentMethod(validateHandler = { PaymentValidationResult.pending("Waiting provider callback.") }))
            ),
            pricingService = DeliveryPricingService(),
        )

        val pendingRequest = request(
            checkoutId = "checkout-order-persistence-pending",
            lines = listOf(foodLine()),
        )
        val result = processor.process(pendingRequest)

        assertTrue(result is OrderProcessingResult.AwaitingPaymentValidation)
        assertEquals(null, orders.findByCheckoutId(pendingRequest.checkoutId))
        assertEquals(0, lines.findByOrderIdOrderByLineIndexAsc("SQ-${pendingRequest.checkoutId}").size)
        assertEquals(0, merchantSubOrders.findByOrderId("SQ-${pendingRequest.checkoutId}").size)
    }

    @Test
    fun `customer pickup confirms ready pickup order idempotently and opens return window`() {
        val request = request(
            checkoutId = "checkout-order-pickup-confirmation",
            lines = listOf(foodLine()),
        ).copy(route = OrderRoute.Pickup, deliveryDistanceKm = 0.0)
        val persisted = service.persistAcceptedOrder(
            request,
            acceptedOrder(request),
            Instant.parse("2026-09-08T10:00:00Z"),
        )
        val subOrder = persisted.merchantSubOrders.single()
        merchantFulfillment.accept(subOrder.id, "merchant-food")
        merchantFulfillment.markPacked(subOrder.id, "merchant-food", packageCount = 1)

        val command = ConfirmCustomerPickupCommand(
            orderId = persisted.order.id,
            customerId = request.customerId,
            actorUserId = request.customerId,
            idempotencyKey = "pickup-confirmation-1",
            proofMetadata = "Seller counter handoff confirmed.",
            confirmedAt = Instant.parse("2026-09-09T12:00:00Z"),
        )
        val first = customerPickup.confirm(command)
        val second = customerPickup.confirm(command.copy(confirmedAt = Instant.parse("2026-09-09T12:05:00Z")))
        val order = orders.findById(persisted.order.id).orElseThrow()
        val payout = settlements.listMerchantPayouts("merchant-food").single { it.orderId == order.id }

        assertTrue(first is CustomerPickupConfirmationResult.Success)
        assertTrue(second is CustomerPickupConfirmationResult.Success)
        assertEquals(first.confirmation.id, second.confirmation.id)
        assertEquals(CustomerOrderStatus.DELIVERED, order.orderStatus)
        assertEquals(Instant.parse("2026-09-09T12:00:00Z"), order.deliveredAt)
        assertEquals(Instant.parse("2026-09-12T12:00:00Z"), order.returnWindowEndsAt)
        assertEquals(0, persisted.order.deliveryFeeCfa)
        assertEquals(4_250, payout.merchantNetCfa)
        assertEquals(750, payout.commissionCfa)
        assertEquals(2, payout.ledgerEntries.size)
        assertEquals(
            listOf(
                CustomerOrderEventType.ACCEPTED_FOR_FULFILLMENT,
                CustomerOrderEventType.DELIVERED,
                CustomerOrderEventType.RETURN_WINDOW_OPENED,
            ),
            events.findByOrderIdOrderByCreatedAtAsc(order.id).map { it.eventType },
        )
    }

    @Test
    fun `customer pickup is rejected before every merchant package is ready`() {
        val request = request(
            checkoutId = "checkout-order-pickup-not-ready",
            lines = listOf(foodLine()),
        ).copy(route = OrderRoute.Pickup, deliveryDistanceKm = 0.0)
        val persisted = service.persistAcceptedOrder(
            request,
            acceptedOrder(request),
            Instant.parse("2026-09-08T10:00:00Z"),
        )

        val result = customerPickup.confirm(
            ConfirmCustomerPickupCommand(
                orderId = persisted.order.id,
                customerId = request.customerId,
                actorUserId = request.customerId,
                idempotencyKey = "pickup-not-ready-1",
                confirmedAt = Instant.parse("2026-09-09T12:00:00Z"),
            )
        )

        assertTrue(result is CustomerPickupConfirmationResult.Rejected)
        assertEquals("pickup_not_ready", result.code)
        assertEquals(CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT, orders.findById(persisted.order.id).orElseThrow().orderStatus)
    }

    @Test
    fun `customer pickup endpoint confirms and scopes pickup confirmations to authenticated customer`() {
        val request = request(
            checkoutId = "checkout-order-pickup-controller",
            lines = listOf(foodLine()),
        ).copy(route = OrderRoute.Pickup, deliveryDistanceKm = 0.0)
        val persisted = service.persistAcceptedOrder(
            request,
            acceptedOrder(request),
            Instant.parse("2026-09-08T10:00:00Z"),
        )
        val subOrder = persisted.merchantSubOrders.single()
        merchantFulfillment.accept(subOrder.id, "merchant-food")
        merchantFulfillment.markPacked(subOrder.id, "merchant-food", packageCount = 1)

        val confirmation = orderController.confirmCustomerPickup(
            request.customerId,
            persisted.order.id,
            ConfirmCustomerPickupRequest(
                idempotencyKey = "pickup-controller-1",
                proofMetadata = "Customer collected at merchant counter.",
            ),
        )
        val ownRead = orderController.pickupConfirmations(request.customerId, persisted.order.id)
        val otherRead = orderController.pickupConfirmations("other-customer", persisted.order.id)

        assertEquals(org.springframework.http.HttpStatus.OK, confirmation.statusCode)
        assertEquals(
            listOf("pickup-controller-1"),
            ownRead.bodyAs<List<CustomerPickupConfirmationSnapshot>>().map { it.idempotencyKey },
        )
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, otherRead.statusCode)
    }

    @Test
    fun `delivery completion marks order delivered and opens return window with audit events`() {
        val request = request(lines = listOf(foodLine()))
        service.persistAcceptedOrder(request, acceptedOrder(request), Instant.parse("2026-09-08T10:00:00Z"))
        val mission = deliveryMissions.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "ORDER-LIFECYCLE-1",
                orderId = "SQ-checkout-order-persistence",
                deliveryMode = DeliveryMissionRecordMode.EXPRESS,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
                customerDeliveryFeeCfa = 450,
                courierFeeCfa = 700,
            )
        )
        deliveryMissions.assignCourier(mission.id, "courier-lifecycle")
        deliveryMissions.transition(mission.id, "admin-lifecycle", DeliveryMissionEvent.OfferToCourier)
        deliveryMissions.transition(mission.id, "courier-lifecycle", DeliveryMissionEvent.CourierAccepts)
        deliveryMissions.transition(mission.id, "courier-lifecycle", DeliveryMissionEvent.CourierPicksUpFromSeller, proof = "pickup")
        val delivered = deliveryMissions.transition(
            mission.id,
            "courier-lifecycle",
            DeliveryMissionEvent.CourierDeliversToCustomer,
            proof = "dropoff",
            at = Instant.parse("2026-09-09T12:00:00Z"),
        ) as DeliveryMissionServiceResult.Success

        val first = lifecycle.markDeliveredFromMission(delivered.mission, "courier-lifecycle")
        val second = lifecycle.markDeliveredFromMission(delivered.mission, "courier-lifecycle")
        val order = orders.findById("SQ-checkout-order-persistence").orElseThrow()
        val payout = settlements.listMerchantPayouts("merchant-food").single { it.orderId == order.id }

        assertEquals(CustomerOrderStatus.DELIVERED, first?.orderStatus)
        assertEquals(first, second)
        assertEquals(Instant.parse("2026-09-09T12:00:00Z"), order.deliveredAt)
        assertEquals(Instant.parse("2026-09-12T12:00:00Z"), order.returnWindowEndsAt)
        assertEquals(
            listOf(
                CustomerOrderEventType.ACCEPTED_FOR_FULFILLMENT,
                CustomerOrderEventType.DELIVERED,
                CustomerOrderEventType.RETURN_WINDOW_OPENED,
            ),
            events.findByOrderIdOrderByCreatedAtAsc(order.id).map { it.eventType },
        )
        assertEquals(DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, delivered.mission.status)
        assertEquals(MerchantPayoutStatus.HeldReturnWindow, payout.status)
        assertEquals(4_250, payout.merchantNetCfa)
        assertEquals(750, payout.commissionCfa)
        assertEquals(Instant.parse("2026-09-09T12:00:00Z"), payout.packageReceivedAt)
        assertEquals(Instant.parse("2026-09-12T12:00:00Z"), payout.payoutEligibleAt)
        assertTrue(payout.activeReturnHold)
        assertEquals(2, payout.ledgerEntries.size)
        val shortfallEntries = settlements.listLedgerEntries(SettlementSourceType.DeliveryMission, delivered.mission.id)
        assertEquals(1, shortfallEntries.size)
        assertEquals(SettlementLedgerAccount.SequoDeliveryShortfallExpense, shortfallEntries.single().account)
        assertEquals(SettlementLedgerDirection.Debit, shortfallEntries.single().direction)
        assertEquals(250, shortfallEntries.single().amountCfa)
        assertEquals("courier-lifecycle", shortfallEntries.single().courierId)
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

    private fun request(
        checkoutId: String = "checkout-order-persistence",
        lines: List<OrderLineRequest> = listOf(foodLine(), secondFoodLine(), groceryLine()),
    ) =
        OrderProcessingRequest(
            checkoutId = checkoutId,
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

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> org.springframework.http.ResponseEntity<Any>.bodyAs(): T =
        body as T
}
