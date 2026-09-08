package dev.orestegabo.sequo_api.domain.returns

import dev.orestegabo.sequo_api.domain.order.CustomerOrderLineRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.order.OrderProductCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "return_requests")
class ReturnRequestRecord(
    @Id val id: String,
    @Column(name = "order_id", nullable = false) val orderId: String,
    @Column(name = "customer_id", nullable = false) val customerId: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: ReturnStatus,
    @Column(nullable = false) val reason: String,
    @Column(name = "requested_refund_cfa", nullable = false) val requestedRefundCfa: Int,
    @Column(name = "return_pin_hash", nullable = false) val returnPinHash: String,
    @Column(name = "relay_point_id") var relayPointId: String? = null,
    @Column(name = "dropped_at") var droppedAt: Instant? = null,
    @Column(name = "received_by_sequo_at") var receivedBySequoAt: Instant? = null,
    @Column(name = "receiving_operator_id") var receivingOperatorId: String? = null,
    @Column(name = "condition_assessment") var conditionAssessment: String? = null,
    @Enumerated(EnumType.STRING) @Column var responsibility: RefundResponsibility? = null,
    @Column(name = "receipt_idempotency_key") var receiptIdempotencyKey: String? = null,
    @Column(name = "refund_idempotency_key") var refundIdempotencyKey: String? = null,
    @Column(name = "refund_amount_cfa") var refundAmountCfa: Int? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Version @Column(nullable = false) var version: Long = 0,
)

interface ReturnRequestRecordRepository : JpaRepository<ReturnRequestRecord, String> {
    fun findByCustomerIdOrderByCreatedAtDesc(customerId: String): List<ReturnRequestRecord>
    fun findByStatusOrderByUpdatedAtAsc(status: ReturnStatus): List<ReturnRequestRecord>
    fun findByOrderIdOrderByCreatedAtDesc(orderId: String): List<ReturnRequestRecord>
}

data class PersistedReturnRequestCommand(
    val returnId: String,
    val orderId: String,
    val customerId: String,
    val reason: String,
    val requestedRefundCfa: Int,
    val rawReturnPin: String,
    val requestedAt: Instant = Instant.now(),
    val productReturnable: Boolean = true,
    val merchantAllowsReturn: Boolean = true,
    val adminOverride: Boolean = false,
)

data class PersistedRelayDropoffCommand(
    val returnId: String,
    val relayPointId: String,
    val rawReturnPin: String,
    val droppedAt: Instant = Instant.now(),
)

data class PersistedPhysicalReceiptCommand(
    val returnId: String,
    val operatorId: String,
    val receivedAt: Instant = Instant.now(),
    val conditionAssessment: String,
    val responsibility: RefundResponsibility,
    val idempotencyKey: String,
)

data class PersistedRefundTriggerCommand(
    val returnId: String,
    val amountCfa: Int,
    val idempotencyKey: String,
)

data class ReturnRequestSnapshot(
    val id: String,
    val orderId: String,
    val customerId: String,
    val status: ReturnStatus,
    val reason: String,
    val requestedAt: Instant,
    val requestedRefundCfa: Int,
    val relayPointId: String?,
    val droppedAt: Instant?,
    val receivedBySequoAt: Instant?,
    val receivingOperatorId: String?,
    val conditionAssessment: String?,
    val responsibility: RefundResponsibility?,
    val refundAmountCfa: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

sealed class PersistedReturnResult {
    data class Accepted(val returnRequest: ReturnRequestSnapshot) : PersistedReturnResult()
    data class Rejected(val rejection: ReturnRejection) : PersistedReturnResult()
}

@Service
class ReturnPersistenceService(
    private val returns: ReturnRequestRecordRepository,
    private val orders: CustomerOrderRecordRepository,
    private val orderLines: CustomerOrderLineRecordRepository,
) {
    private val domain = ReturnProcessingService()

    @Transactional
    fun requestReturn(command: PersistedReturnRequestCommand): PersistedReturnResult {
        returns.findById(command.returnId).orElse(null)?.let { return accepted(it) }
        val order = orders.findById(command.orderId).orElse(null)
            ?: return rejected("order_not_found", "Order was not found.")
        val deliveredAt = order.deliveredAt
            ?: return rejected("order_not_delivered", "Only delivered orders can be returned.")
        if (command.requestedRefundCfa > order.itemSubtotalCfa) {
            return rejected("refund_amount_too_high", "Requested refund amount cannot exceed order item subtotal.")
        }

        val result = domain.requestReturn(
            ReturnRequestCommand(
                returnId = command.returnId,
                orderId = command.orderId,
                customerId = command.customerId,
                reason = command.reason,
                requestedRefundCfa = command.requestedRefundCfa,
                rawReturnPin = command.rawReturnPin,
                eligibility = ReturnEligibilityInput(
                    orderId = order.id,
                    orderOwnerId = order.customerId,
                    requesterCustomerId = command.customerId,
                    itemCategory = order.returnItemCategory(),
                    deliveredAt = deliveredAt,
                    requestedAt = command.requestedAt,
                    productReturnable = command.productReturnable,
                    merchantAllowsReturn = command.merchantAllowsReturn,
                    adminOverride = command.adminOverride,
                ),
            )
        )

        return when (result) {
            is ReturnProcessingResult.Accepted -> {
                val saved = returns.save(result.returnRequest.toRecord(command.reason, command.requestedAt))
                order.orderStatus = CustomerOrderStatus.RETURN_REQUESTED
                order.updatedAt = command.requestedAt
                orders.save(order)
                accepted(saved)
            }
            is ReturnProcessingResult.Rejected -> PersistedReturnResult.Rejected(result.rejection)
        }
    }

    @Transactional
    fun recordRelayDropoff(command: PersistedRelayDropoffCommand): PersistedReturnResult =
        withExisting(command.returnId) { record ->
            domain.recordRelayDropoff(
                RelayDropoffCommand(
                    returnRequest = record.toDomain(),
                    relayPointId = command.relayPointId,
                    rawReturnPin = command.rawReturnPin,
                    droppedAt = command.droppedAt,
                )
            ).persist(record, command.droppedAt)
        }

    @Transactional
    fun recordPhysicalReceipt(command: PersistedPhysicalReceiptCommand): PersistedReturnResult =
        withExisting(command.returnId) { record ->
            if (record.receiptIdempotencyKey == command.idempotencyKey && record.status == ReturnStatus.ReceivedBySequo) {
                return@withExisting accepted(record)
            }
            domain.recordPhysicalReceipt(
                PhysicalReceiptCommand(
                    returnRequest = record.toDomain(),
                    operatorId = command.operatorId,
                    receivedAt = command.receivedAt,
                    conditionAssessment = command.conditionAssessment,
                    responsibility = command.responsibility,
                    idempotencyKey = command.idempotencyKey,
                )
            ).persist(record, command.receivedAt)
        }

    @Transactional
    fun triggerRefund(command: PersistedRefundTriggerCommand): PersistedReturnResult =
        withExisting(command.returnId) { record ->
            if (record.refundIdempotencyKey == command.idempotencyKey && record.status == ReturnStatus.RefundPending) {
                return@withExisting accepted(record)
            }
            domain.triggerRefund(
                RefundTriggerCommand(
                    returnRequest = record.toDomain(),
                    amountCfa = command.amountCfa,
                    idempotencyKey = command.idempotencyKey,
                )
            ).persist(record, Instant.now(), refundAmountCfa = command.amountCfa)
        }

    @Transactional(readOnly = true)
    fun get(returnId: String): ReturnRequestSnapshot? =
        returns.findById(returnId).orElse(null)?.toSnapshot()

    @Transactional(readOnly = true)
    fun listForCustomer(customerId: String): List<ReturnRequestSnapshot> {
        require(customerId.isNotBlank()) { "customerId is required." }
        return returns.findByCustomerIdOrderByCreatedAtDesc(customerId).map { it.toSnapshot() }
    }

    @Transactional(readOnly = true)
    fun listByStatus(status: ReturnStatus): List<ReturnRequestSnapshot> =
        returns.findByStatusOrderByUpdatedAtAsc(status).map { it.toSnapshot() }

    @Transactional(readOnly = true)
    fun listForOrder(orderId: String): List<ReturnRequestSnapshot> {
        require(orderId.isNotBlank()) { "orderId is required." }
        return returns.findByOrderIdOrderByCreatedAtDesc(orderId).map { it.toSnapshot() }
    }

    private fun CustomerOrderRecord.returnItemCategory(): ReturnItemCategory {
        val categories = orderLines.findByOrderIdOrderByLineIndexAsc(id).map { it.category }.toSet()
        return when {
            categories.any { it == OrderProductCategory.Food } -> ReturnItemCategory.Food
            categories.any { it == OrderProductCategory.Perishable } -> ReturnItemCategory.Perishable
            else -> ReturnItemCategory.ReturnableGoods
        }
    }

    private fun withExisting(
        returnId: String,
        operation: (ReturnRequestRecord) -> PersistedReturnResult,
    ): PersistedReturnResult {
        if (returnId.isBlank()) return rejected("missing_return_id", "Return id is required.")
        val record = returns.findById(returnId).orElse(null)
            ?: return rejected("return_not_found", "Return request was not found.")
        return operation(record)
    }

    private fun ReturnProcessingResult.persist(
        record: ReturnRequestRecord,
        updatedAt: Instant,
        refundAmountCfa: Int? = null,
    ): PersistedReturnResult = when (this) {
        is ReturnProcessingResult.Accepted -> {
            record.apply(returnRequest, updatedAt, refundAmountCfa)
            accepted(returns.save(record))
        }
        is ReturnProcessingResult.Rejected -> PersistedReturnResult.Rejected(rejection)
    }

    private fun accepted(record: ReturnRequestRecord) = PersistedReturnResult.Accepted(record.toSnapshot())

    private fun rejected(code: String, message: String) =
        PersistedReturnResult.Rejected(ReturnRejection(code, message))
}

private fun ReturnRequest.toRecord(reason: String, createdAt: Instant) = ReturnRequestRecord(
    id = returnId,
    orderId = orderId,
    customerId = customerId,
    status = status,
    reason = reason,
    requestedRefundCfa = requestedRefundCfa,
    returnPinHash = returnPinHash,
    relayPointId = relayPointId,
    droppedAt = droppedAt,
    receivedBySequoAt = receivedBySequoAt,
    receivingOperatorId = receivingOperatorId,
    conditionAssessment = conditionAssessment,
    responsibility = responsibility,
    receiptIdempotencyKey = receiptIdempotencyKey,
    refundIdempotencyKey = refundIdempotencyKey,
    createdAt = createdAt,
    updatedAt = createdAt,
)

private fun ReturnRequestRecord.apply(
    request: ReturnRequest,
    updatedAt: Instant,
    refundAmountCfa: Int? = null,
) {
    status = request.status
    relayPointId = request.relayPointId
    droppedAt = request.droppedAt
    receivedBySequoAt = request.receivedBySequoAt
    receivingOperatorId = request.receivingOperatorId
    conditionAssessment = request.conditionAssessment
    responsibility = request.responsibility
    receiptIdempotencyKey = request.receiptIdempotencyKey
    refundIdempotencyKey = request.refundIdempotencyKey
    if (refundAmountCfa != null) this.refundAmountCfa = refundAmountCfa
    this.updatedAt = updatedAt
}

private fun ReturnRequestRecord.toDomain() = ReturnRequest(
    returnId = id,
    orderId = orderId,
    customerId = customerId,
    status = status,
    requestedAt = createdAt,
    requestedRefundCfa = requestedRefundCfa,
    returnPinHash = returnPinHash,
    relayPointId = relayPointId,
    droppedAt = droppedAt,
    receivedBySequoAt = receivedBySequoAt,
    receivingOperatorId = receivingOperatorId,
    conditionAssessment = conditionAssessment,
    responsibility = responsibility,
    receiptIdempotencyKey = receiptIdempotencyKey,
    refundIdempotencyKey = refundIdempotencyKey,
)

private fun ReturnRequestRecord.toSnapshot() = ReturnRequestSnapshot(
    id = id,
    orderId = orderId,
    customerId = customerId,
    status = status,
    reason = reason,
    requestedAt = createdAt,
    requestedRefundCfa = requestedRefundCfa,
    relayPointId = relayPointId,
    droppedAt = droppedAt,
    receivedBySequoAt = receivedBySequoAt,
    receivingOperatorId = receivingOperatorId,
    conditionAssessment = conditionAssessment,
    responsibility = responsibility,
    refundAmountCfa = refundAmountCfa,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
