package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.delivery.DeliveryMission
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordDestination
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordMode
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordStatus
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrder
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderStatus
import dev.orestegabo.sequo_api.domain.delivery.toSnapshot
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher

class HandoffReplayIntegrityTest {
    private val handoffAt = Instant.parse("2026-11-08T10:00:00Z")

    @Test
    fun `another customer cannot replay an existing pickup key`() {
        val fixture = Fixture(order(CustomerOrderStatus.DELIVERED, deliveredAt = handoffAt))
        fixture.records += fixture.confirmation()

        val result = fixture.pickup.confirm(command().copy(customerId = "other-customer", actorUserId = "other-customer"))

        assertEquals("customer_scope_mismatch", assertIs<CustomerPickupConfirmationResult.Rejected>(result).code)
        Mockito.verifyNoInteractions(fixture.confirmations, fixture.settlements)
        Mockito.verify(fixture.orders).lockForCompletion(fixture.order.id)
    }

    @Test
    fun `own pickup retries retain the first handoff after delivery return and refund`() {
        val fixture = Fixture(order())
        val first = assertIs<CustomerPickupConfirmationResult.Success>(fixture.pickup.confirm(command()))
        val originalWindow = handoffAt.plusSeconds(72 * 60 * 60)
        assertEquals(handoffAt, fixture.order.deliveredAt)
        assertEquals(originalWindow, fixture.order.returnWindowEndsAt)
        Mockito.clearInvocations(fixture.orders, fixture.events, fixture.settlements)

        listOf(CustomerOrderStatus.DELIVERED, CustomerOrderStatus.RETURN_REQUESTED, CustomerOrderStatus.REFUNDED).forEachIndexed { index, state ->
            fixture.order.orderStatus = state
            val retried = assertIs<CustomerPickupConfirmationResult.Success>(fixture.pickup.confirm(
                command().copy(idempotencyKey = "different-key-$index", confirmedAt = handoffAt.plusSeconds(86_400)),
            ))
            assertEquals(first.confirmation.id, retried.confirmation.id)
            assertEquals(handoffAt, retried.confirmation.confirmedAt)
            assertEquals(state, retried.confirmation.orderStatus)
            assertEquals(state, fixture.order.orderStatus)
            assertEquals(handoffAt, fixture.order.deliveredAt)
            assertEquals(originalWindow, fixture.order.returnWindowEndsAt)
        }
        assertEquals(1, fixture.records.size)
        Mockito.verify(fixture.orders, Mockito.never()).save(Mockito.any(CustomerOrderRecord::class.java))
        Mockito.verifyNoInteractions(fixture.events, fixture.settlements)
    }

    @Test
    fun `same pickup key also remains scoped and read only after a return`() {
        val fixture = Fixture(order(CustomerOrderStatus.RETURN_REQUESTED, deliveredAt = handoffAt))
        fixture.records += fixture.confirmation()
        val result = assertIs<CustomerPickupConfirmationResult.Success>(fixture.pickup.confirm(command().copy(confirmedAt = handoffAt.plusSeconds(86_400))))
        assertEquals(CustomerOrderStatus.RETURN_REQUESTED, result.confirmation.orderStatus)
        assertEquals(handoffAt, result.confirmation.confirmedAt)
        assertEquals(handoffAt, fixture.order.deliveredAt)
        Mockito.verifyNoInteractions(fixture.settlements, fixture.events)
    }

    @Test
    fun `first pickup requires paid active fulfillment and legacy delivery is not reset`() {
        listOf(
            order(payment = PaymentValidationStatus.Pending),
            order(CustomerOrderStatus.CANCELLED),
            order(CustomerOrderStatus.DELIVERED, deliveredAt = handoffAt),
            order(CustomerOrderStatus.REFUNDED, deliveredAt = handoffAt),
        ).forEach { current ->
            val fixture = Fixture(current)
            val before = listOf(current.orderStatus, current.deliveredAt, current.returnWindowEndsAt, current.updatedAt)
            assertIs<CustomerPickupConfirmationResult.Rejected>(fixture.pickup.confirm(command()))
            assertEquals(before, listOf(current.orderStatus, current.deliveredAt, current.returnWindowEndsAt, current.updatedAt))
            assertEquals(0, fixture.records.size)
            Mockito.verifyNoInteractions(fixture.settlements, fixture.events)
        }
    }

    @Test
    fun `mission replay keeps later return refund and cancellation status without financial side effects`() {
        listOf(CustomerOrderStatus.DELIVERED, CustomerOrderStatus.RETURN_REQUESTED,
            CustomerOrderStatus.REFUNDED, CustomerOrderStatus.CANCELLED).forEach { state ->
            val fixture = Fixture(order(state, deliveredAt = handoffAt, route = OrderRoute.FastDelivery))
            val result = fixture.lifecycle.markDeliveredFromMission(fixture.deliveredMission(), "courier-fixture", handoffAt.plusSeconds(86_400))
            assertEquals(state, result?.orderStatus)
            assertEquals(state, fixture.order.orderStatus)
            assertEquals(handoffAt, fixture.order.deliveredAt)
            assertEquals(handoffAt.plusSeconds(72 * 60 * 60), fixture.order.returnWindowEndsAt)
            Mockito.verify(fixture.orders, Mockito.never()).save(Mockito.any(CustomerOrderRecord::class.java))
            Mockito.verifyNoInteractions(fixture.settlements, fixture.events, fixture.publisher)
        }
    }

    @Test
    fun `cancelled or unpaid orders cannot gain a first delivered mission handoff`() {
        listOf(order(CustomerOrderStatus.CANCELLED, route = OrderRoute.FastDelivery),
            order(payment = PaymentValidationStatus.Pending, route = OrderRoute.FastDelivery)).forEach { current ->
            val fixture = Fixture(current)
            assertNull(fixture.lifecycle.markDeliveredFromMission(fixture.deliveredMission(), "courier-fixture"))
            assertNull(current.deliveredAt)
            assertNull(current.returnWindowEndsAt)
            Mockito.verifyNoInteractions(fixture.settlements, fixture.events, fixture.publisher)
        }
    }

    private fun command() = ConfirmCustomerPickupCommand("order-fixture", "customer-fixture", "customer-fixture", "original-key", confirmedAt = handoffAt)

    private fun order(
        status: CustomerOrderStatus = CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT,
        payment: PaymentValidationStatus = PaymentValidationStatus.Validated,
        deliveredAt: Instant? = null,
        route: OrderRoute = OrderRoute.Pickup,
    ) = CustomerOrderRecord(
        id = "order-fixture", checkoutId = "checkout-fixture", customerId = "customer-fixture",
        serviceLevel = OrderServiceLevel.Regular, route = route,
        fulfillmentPriority = FulfillmentPriority.Standard, requiresConsolidation = false,
        customerFacingStatus = "Fixture order", itemSubtotalCfa = 5_000, deliveryFeeCfa = 0, totalCfa = 5_000,
        paymentProvider = "yas_togo", paymentReference = "payment-fixture", providerReference = "provider-fixture",
        paymentStatus = payment, orderStatus = status, deliveredAt = deliveredAt,
        returnWindowEndsAt = deliveredAt?.plusSeconds(72 * 60 * 60),
        createdAt = handoffAt.minusSeconds(3_600), updatedAt = handoffAt,
    )

    private inner class Fixture(val order: CustomerOrderRecord) {
        val orders = Mockito.mock(CustomerOrderRecordRepository::class.java)
        val events = Mockito.mock(CustomerOrderEventRecordRepository::class.java)
        val subOrders = Mockito.mock(MerchantSubOrderRepository::class.java)
        val confirmations = Mockito.mock(CustomerPickupConfirmationRepository::class.java)
        val settlements = Mockito.mock(SettlementPersistenceService::class.java)
        val lines = Mockito.mock(CustomerOrderLineRecordRepository::class.java)
        val missions = Mockito.mock(DeliveryMissionRepository::class.java)
        val publisher = Mockito.mock(ApplicationEventPublisher::class.java)
        val records = mutableListOf<CustomerPickupConfirmationRecord>()
        val pickup = CustomerPickupConfirmationService(orders, events, subOrders, confirmations, settlements)
        val lifecycle = OrderDeliveryLifecycleService(orders, lines, events, subOrders, settlements, publisher, missions)

        init {
            Mockito.`when`(orders.lockForCompletion(order.id)).thenReturn(order)
            Mockito.`when`(orders.save(Mockito.any(CustomerOrderRecord::class.java))).thenAnswer { it.getArgument<CustomerOrderRecord>(0) }
            Mockito.`when`(subOrders.findByOrderId(order.id)).thenReturn(listOf(MerchantSubOrder(
                id = "part-fixture", subOrderCode = "part-code-fixture", orderId = order.id, merchantId = "merchant-fixture",
                status = MerchantSubOrderStatus.PACKED_READY, packageCount = 1,
                itemSubtotalCfa = 5_000, merchantNetCfa = 4_250, commissionCfa = 750,
            )))
            Mockito.`when`(confirmations.findByOrderIdAndIdempotencyKey(Mockito.anyString(), Mockito.anyString())).thenAnswer {
                val key = it.getArgument<String>(1)
                records.firstOrNull { record -> record.idempotencyKey == key }
            }
            Mockito.`when`(confirmations.findByOrderIdOrderByConfirmedAtAsc(order.id)).thenAnswer { records.toList() }
            Mockito.`when`(confirmations.save(Mockito.any(CustomerPickupConfirmationRecord::class.java))).thenAnswer {
                val proposed = it.getArgument<CustomerPickupConfirmationRecord>(0)
                CustomerPickupConfirmationRecord(
                    id = "confirmation-${records.size + 1}", orderId = proposed.orderId, customerId = proposed.customerId,
                    actorUserId = proposed.actorUserId, idempotencyKey = proposed.idempotencyKey,
                    proofMetadata = proposed.proofMetadata, confirmedAt = proposed.confirmedAt,
                ).also { saved -> records += saved }
            }
        }

        fun confirmation() = CustomerPickupConfirmationRecord(
            id = "legacy-confirmation", orderId = order.id, customerId = order.customerId,
            actorUserId = order.customerId, idempotencyKey = "original-key", confirmedAt = handoffAt,
        )

        fun deliveredMission() = DeliveryMission(
            id = "mission-fixture", deliveryCode = "mission-code-fixture", orderId = order.id, courierId = "courier-fixture",
            deliveryMode = DeliveryMissionRecordMode.STANDARD, destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            status = DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, deliveredAt = handoffAt,
            customerDeliveryFeeCfa = 400, courierFeeCfa = 600, createdAt = handoffAt.minusSeconds(3_600),
        ).toSnapshot()
    }
}
