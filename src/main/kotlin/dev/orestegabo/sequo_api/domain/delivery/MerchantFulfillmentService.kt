package dev.orestegabo.sequo_api.domain.delivery

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import dev.orestegabo.sequo_api.domain.notification.NotificationSeverity
import dev.orestegabo.sequo_api.domain.notification.NotificationWorkflowEvent
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
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
    val itemSubtotalCfa: Int,
    val commissionRateBps: Int,
    val commissionCfa: Int,
    val merchantNetCfa: Int,
    val packageCount: Int,
    val acceptedAt: Instant?,
    val preparingAt: Instant?,
    val packedReadyAt: Instant?,
    val handedToCourierAt: Instant?,
    val rejectionReason: String?,
    val sellerResponseDueAt: Instant?,
    val packingDueAt: Instant?,
    val updatedAt: Instant,
)

data class MerchantFulfillmentSlaSnapshot(
    val subOrderId: String,
    val status: MerchantSubOrderStatus,
    val sellerResponseDueAt: Instant?,
    val packingDueAt: Instant?,
    val overdue: Boolean,
    val overdueReason: String?,
)

data class MerchantFulfillmentSlaWarning(
    val subOrder: MerchantSubOrderSnapshot,
    val overdueReason: String,
    val evaluatedAt: Instant,
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
    private val escalations: MerchantFulfillmentEscalationRepository,
    private val orders: CustomerOrderRecordRepository,
    private val workflow: MerchantFulfillmentWorkflow,
    private val publisher: ApplicationEventPublisher,
    private val sellerResponseSla: Duration = Duration.ofHours(24),
    private val packingSla: Duration = Duration.ofHours(48),
) {
    private val objectMapper = ObjectMapper()

    init {
        require(!sellerResponseSla.isNegative && !sellerResponseSla.isZero) { "sellerResponseSla must be positive." }
        require(!packingSla.isNegative && !packingSla.isZero) { "packingSla must be positive." }
    }

    @Transactional
    fun create(
        command: CreateMerchantSubOrderCommand,
        createdAt: Instant = Instant.now(),
    ): MerchantSubOrderSnapshot {
        val now = createdAt
        val subOrder = MerchantSubOrder(
            subOrderCode = command.subOrderCode,
            orderId = command.orderId,
            merchantId = command.merchantId,
            itemSubtotalCfa = command.itemSubtotalCfa,
            commissionRateBps = command.commissionRateBps,
            commissionCfa = command.commissionCfa,
            merchantNetCfa = command.merchantNetCfa,
            sellerResponseDueAt = now.plus(sellerResponseSla),
            packingDueAt = now.plus(packingSla),
            createdAt = now,
            updatedAt = now,
        )

        return repository.save(subOrder).toSnapshot()
    }

    @Transactional(readOnly = true)
    fun findBySubOrderCode(subOrderCode: String): MerchantSubOrderSnapshot? {
        require(subOrderCode.isNotBlank()) { "subOrderCode cannot be blank." }
        return repository.findBySubOrderCode(subOrderCode)?.toSnapshot()
    }

    @Transactional(readOnly = true)
    fun get(subOrderId: String): MerchantSubOrderSnapshot? =
        repository.findById(subOrderId).orElse(null)?.toSnapshot()

    @Transactional(readOnly = true)
    fun listForMerchant(
        merchantId: String,
        statuses: Set<MerchantSubOrderStatus> = activeMerchantStatuses,
    ): List<MerchantSubOrderSnapshot> {
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        require(statuses.isNotEmpty()) { "At least one merchant sub-order status is required." }
        return repository.findByMerchantIdAndStatusInOrderByUpdatedAtDesc(merchantId, statuses)
            .map { it.toSnapshot() }
    }

    @Transactional(readOnly = true)
    fun listForOrder(orderId: String): List<MerchantSubOrderSnapshot> {
        require(orderId.isNotBlank()) { "orderId cannot be blank." }
        return repository.findByOrderId(orderId).map { it.toSnapshot() }
    }

    @Transactional(readOnly = true)
    fun sla(subOrderId: String, at: Instant = Instant.now()): MerchantFulfillmentSlaSnapshot? {
        val subOrder = repository.findById(subOrderId).orElse(null) ?: return null
        return subOrder.toSlaSnapshot(at)
    }

    @Transactional(readOnly = true)
    fun listEscalations(subOrderId: String): List<MerchantFulfillmentEscalationSnapshot> {
        require(subOrderId.isNotBlank()) { "subOrderId cannot be blank." }
        return escalations.findBySubOrderIdOrderByCreatedAtAsc(subOrderId)
            .map { it.toSnapshot() }
    }

    @Transactional
    fun escalate(
        subOrderId: String,
        actorUserId: String,
        reason: MerchantFulfillmentEscalationReason,
        note: String,
        createdAt: Instant = Instant.now(),
    ): MerchantFulfillmentEscalationSnapshot {
        require(subOrderId.isNotBlank()) { "subOrderId cannot be blank." }
        require(actorUserId.isNotBlank()) { "actorUserId cannot be blank." }
        require(note.isNotBlank()) { "Escalation note cannot be blank." }
        require(note.length <= 1000) { "Escalation note cannot exceed 1000 characters." }
        val subOrder = repository.findById(subOrderId).orElse(null)
            ?: throw IllegalArgumentException("Merchant sub-order was not found.")
        return recordEscalation(subOrder, actorUserId, reason, note, createdAt)
    }

    @Transactional
    fun publishOverdueSlaWarnings(
        evaluatedAt: Instant = Instant.now(),
        limit: Int = 100,
    ): List<MerchantFulfillmentSlaWarning> {
        require(limit in 1..200) { "SLA warning scan limit must be between 1 and 200." }
        return repository.findTop200ByStatusInOrderByUpdatedAtAsc(slaWarningStatuses)
            .asSequence()
            .take(limit)
            .mapNotNull { subOrder ->
                val sla = subOrder.toSlaSnapshot(evaluatedAt)
                val reason = sla.overdueReason ?: return@mapNotNull null
                if (publishSlaWarningEvent(subOrder, reason, evaluatedAt)) {
                    recordEscalation(
                        subOrder = subOrder,
                        actorUserId = MERCHANT_SLA_SCHEDULER_USER_ID,
                        reason = reason.toEscalationReason(),
                        note = reason.escalationNote(),
                        createdAt = evaluatedAt,
                    )
                    MerchantFulfillmentSlaWarning(subOrder.toSnapshot(), reason, evaluatedAt)
                } else {
                    null
                }
            }
            .toList()
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

        val saved = repository.save(subOrder)
        publishFulfillmentEvent(saved, event)

        return MerchantFulfillmentServiceResult.Success(
            subOrder = saved.toSnapshot(),
            message = transition.reason,
        )
    }

    private fun publishFulfillmentEvent(
        subOrder: MerchantSubOrder,
        event: MerchantFulfillmentEvent,
    ) {
        val eventType = when (event) {
            MerchantFulfillmentEvent.SellerAccepts -> NotificationEventType.MERCHANT_ACCEPTED_ORDER
            MerchantFulfillmentEvent.SellerRejects -> NotificationEventType.MERCHANT_REJECTED_ORDER
            MerchantFulfillmentEvent.SellerStartsPreparing -> NotificationEventType.ORDER_PREPARING
            MerchantFulfillmentEvent.SellerMarksPacked -> NotificationEventType.ORDER_READY_FOR_PICKUP
            MerchantFulfillmentEvent.CourierCollectsPackage,
            MerchantFulfillmentEvent.CustomerCancels -> return
        }
        val order = orders.findById(subOrder.orderId).orElse(null) ?: return
        val subOrderId = requireNotNull(subOrder.id)
        publisher.publishEvent(
            NotificationWorkflowEvent(
                eventId = "$subOrderId:merchant-fulfillment:${event.name}",
                eventType = eventType,
                aggregateType = "MERCHANT_SUB_ORDER",
                aggregateId = subOrderId,
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "customerUserId" to order.customerId,
                        "merchantUserIds" to listOf(subOrder.merchantId),
                        "title" to eventType.merchantFulfillmentTitle(),
                        "body" to eventType.merchantFulfillmentBody(),
                        "actionUrl" to "/merchant/sub-orders/$subOrderId",
                        "severity" to if (eventType == NotificationEventType.ORDER_READY_FOR_PICKUP) {
                            NotificationSeverity.ACTION_REQUIRED.name
                        } else {
                            NotificationSeverity.INFO.name
                        },
                        "messagePayload" to mapOf(
                            "subOrderId" to subOrderId,
                            "subOrderCode" to subOrder.subOrderCode,
                            "orderId" to subOrder.orderId,
                            "merchantId" to subOrder.merchantId,
                            "status" to subOrder.status.name,
                            "rejectionReason" to subOrder.rejectionReason,
                        ),
                    )
                ),
            )
        )
    }

    private fun publishSlaWarningEvent(
        subOrder: MerchantSubOrder,
        overdueReason: String,
        evaluatedAt: Instant,
    ): Boolean {
        val order = orders.findById(subOrder.orderId).orElse(null) ?: return false
        val subOrderId = requireNotNull(subOrder.id)
        publisher.publishEvent(
            NotificationWorkflowEvent(
                eventId = "$subOrderId:merchant-sla:$overdueReason",
                eventType = NotificationEventType.MERCHANT_SLA_WARNING,
                aggregateType = "MERCHANT_SUB_ORDER",
                aggregateId = subOrderId,
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "customerUserId" to order.customerId,
                        "merchantUserIds" to listOf(subOrder.merchantId),
                        "title" to "Seller delay warning",
                        "body" to overdueReason.warningBody(),
                        "actionUrl" to "/merchant/sub-orders/$subOrderId",
                        "severity" to NotificationSeverity.ACTION_REQUIRED.name,
                        "messagePayload" to mapOf(
                            "subOrderId" to subOrderId,
                            "subOrderCode" to subOrder.subOrderCode,
                            "orderId" to subOrder.orderId,
                            "merchantId" to subOrder.merchantId,
                            "status" to subOrder.status.name,
                            "overdueReason" to overdueReason,
                            "evaluatedAt" to evaluatedAt.toString(),
                        ),
                    )
                ),
            )
        )
        return true
    }

    private fun recordEscalation(
        subOrder: MerchantSubOrder,
        actorUserId: String,
        reason: MerchantFulfillmentEscalationReason,
        note: String,
        createdAt: Instant,
    ): MerchantFulfillmentEscalationSnapshot {
        val subOrderId = requireNotNull(subOrder.id)
        return escalations.findBySubOrderIdAndReason(subOrderId, reason)?.toSnapshot()
            ?: escalations.save(
                MerchantFulfillmentEscalationRecord(
                    subOrderId = subOrderId,
                    actorUserId = actorUserId,
                    reason = reason,
                    note = note.trim(),
                    createdAt = createdAt,
                )
            ).toSnapshot()
    }

    companion object {
        const val MERCHANT_SLA_SCHEDULER_USER_ID = "merchant-sla-scheduler"

        val activeMerchantStatuses = setOf(
            MerchantSubOrderStatus.MERCHANT_PENDING,
            MerchantSubOrderStatus.ACCEPTED,
            MerchantSubOrderStatus.PREPARING,
            MerchantSubOrderStatus.PACKED_READY,
        )
        val slaWarningStatuses = setOf(
            MerchantSubOrderStatus.MERCHANT_PENDING,
            MerchantSubOrderStatus.ACCEPTED,
            MerchantSubOrderStatus.PREPARING,
        )
    }
}

private fun MerchantSubOrder.toSlaSnapshot(at: Instant): MerchantFulfillmentSlaSnapshot {
    val responseOverdue = status == MerchantSubOrderStatus.MERCHANT_PENDING &&
        sellerResponseDueAt?.isBefore(at) == true
    val packingOverdue = status in setOf(
        MerchantSubOrderStatus.ACCEPTED,
        MerchantSubOrderStatus.PREPARING,
    ) && packingDueAt?.isBefore(at) == true
    return MerchantFulfillmentSlaSnapshot(
        subOrderId = requireNotNull(id),
        status = status,
        sellerResponseDueAt = sellerResponseDueAt,
        packingDueAt = packingDueAt,
        overdue = responseOverdue || packingOverdue,
        overdueReason = when {
            responseOverdue -> "seller_response_sla_exceeded"
            packingOverdue -> "packing_sla_exceeded"
            else -> null
        },
    )
}

private fun String.warningBody(): String =
    when (this) {
        "seller_response_sla_exceeded" -> "The seller has not accepted or rejected the paid order before the response deadline."
        "packing_sla_exceeded" -> "The seller has not marked the accepted package ready before the packing deadline."
        else -> "A seller fulfillment deadline needs attention."
    }

private fun String.escalationNote(): String =
    when (this) {
        "seller_response_sla_exceeded" -> "Automatic support escalation: seller response deadline was exceeded."
        "packing_sla_exceeded" -> "Automatic support escalation: seller packing deadline was exceeded."
        else -> "Automatic support escalation: seller fulfillment deadline needs attention."
    }

private fun String.toEscalationReason(): MerchantFulfillmentEscalationReason =
    when (this) {
        "seller_response_sla_exceeded" -> MerchantFulfillmentEscalationReason.SELLER_RESPONSE_SLA_EXCEEDED
        "packing_sla_exceeded" -> MerchantFulfillmentEscalationReason.PACKING_SLA_EXCEEDED
        else -> MerchantFulfillmentEscalationReason.MANUAL_SUPPORT_REVIEW
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

private fun NotificationEventType.merchantFulfillmentTitle(): String =
    when (this) {
        NotificationEventType.MERCHANT_ACCEPTED_ORDER -> "Seller accepted the order"
        NotificationEventType.MERCHANT_REJECTED_ORDER -> "Seller rejected the order"
        NotificationEventType.ORDER_PREPARING -> "Order preparation started"
        NotificationEventType.ORDER_READY_FOR_PICKUP -> "Order ready for pickup"
        else -> "Merchant fulfillment updated"
    }

private fun NotificationEventType.merchantFulfillmentBody(): String =
    when (this) {
        NotificationEventType.MERCHANT_ACCEPTED_ORDER -> "The seller accepted the paid order."
        NotificationEventType.MERCHANT_REJECTED_ORDER -> "The seller rejected the order and support follow-up may be needed."
        NotificationEventType.ORDER_PREPARING -> "The seller started preparing the package."
        NotificationEventType.ORDER_READY_FOR_PICKUP -> "The seller marked the package ready for pickup."
        else -> "Merchant fulfillment was updated."
    }

private fun MerchantSubOrder.toSnapshot(): MerchantSubOrderSnapshot =
    MerchantSubOrderSnapshot(
        id = requireNotNull(id) { "Persisted merchant sub-order id is required." },
        subOrderCode = subOrderCode,
        orderId = orderId,
        merchantId = merchantId,
        status = status,
        itemSubtotalCfa = itemSubtotalCfa,
        commissionRateBps = commissionRateBps,
        commissionCfa = commissionCfa,
        merchantNetCfa = merchantNetCfa,
        packageCount = packageCount,
        acceptedAt = acceptedAt,
        preparingAt = preparingAt,
        packedReadyAt = packedReadyAt,
        handedToCourierAt = handedToCourierAt,
        rejectionReason = rejectionReason,
        sellerResponseDueAt = sellerResponseDueAt,
        packingDueAt = packingDueAt,
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
