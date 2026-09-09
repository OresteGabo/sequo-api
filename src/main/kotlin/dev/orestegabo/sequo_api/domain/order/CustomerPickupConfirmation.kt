package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderStatus
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutAccrualCommand
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import dev.orestegabo.sequo_api.domain.settlement.SettlementWorkflowType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ConfirmCustomerPickupCommand(
    val orderId: String,
    val customerId: String,
    val actorUserId: String,
    val idempotencyKey: String,
    val proofMetadata: String? = null,
    val confirmedAt: Instant = Instant.now(),
) {
    init {
        require(orderId.isNotBlank()) { "orderId cannot be blank." }
        require(customerId.isNotBlank()) { "customerId cannot be blank." }
        require(actorUserId.isNotBlank()) { "actorUserId cannot be blank." }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey cannot be blank." }
        require(idempotencyKey.length <= 255) { "idempotencyKey cannot exceed 255 characters." }
        require((proofMetadata?.length ?: 0) <= 1000) { "proofMetadata cannot exceed 1000 characters." }
    }
}

data class CustomerPickupConfirmationSnapshot(
    val id: String,
    val orderId: String,
    val customerId: String,
    val actorUserId: String,
    val idempotencyKey: String,
    val proofMetadata: String?,
    val confirmedAt: Instant,
    val orderStatus: CustomerOrderStatus,
    val returnWindowEndsAt: Instant?,
)

sealed class CustomerPickupConfirmationResult {
    data class Success(
        val confirmation: CustomerPickupConfirmationSnapshot,
    ) : CustomerPickupConfirmationResult()

    data class Rejected(
        val code: String,
        val message: String,
    ) : CustomerPickupConfirmationResult()
}

@Entity
@Table(name = "customer_pickup_confirmations")
class CustomerPickupConfirmationRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "order_id", nullable = false)
    val orderId: String,

    @Column(name = "customer_id", nullable = false)
    val customerId: String,

    @Column(name = "actor_user_id", nullable = false)
    val actorUserId: String,

    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @Column(name = "proof_metadata", length = 1000)
    val proofMetadata: String? = null,

    @Column(name = "confirmed_at", nullable = false)
    val confirmedAt: Instant,
)

interface CustomerPickupConfirmationRepository : JpaRepository<CustomerPickupConfirmationRecord, String> {
    fun findByOrderIdAndIdempotencyKey(orderId: String, idempotencyKey: String): CustomerPickupConfirmationRecord?

    fun findByOrderIdOrderByConfirmedAtAsc(orderId: String): List<CustomerPickupConfirmationRecord>
}

@Service
class CustomerPickupConfirmationService(
    private val orders: CustomerOrderRecordRepository,
    private val events: CustomerOrderEventRecordRepository,
    private val merchantSubOrders: MerchantSubOrderRepository,
    private val confirmations: CustomerPickupConfirmationRepository,
    private val settlements: SettlementPersistenceService,
) {
    @Transactional
    fun confirm(command: ConfirmCustomerPickupCommand): CustomerPickupConfirmationResult {
        val order = orders.findById(command.orderId).orElse(null)
            ?: return rejected("order_not_found", "Customer order was not found.")
        val existing = confirmations.findByOrderIdAndIdempotencyKey(command.orderId, command.idempotencyKey)
        if (existing != null) {
            return CustomerPickupConfirmationResult.Success(existing.toSnapshot(order))
        }
        if (order.customerId != command.customerId) {
            return rejected("customer_scope_mismatch", "This customer cannot confirm pickup for another customer order.")
        }
        if (order.route != OrderRoute.Pickup) {
            return rejected("not_pickup_order", "Customer pickup confirmation is only allowed for pickup orders.")
        }
        if (order.orderStatus == CustomerOrderStatus.DELIVERED) {
            return rejected("pickup_already_confirmed", "This pickup order is already delivered.")
        }
        val subOrders = merchantSubOrders.findByOrderId(order.id)
        if (subOrders.isEmpty()) {
            return rejected("missing_merchant_sub_orders", "Pickup confirmation requires merchant sub-orders.")
        }
        val notReady = subOrders.filter { it.status != MerchantSubOrderStatus.PACKED_READY }
        if (notReady.isNotEmpty()) {
            return rejected("pickup_not_ready", "All merchant packages must be ready before customer pickup.")
        }

        val returnWindowEndsAt = command.confirmedAt.plusSeconds(RETURN_WINDOW_SECONDS)
        order.orderStatus = CustomerOrderStatus.DELIVERED
        order.deliveredAt = command.confirmedAt
        order.returnWindowEndsAt = returnWindowEndsAt
        order.updatedAt = command.confirmedAt
        val savedOrder = orders.save(order)
        val confirmation = confirmations.save(
            CustomerPickupConfirmationRecord(
                orderId = order.id,
                customerId = order.customerId,
                actorUserId = command.actorUserId,
                idempotencyKey = command.idempotencyKey,
                proofMetadata = command.proofMetadata?.trim()?.takeIf { it.isNotEmpty() },
                confirmedAt = command.confirmedAt,
            )
        )
        val confirmationId = requireNotNull(confirmation.id) { "Persisted customer pickup confirmation id is required." }

        saveEvent(
            CustomerOrderEventRecord(
                id = "${order.id}:delivered:customer-pickup",
                orderId = order.id,
                eventType = CustomerOrderEventType.DELIVERED,
                sourceType = "CUSTOMER_PICKUP",
                sourceId = confirmationId,
                actorUserId = command.actorUserId,
                metadata = "Customer picked up the order from the seller.",
                createdAt = command.confirmedAt,
            )
        )
        saveEvent(
            CustomerOrderEventRecord(
                id = "${order.id}:return-window:customer-pickup",
                orderId = order.id,
                eventType = CustomerOrderEventType.RETURN_WINDOW_OPENED,
                sourceType = "CUSTOMER_PICKUP",
                sourceId = confirmationId,
                actorUserId = command.actorUserId,
                metadata = "Return window ends at $returnWindowEndsAt.",
                createdAt = command.confirmedAt,
            )
        )
        accrueMerchantPayouts(savedOrder, command.confirmedAt)
        return CustomerPickupConfirmationResult.Success(confirmation.toSnapshot(savedOrder))
    }

    @Transactional(readOnly = true)
    fun listForOrder(orderId: String, customerId: String): List<CustomerPickupConfirmationSnapshot> {
        require(orderId.isNotBlank()) { "orderId cannot be blank." }
        require(customerId.isNotBlank()) { "customerId cannot be blank." }
        val order = orders.findById(orderId).orElse(null) ?: return emptyList()
        require(order.customerId == customerId) { "This customer cannot read another customer's pickup confirmations." }
        return confirmations.findByOrderIdOrderByConfirmedAtAsc(orderId).map { it.toSnapshot(order) }
    }

    private fun saveEvent(event: CustomerOrderEventRecord) {
        if (!events.existsById(event.id)) events.save(event)
    }

    private fun accrueMerchantPayouts(order: CustomerOrderRecord, deliveredAt: Instant) {
        merchantSubOrders.findByOrderId(order.id).forEach { subOrder ->
            val subOrderId = checkNotNull(subOrder.id) { "Persisted merchant sub-order id is required for settlement." }
            settlements.accrueMerchantPayout(
                MerchantPayoutAccrualCommand(
                    accrualId = "${order.id}:merchant-payout:$subOrderId",
                    merchantId = subOrder.merchantId,
                    orderId = order.id,
                    sourceOrderItemId = subOrderId,
                    merchantNetCfa = subOrder.merchantNetCfa,
                    commissionCfa = subOrder.commissionCfa,
                    platformMarginCfa = 0,
                    packageReceivedAt = deliveredAt,
                    workflowType = SettlementWorkflowType.DeliveryConfirmed,
                    activeReturnHold = true,
                )
            )
        }
    }

    private fun rejected(code: String, message: String): CustomerPickupConfirmationResult.Rejected =
        CustomerPickupConfirmationResult.Rejected(code, message)

    private fun CustomerPickupConfirmationRecord.toSnapshot(order: CustomerOrderRecord): CustomerPickupConfirmationSnapshot =
        CustomerPickupConfirmationSnapshot(
            id = requireNotNull(id) { "Persisted customer pickup confirmation id is required." },
            orderId = orderId,
            customerId = customerId,
            actorUserId = actorUserId,
            idempotencyKey = idempotencyKey,
            proofMetadata = proofMetadata,
            confirmedAt = confirmedAt,
            orderStatus = order.orderStatus,
            returnWindowEndsAt = order.returnWindowEndsAt,
        )

    private companion object {
        const val RETURN_WINDOW_SECONDS = 72L * 60L * 60L
    }
}
