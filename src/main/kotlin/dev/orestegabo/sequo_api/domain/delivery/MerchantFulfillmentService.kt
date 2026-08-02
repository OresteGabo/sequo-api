package dev.orestegabo.sequo_api.domain.delivery

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class CreateMerchantSubOrderCommand(
    val subOrderCode: String,
    val orderId: String,
    val merchantId: String,
    val itemSubtotalCfa: Int,
    val commissionRateBps: Int = 1500,
    val commissionCfa: Int = 0,
    val merchantNetCfa: Int = itemSubtotalCfa - commissionCfa,
) {
    init {
        require(subOrderCode.isNotBlank()) { "subOrderCode cannot be blank." }
        require(orderId.isNotBlank()) { "orderId cannot be blank." }
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        require(itemSubtotalCfa >= 0) { "itemSubtotalCfa must be non-negative." }
        require(commissionRateBps in 500..1500) { "commissionRateBps must be between 500 and 1500." }
        require(commissionCfa >= 0) { "commissionCfa must be non-negative." }
        require(merchantNetCfa >= 0) { "merchantNetCfa must be non-negative." }
    }
}

data class MerchantSubOrderSnapshot(
    val id: String,
    val subOrderCode: String,
    val orderId: String,
    val merchantId: String,
    val status: MerchantSubOrderStatus,
    val packageCount: Int,
    val acceptedAt: Instant?,
    val preparingAt: Instant?,
    val packedReadyAt: Instant?,
    val handedToCourierAt: Instant?,
    val rejectionReason: String?,
    val updatedAt: Instant,
)

sealed class MerchantFulfillmentServiceResult {
    data class Success(
        val subOrder: MerchantSubOrderSnapshot,
        val message: String,
    ) : MerchantFulfillmentServiceResult()

    data class Rejected(
        val code: String,
        val message: String,
    ) : MerchantFulfillmentServiceResult()
}

@Service
class MerchantFulfillmentService(
    private val repository: MerchantSubOrderRepository,
    private val workflow: MerchantFulfillmentWorkflow,
) {
    @Transactional
    fun create(command: CreateMerchantSubOrderCommand): MerchantSubOrderSnapshot {
        val now = Instant.now()
        val subOrder = MerchantSubOrder(
            subOrderCode = command.subOrderCode,
            orderId = command.orderId,
            merchantId = command.merchantId,
            itemSubtotalCfa = command.itemSubtotalCfa,
            commissionRateBps = command.commissionRateBps,
            commissionCfa = command.commissionCfa,
            merchantNetCfa = command.merchantNetCfa,
            createdAt = now,
            updatedAt = now,
        )

        return repository.save(subOrder).toSnapshot()
    }

    @Transactional
    fun accept(
        subOrderId: String,
        merchantId: String,
        occurredAt: Instant = Instant.now(),
    ): MerchantFulfillmentServiceResult =
        transition(
            subOrderId = subOrderId,
            merchantId = merchantId,
            event = MerchantFulfillmentEvent.SellerAccepts,
            occurredAt = occurredAt,
        )

    @Transactional
    fun startPreparation(
        subOrderId: String,
        merchantId: String,
        occurredAt: Instant = Instant.now(),
    ): MerchantFulfillmentServiceResult =
        transition(
            subOrderId = subOrderId,
            merchantId = merchantId,
            event = MerchantFulfillmentEvent.SellerStartsPreparing,
            occurredAt = occurredAt,
        )

    @Transactional
    fun markPacked(
        subOrderId: String,
        merchantId: String,
        packageCount: Int,
        occurredAt: Instant = Instant.now(),
    ): MerchantFulfillmentServiceResult =
        transition(
            subOrderId = subOrderId,
            merchantId = merchantId,
            event = MerchantFulfillmentEvent.SellerMarksPacked,
            packageCount = packageCount,
            occurredAt = occurredAt,
        )

    @Transactional
    fun confirmCourierHandoff(
        subOrderId: String,
        merchantId: String,
        occurredAt: Instant = Instant.now(),
    ): MerchantFulfillmentServiceResult =
        transition(
            subOrderId = subOrderId,
            merchantId = merchantId,
            event = MerchantFulfillmentEvent.CourierCollectsPackage,
            occurredAt = occurredAt,
        )

    @Transactional
    fun reject(
        subOrderId: String,
        merchantId: String,
        reason: String,
        occurredAt: Instant = Instant.now(),
    ): MerchantFulfillmentServiceResult {
        if (reason.isBlank()) {
            return MerchantFulfillmentServiceResult.Rejected(
                code = "missing_rejection_reason",
                message = "Seller rejection requires a reason.",
            )
        }
        return transition(
            subOrderId = subOrderId,
            merchantId = merchantId,
            event = MerchantFulfillmentEvent.SellerRejects,
            occurredAt = occurredAt,
            rejectionReason = reason,
        )
    }

    private fun transition(
        subOrderId: String,
        merchantId: String,
        event: MerchantFulfillmentEvent,
        packageCount: Int = 0,
        occurredAt: Instant,
        rejectionReason: String? = null,
    ): MerchantFulfillmentServiceResult {
        val subOrder = repository.findById(subOrderId).orElse(null)
            ?: return MerchantFulfillmentServiceResult.Rejected(
                code = "merchant_sub_order_not_found",
                message = "Merchant sub-order was not found.",
            )
        if (subOrder.merchantId != merchantId) {
            return MerchantFulfillmentServiceResult.Rejected(
                code = "merchant_scope_mismatch",
                message = "This merchant cannot update another merchant's sub-order.",
            )
        }

        val transition = workflow.transition(
            MerchantFulfillmentTransitionRequest(
                currentStatus = subOrder.status.toWorkflowStatus(),
                event = event,
                packageCount = packageCount,
            )
        )
        if (!transition.accepted) {
            return MerchantFulfillmentServiceResult.Rejected(
                code = "invalid_merchant_fulfillment_transition",
                message = transition.reason,
            )
        }

        subOrder.applyTransition(
            nextStatus = transition.nextStatus.toRecordStatus(),
            event = event,
            packageCount = packageCount,
            occurredAt = occurredAt,
            rejectionReason = rejectionReason,
        )

        return MerchantFulfillmentServiceResult.Success(
            subOrder = repository.save(subOrder).toSnapshot(),
            message = transition.reason,
        )
    }
}

private fun MerchantSubOrder.applyTransition(
    nextStatus: MerchantSubOrderStatus,
    event: MerchantFulfillmentEvent,
    packageCount: Int,
    occurredAt: Instant,
    rejectionReason: String?,
) {
    status = nextStatus
    updatedAt = occurredAt
    when (event) {
        MerchantFulfillmentEvent.SellerAccepts -> acceptedAt = occurredAt
        MerchantFulfillmentEvent.SellerStartsPreparing -> preparingAt = occurredAt
        MerchantFulfillmentEvent.SellerMarksPacked -> {
            this.packageCount = packageCount
            packedReadyAt = occurredAt
        }
        MerchantFulfillmentEvent.CourierCollectsPackage -> handedToCourierAt = occurredAt
        MerchantFulfillmentEvent.SellerRejects -> this.rejectionReason = rejectionReason
        MerchantFulfillmentEvent.CustomerCancels -> Unit
    }
}

private fun MerchantSubOrder.toSnapshot(): MerchantSubOrderSnapshot =
    MerchantSubOrderSnapshot(
        id = requireNotNull(id) { "Persisted merchant sub-order id is required." },
        subOrderCode = subOrderCode,
        orderId = orderId,
        merchantId = merchantId,
        status = status,
        packageCount = packageCount,
        acceptedAt = acceptedAt,
        preparingAt = preparingAt,
        packedReadyAt = packedReadyAt,
        handedToCourierAt = handedToCourierAt,
        rejectionReason = rejectionReason,
        updatedAt = updatedAt,
    )

private fun MerchantSubOrderStatus.toWorkflowStatus(): MerchantFulfillmentStatus =
    when (this) {
        MerchantSubOrderStatus.MERCHANT_PENDING -> MerchantFulfillmentStatus.AwaitingSellerAcceptance
        MerchantSubOrderStatus.ACCEPTED -> MerchantFulfillmentStatus.AcceptedBySeller
        MerchantSubOrderStatus.PREPARING -> MerchantFulfillmentStatus.Preparing
        MerchantSubOrderStatus.PACKED_READY -> MerchantFulfillmentStatus.PackedReadyForPickup
        MerchantSubOrderStatus.HANDED_TO_COURIER -> MerchantFulfillmentStatus.HandedToCourier
        MerchantSubOrderStatus.REJECTED -> MerchantFulfillmentStatus.RejectedBySeller
        MerchantSubOrderStatus.CANCELLED -> MerchantFulfillmentStatus.Cancelled
    }

private fun MerchantFulfillmentStatus.toRecordStatus(): MerchantSubOrderStatus =
    when (this) {
        MerchantFulfillmentStatus.AwaitingSellerAcceptance -> MerchantSubOrderStatus.MERCHANT_PENDING
        MerchantFulfillmentStatus.AcceptedBySeller -> MerchantSubOrderStatus.ACCEPTED
        MerchantFulfillmentStatus.Preparing -> MerchantSubOrderStatus.PREPARING
        MerchantFulfillmentStatus.PackedReadyForPickup -> MerchantSubOrderStatus.PACKED_READY
        MerchantFulfillmentStatus.HandedToCourier -> MerchantSubOrderStatus.HANDED_TO_COURIER
        MerchantFulfillmentStatus.RejectedBySeller -> MerchantSubOrderStatus.REJECTED
        MerchantFulfillmentStatus.Cancelled -> MerchantSubOrderStatus.CANCELLED
    }
