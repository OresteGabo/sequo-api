package dev.orestegabo.sequo_api.domain.returns

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import dev.orestegabo.sequo_api.domain.notification.NotificationWorkflowEvent
import dev.orestegabo.sequo_api.domain.order.CustomerOrderLineRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.order.OrderProductCategory
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "return_requests")
class ReturnRequestRecord(
    @Id val id: String,
    @Column(name = "order_id", nullable = false) val orderId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_return_requests_order"))
    val order: CustomerOrderRecord? = null,
    @Column(name = "customer_id", nullable = false) val customerId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_return_requests_customer"))
    val customer: User? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: ReturnStatus,
    @Column(nullable = false) val reason: String,
    @Column(name = "requested_refund_cfa", nullable = false) val requestedRefundCfa: Int,
    @Column(name = "return_pin_hash", nullable = false) val returnPinHash: String,
    @Column(name = "customer_credential_version") var customerCredentialVersion: Int? = null,
    @Column(name = "relay_point_id") var relayPointId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_point_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_return_requests_relay_point"))
    val relayPoint: RelayPointRecord? = null,
    @Column(name = "dropped_at") var droppedAt: Instant? = null,
    @Column(name = "received_by_sequo_at") var receivedBySequoAt: Instant? = null,
    @Column(name = "receiving_operator_id") var receivingOperatorId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiving_operator_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_return_requests_receiving_operator"))
    val receivingOperator: User? = null,
    @Column(name = "condition_assessment") var conditionAssessment: String? = null,
    @Enumerated(EnumType.STRING) @Column var responsibility: RefundResponsibility? = null,
    @Column(name = "receipt_idempotency_key") var receiptIdempotencyKey: String? = null,
    @Column(name = "refund_idempotency_key") var refundIdempotencyKey: String? = null,
    @Column(name = "refund_amount_cfa") var refundAmountCfa: Int? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "collected_by_sequo_at") var collectedBySequoAt: Instant? = null,
    @Column(name = "collecting_operator_id") var collectingOperatorId: String? = null,
    @Enumerated(EnumType.STRING) @Column(name = "inspection_decision") var inspectionDecision: ReturnInspectionDecision? = null,
    @Column(name = "inspected_at") var inspectedAt: Instant? = null,
    @Column(name = "inspecting_operator_id") var inspectingOperatorId: String? = null,
    @Column(name = "refund_instruction_id", unique = true) var refundInstructionId: String? = null,
    @Column(name = "refund_instructed_at") var refundInstructedAt: Instant? = null,
    @Column(name = "refund_instructing_operator_id") var refundInstructingOperatorId: String? = null,
    @Column(name = "receipt_condition_assessment") var receiptConditionAssessment: String? = null,
    @Enumerated(EnumType.STRING) @Column(name = "receipt_responsibility") var receiptResponsibility: RefundResponsibility? = null,
    @Column(name = "custody_history_json", nullable = false, columnDefinition = "text") var custodyHistoryJson: String = "[]",
    @Version @Column(nullable = false) var version: Long = 0,
)

interface ReturnRequestRecordRepository : JpaRepository<ReturnRequestRecord, String> {
    @org.springframework.data.jpa.repository.Query("select r.orderId from ReturnRequestRecord r where r.id = :id")
    fun orderReference(id: String): String?
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from ReturnRequestRecord r where r.id = :id")
    fun lockForAction(id: String): ReturnRequestRecord?
    fun findByCustomerIdOrderByCreatedAtDesc(customerId: String): List<ReturnRequestRecord>
    fun findByStatusOrderByUpdatedAtAsc(status: ReturnStatus): List<ReturnRequestRecord>
    fun findByOrderIdOrderByCreatedAtDesc(orderId: String): List<ReturnRequestRecord>
    fun findByRelayPointIdOrderByUpdatedAtDesc(relayPointId: String): List<ReturnRequestRecord>
    fun findAllByOrderByUpdatedAtAsc(): List<ReturnRequestRecord>
    @org.springframework.data.jpa.repository.Query("select distinct r from ReturnRequestRecord r, CustomerOrderLineRecord l where r.orderId = l.orderId and l.sellerId = :merchantId order by r.updatedAt asc")
    fun findForMerchant(merchantId: String): List<ReturnRequestRecord>
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
    val customerCredentialVersion: Int? = null,
    val customerSubmission: Boolean = false,
)

data class PersistedRelayDropoffCommand(
    val returnId: String,
    val relayPointId: String,
    val rawReturnPin: String,
    val droppedAt: Instant = Instant.now(),
    val operatorId: String,
    val idempotencyKey: String,
    val metadata: String? = null,
)

data class PersistedReturnCollectionCommand(
    val returnId: String,
    val operatorId: String,
    val idempotencyKey: String,
    val metadata: String? = null,
)

data class PersistedReturnInspectionCommand(
    val returnId: String,
    val operatorId: String,
    val decision: ReturnInspectionDecision,
    val conditionAssessment: String,
    val responsibility: RefundResponsibility,
    val idempotencyKey: String,
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
    val operatorId: String,
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
    val dropoffMethod: String? = null,
    val instructions: String? = null,
    val relayPoints: List<dev.orestegabo.sequo_api.domain.relay.RelayDestination> = emptyList(),
    val returnPin: String? = null,
    val collectedBySequoAt: Instant? = null,
    val collectingOperatorId: String? = null,
    val inspectionDecision: ReturnInspectionDecision? = null,
    val inspectedAt: Instant? = null,
    val inspectingOperatorId: String? = null,
    val history: List<ReturnCustodyEvent> = emptyList(),
    val refundInstruction: ReturnRefundInstruction? = null,
    val refundDeadlineState: String = "UNCONFIGURED",
    val refundDueAt: Instant? = null,
    val refundInstructionState: String = "NOT_INSTRUCTED",
    val receiptConditionAssessment: String? = null,
    val receiptResponsibility: RefundResponsibility? = null,
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
    private val publisher: ApplicationEventPublisher,
    private val policy: CustomerReturnPolicy,
    @org.springframework.beans.factory.annotation.Qualifier("returnClock") private val clock: java.time.Clock,
    private val relayEligibility: dev.orestegabo.sequo_api.domain.relay.RelayOrderEligibilityService,
    private val relayAvailability: dev.orestegabo.sequo_api.domain.relay.RelayAvailabilityService,
    private val entityManager: jakarta.persistence.EntityManager,
) {
    private val domain = ReturnProcessingService(returnWindow = policy.window)
    private val objectMapper = ObjectMapper()

    @Transactional
    fun requestForCustomer(customerId: String, returnId: String, orderId: String, reason: String): PersistedReturnResult {
        val order = orders.lockForCompletion(orderId) ?: return rejected("order_not_found", "Order was not found.")
        if (order.customerId != customerId) return rejected("customer_not_order_owner", "Only the order owner can request a return.")
        return requestReturn(PersistedReturnRequestCommand(returnId, orderId, customerId, reason,
            order.itemSubtotalCfa, policy.credential(customerId, orderId, returnId), clock.instant(),
            customerCredentialVersion = 1, customerSubmission = true))
    }

    @Transactional
    fun requestForStaff(customerId: String, returnId: String, orderId: String, reason: String,
        requestedRefundCfa: Int?, rawReturnPin: String?, adminOverride: Boolean): PersistedReturnResult {
        val order = orders.lockForCompletion(orderId) ?: return rejected("order_not_found", "Order was not found.")
        return requestReturn(PersistedReturnRequestCommand(returnId, orderId, customerId, reason,
            requestedRefundCfa ?: order.itemSubtotalCfa, rawReturnPin ?: policy.credential(customerId, orderId, returnId),
            clock.instant(), adminOverride = adminOverride,
            customerCredentialVersion = 1.takeIf { rawReturnPin == null }, customerSubmission = true))
    }

    fun currentTime(): Instant = clock.instant()

    @Transactional(readOnly = true)
    fun customerEligibility(customerId: String, orderId: String): CustomerReturnEligibility {
        val order = ownedOrder(customerId, orderId)
        val existing = returns.findByOrderIdOrderByCreatedAtDesc(orderId).firstOrNull()
        val now = clock.instant()
        val deadline = order.deliveredAt?.plus(policy.window)
        val rejection = when {
            existing != null -> ReturnRejection("return_already_requested", "A return is already recorded for this order.")
            order.paymentStatus != dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus.Validated ||
                order.orderStatus != CustomerOrderStatus.DELIVERED || order.deliveredAt == null -> ReturnRejection("order_not_delivered", "Only paid delivered orders can be returned.")
            now.isBefore(order.deliveredAt) -> ReturnRejection("invalid_return_time", "The recorded delivery time requires review.")
            now.isAfter(requireNotNull(deadline)) -> ReturnRejection("return_window_expired", "The return request window has expired.")
            !productPolicyAllows(order) -> ReturnRejection("item_not_returnable", "The configured return policy excludes an item in this order. Contact SEQUO for review.")
            else -> null
        }
        val plan = plan(order)
        return CustomerReturnEligibility(orderId, rejection == null, rejection?.code, rejection?.message,
            order.deliveredAt, deadline, existing?.id, plan.method, plan.instructions, plan.points)
    }

    @Transactional(readOnly = true)
    fun customerReturnsForOrder(customerId: String, orderId: String): List<ReturnRequestSnapshot> {
        ownedOrder(customerId, orderId)
        return returns.findByOrderIdOrderByCreatedAtDesc(orderId).map { customerView(customerId, it.toSnapshot()) }
    }

    @Transactional(readOnly = true)
    fun customerView(customerId: String, snapshot: ReturnRequestSnapshot): ReturnRequestSnapshot {
        val order = ownedOrder(customerId, snapshot.orderId)
        require(snapshot.customerId == customerId) { "Return is unavailable." }
        val record = returns.findById(snapshot.id).orElseThrow()
        var routing = plan(order)
        if (record.status == ReturnStatus.Requested && routing.method == "RELAY") routing = pendingPlan()
        if (record.status !in setOf(ReturnStatus.Requested, ReturnStatus.AwaitingRelayDropoff)) {
            routing = ReturnDropoffPlan("ALREADY_DEPOSITED", when (record.status) {
                ReturnStatus.DroppedAtRelay -> "The relay recorded your drop-off. Wait for SEQUO collection and inspection."
                ReturnStatus.CollectedBySequo -> "SEQUO collected the item. Physical receipt and inspection are pending."
                ReturnStatus.ReceivedBySequo -> "SEQUO received the item. Its condition and refund eligibility are being reviewed."
                ReturnStatus.RefundPending -> "Refund processing is pending. This status does not confirm a completed payment."
                ReturnStatus.Rejected -> "This return was rejected. Contact SEQUO for further instructions."
                else -> "Contact SEQUO for the current return instructions."
            })
        }
        val raw = if (record.customerCredentialVersion == 1 && record.status == ReturnStatus.AwaitingRelayDropoff && routing.method == "RELAY")
            policy.credential(customerId, order.id, record.id).takeIf { Sha256ReturnPinHasher().matches(it, record.returnPinHash) } else null
        if (record.status == ReturnStatus.AwaitingRelayDropoff && routing.method == "RELAY" && raw == null)
            routing = ReturnDropoffPlan("SEQUO_INSTRUCTIONS_PENDING", "Your return is recorded. Contact SEQUO to confirm the existing return credential before going to a relay.")
        return snapshot.copy(receivingOperatorId = null, conditionAssessment = null, responsibility = null,
            receiptConditionAssessment = null, receiptResponsibility = null,
            dropoffMethod = routing.method, instructions = routing.instructions, relayPoints = routing.points, returnPin = raw,
            collectingOperatorId = null, inspectingOperatorId = null,
            history = snapshot.history.map { it.copy(actorUserId = null, metadata = null) },
            refundInstruction = snapshot.refundInstruction?.copy(requestedByUserId = null))
    }

    private fun ownedOrder(customerId: String, orderId: String): CustomerOrderRecord =
        orders.findById(orderId).orElse(null)?.takeIf { it.customerId == customerId }
            ?: throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Order is unavailable.")

    private fun productPolicyAllows(order: CustomerOrderRecord): Boolean {
        val lines = orderLines.findByOrderIdOrderByLineIndexAsc(order.id)
        if (lines.isEmpty()) return false
        if (order.route == dev.orestegabo.sequo_api.domain.order.OrderRoute.Pickup && policy.clickAndCollectMode == ClickAndCollectReturnMode.PENDING) return true
        val categories = lines.map { it.category }.toMutableSet()
        // Sealed items retain their kind in OrderProductCategory. The checkout category still
        // carries grocery/sensitive exclusions and must not be lost when evaluating return policy.
        relayEligibility.categories(order.id).forEach { category ->
            when (category) {
                dev.orestegabo.sequo_api.domain.relay.RelayParcelCategory.Food -> categories += OrderProductCategory.Food
                dev.orestegabo.sequo_api.domain.relay.RelayParcelCategory.Perishable -> categories += OrderProductCategory.Perishable
                dev.orestegabo.sequo_api.domain.relay.RelayParcelCategory.Sensitive -> categories += OrderProductCategory.Sensitive
                else -> Unit
            }
        }
        return categories.none { it in policy.excludedCategories }
    }

    private data class ReturnDropoffPlan(val method: String, val instructions: String,
        val points: List<dev.orestegabo.sequo_api.domain.relay.RelayDestination> = emptyList())

    private fun pendingPlan() = ReturnDropoffPlan("SEQUO_INSTRUCTIONS_PENDING",
        "Keep the packaged item and your return ID. Contact SEQUO for an approved drop-off location; do not leave the item unattended.")

    private fun plan(order: CustomerOrderRecord): ReturnDropoffPlan {
        if (order.route == dev.orestegabo.sequo_api.domain.order.OrderRoute.Pickup) {
            when (policy.clickAndCollectMode) {
                ClickAndCollectReturnMode.PENDING -> return ReturnDropoffPlan("CUSTOMER_MANAGED_PENDING",
                    "Your Click and collect return request can be recorded. SEQUO must confirm the customer-managed return instructions before you move the item.")
                ClickAndCollectReturnMode.SELLER_MANAGED -> return ReturnDropoffPlan("CUSTOMER_MANAGED",
                    "Arrange the customer-managed return directly with the seller. Keep your return ID; no relay drop-off is authorized by this instruction.")
                ClickAndCollectReturnMode.RELAY -> Unit
            }
        }
        if (!dev.orestegabo.sequo_api.domain.relay.relayCategoriesAllowed(relayEligibility.categories(order.id))) return pendingPlan()
        val points = relayAvailability.available(forReturns = true)
        return if (points.isEmpty()) pendingPlan() else ReturnDropoffPlan("RELAY",
            "Bring the packaged item, return ID and return PIN to an available eligible relay below. Have the operator confirm receipt; reimbursement requires SEQUO receipt and inspection.", points)
    }

    @Transactional
    fun requestReturn(command: PersistedReturnRequestCommand): PersistedReturnResult {
        val order = orders.lockForCompletion(command.orderId)
            ?: return rejected("order_not_found", "Order was not found.")
        if (order.customerId != command.customerId) return rejected("customer_not_order_owner", "Only the order owner can request a return.")
        returns.findById(command.returnId).orElse(null)?.let {
            if (it.customerId != command.customerId || it.orderId != command.orderId) return rejected("return_id_conflict", "Return ID is already in use.")
            if (it.reason != command.reason || it.requestedRefundCfa != command.requestedRefundCfa) return rejected("return_retry_conflict", "A return retry must use the original request.")
            return accepted(it)
        }
        returns.findByOrderIdOrderByCreatedAtDesc(order.id).firstOrNull()?.let { return accepted(it) }
        if (order.paymentStatus != dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus.Validated || order.orderStatus != CustomerOrderStatus.DELIVERED)
            return rejected("order_not_delivered", "Only paid delivered orders can be returned.")
        val deliveredAt = order.deliveredAt
            ?: return rejected("order_not_delivered", "Only delivered orders can be returned.")
        if (!productPolicyAllows(order) && !command.adminOverride)
            return rejected("item_not_returnable", "The configured return policy excludes an item in this order. Contact SEQUO for review.")
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
                    productReturnable = productPolicyAllows(order),
                    merchantAllowsReturn = true,
                    adminOverride = command.adminOverride,
                    relayDropoffAllowed = if (command.customerSubmission) plan(order).method == "RELAY" else order.returnItemCategory() == ReturnItemCategory.ReturnableGoods,
                ),
            )
        )

        return when (result) {
            is ReturnProcessingResult.Accepted -> {
                val record = result.returnRequest.toRecord(command.reason, command.requestedAt)
                record.customerCredentialVersion = command.customerCredentialVersion
                if (command.customerSubmission && plan(order).method != "RELAY") record.status = ReturnStatus.Requested
                try {
                    entityManager.persist(record)
                    entityManager.flush()
                } catch (_: org.hibernate.exception.ConstraintViolationException) {
                    throw ReturnWriteConflict()
                } catch (_: jakarta.persistence.EntityExistsException) {
                    throw ReturnWriteConflict()
                }
                val saved = record
                order.orderStatus = CustomerOrderStatus.RETURN_REQUESTED
                order.updatedAt = command.requestedAt
                orders.save(order)
                publishReturnRequested(saved, order)
                if (saved.status == ReturnStatus.AwaitingRelayDropoff) publishReturnPinCreated(saved)
                accepted(saved)
            }
            is ReturnProcessingResult.Rejected -> PersistedReturnResult.Rejected(result.rejection)
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun recordRelayDropoff(command: PersistedRelayDropoffCommand): PersistedReturnResult =
        withExisting(command.returnId) { record ->
            val intent = custodyIntent(command.relayPointId, Sha256ReturnPinHasher().hash(command.rawReturnPin), command.metadata)
            replay(record, "RELAY_DROPOFF", intent)?.let { return@withExisting it }
            // Preserve supported legacy deposits without fabricating their missing actor/history.
            if (record.droppedAt != null && record.relayPointId == command.relayPointId && Sha256ReturnPinHasher().matches(command.rawReturnPin, record.returnPinHash))
                return@withExisting accepted(record)
            if (!dev.orestegabo.sequo_api.domain.relay.relayCategoriesAllowed(relayEligibility.categories(record.orderId)))
                return@withExisting rejected("relay_return_ineligible", "This order contains an item excluded from relay handling. Contact SEQUO for return instructions.")
            if (record.customerCredentialVersion == 1) {
                val order = orders.findById(record.orderId).orElseThrow()
                if (plan(order).method != "RELAY" || relayAvailability.available(forReturns = true).none { it.id == command.relayPointId })
                    return@withExisting rejected("relay_return_unavailable", "This return is not authorized for that relay drop-off.")
            }
            val now = clock.instant()
            if (now.isBefore(record.createdAt)) return@withExisting rejected("invalid_custody_time", "Drop-off cannot precede the return request.")
            domain.recordRelayDropoff(RelayDropoffCommand(record.toDomain(), command.relayPointId, command.rawReturnPin, now))
                .persist(record, now) { audit(it, "RELAY_DROPOFF", command.operatorId, now, command.idempotencyKey, intent, command.metadata) }
        }

    @Transactional(rollbackFor = [Exception::class])
    fun recordCollection(command: PersistedReturnCollectionCommand): PersistedReturnResult =
        withExisting(command.returnId) { record ->
            val intent = custodyIntent(command.metadata)
            replay(record, "SEQUO_COLLECTION", intent)?.let { return@withExisting it }
            val now = clock.instant()
            domain.recordCollection(ReturnCollectionCommand(record.toDomain(), command.operatorId, now, command.idempotencyKey))
                .persist(record, now) {
                    it.collectingOperatorId = command.operatorId
                    audit(it, "SEQUO_COLLECTION", command.operatorId, now, command.idempotencyKey, intent, command.metadata)
                }
        }

    @Transactional(rollbackFor = [Exception::class])
    fun recordPhysicalReceipt(command: PersistedPhysicalReceiptCommand): PersistedReturnResult =
        withExisting(command.returnId) { record ->
            val intent = custodyIntent(command.conditionAssessment, command.responsibility)
            replay(record, "PHYSICAL_RECEIPT", intent)?.let { return@withExisting it }
            if (record.receiptIdempotencyKey == command.idempotencyKey && record.receivedBySequoAt != null) {
                return@withExisting if ((record.receiptConditionAssessment ?: record.conditionAssessment) == command.conditionAssessment && (record.receiptResponsibility ?: record.responsibility) == command.responsibility) accepted(record)
                else rejected("return_retry_conflict", "Receipt retry must preserve the original assessment.")
            }
            val now = clock.instant()
            domain.recordPhysicalReceipt(PhysicalReceiptCommand(record.toDomain(), command.operatorId, now,
                command.conditionAssessment, command.responsibility, command.idempotencyKey))
                .persist(record, now) {
                    it.receiptConditionAssessment = command.conditionAssessment
                    it.receiptResponsibility = command.responsibility
                    audit(it, "PHYSICAL_RECEIPT", command.operatorId, now, command.idempotencyKey, intent, command.conditionAssessment)
                    publishReturnReceivedBySequo(it)
                }
        }

    @Transactional(rollbackFor = [Exception::class])
    fun recordInspection(command: PersistedReturnInspectionCommand): PersistedReturnResult =
        withExisting(command.returnId) { record ->
            val intent = custodyIntent(command.decision, command.conditionAssessment, command.responsibility)
            replay(record, "CONDITION_INSPECTION", intent)?.let { return@withExisting it }
            if (record.status != ReturnStatus.ReceivedBySequo || record.receivedBySequoAt == null)
                return@withExisting rejected("physical_receipt_required", "Inspection requires actual recorded SEQUO receipt.")
            if (command.conditionAssessment.isBlank() || command.operatorId.isBlank() || command.idempotencyKey.isBlank())
                return@withExisting rejected("inspection_proof_required", "Inspection requires an operator, condition assessment and idempotency key.")
            val now = clock.instant()
            if (now.isBefore(record.receivedBySequoAt)) return@withExisting rejected("invalid_custody_time", "Inspection cannot precede receipt.")
            // Older receipt notes are evidence, not an implicit inspection approval.
            if (record.receiptConditionAssessment == null) record.receiptConditionAssessment = record.conditionAssessment
            if (record.receiptResponsibility == null) record.receiptResponsibility = record.responsibility
            record.inspectionDecision = command.decision
            record.inspectedAt = now
            record.inspectingOperatorId = command.operatorId
            record.conditionAssessment = command.conditionAssessment
            record.responsibility = command.responsibility
            if (command.decision == ReturnInspectionDecision.REJECTED) record.status = ReturnStatus.Rejected
            record.updatedAt = now
            audit(record, "CONDITION_INSPECTION", command.operatorId, now, command.idempotencyKey, intent, command.conditionAssessment)
            accepted(returns.save(record))
        }

    @Transactional(rollbackFor = [Exception::class])
    fun triggerRefund(command: PersistedRefundTriggerCommand): PersistedReturnResult =
        withExisting(command.returnId) { record ->
            // The locked return row holds exactly one durable instruction. A fresh retry key cannot create another.
            if (record.refundInstructionId != null) return@withExisting if (record.refundAmountCfa == command.amountCfa) accepted(record)
                else rejected("return_retry_conflict", "A refund instruction already exists with another amount.")
            if (record.status == ReturnStatus.RefundPending || record.refundIdempotencyKey != null)
                return@withExisting rejected("legacy_refund_review_required", "An existing legacy refund must be reconciled before any further instruction.")
            val order = orders.findById(record.orderId).orElseThrow()
            // New requests are canonical per whole order. Legacy duplicate return rows remain readable,
            // but must not authorize a second instruction against the same paid purchase.
            if (returns.findByOrderIdOrderByCreatedAtDesc(record.orderId).any { other -> other.id != record.id &&
                    (other.refundInstructionId != null || other.refundIdempotencyKey != null || other.status == ReturnStatus.RefundPending) })
                return@withExisting rejected("order_refund_review_required", "Another return for this order already has a refund in progress. Reconcile it before further instruction.")
            if (order.paymentStatus != dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus.Validated)
                return@withExisting rejected("paid_order_required", "Refund instruction requires a verified paid order.")
            if (command.operatorId.isBlank()) return@withExisting rejected("missing_operator", "Refund authorizing operator is required.")
            if (record.inspectionDecision == ReturnInspectionDecision.ACCEPTED && (record.inspectedAt == null || record.inspectingOperatorId.isNullOrBlank()))
                return@withExisting rejected("inspection_required", "Recorded inspection evidence requires operator review.")
            if (command.amountCfa > order.itemSubtotalCfa)
                return@withExisting rejected("refund_amount_too_high", "Refund instruction cannot exceed the paid item subtotal.")
            val now = clock.instant()
            if (record.inspectedAt?.let { now.isBefore(it) } == true)
                return@withExisting rejected("invalid_custody_time", "Instruction cannot precede inspection.")
            domain.triggerRefund(RefundTriggerCommand(record.toDomain(), command.amountCfa, command.idempotencyKey))
                .persist(record, now, refundAmountCfa = command.amountCfa) {
                    it.refundInstructionId = java.util.UUID.randomUUID().toString()
                    it.refundInstructedAt = now
                    it.refundInstructingOperatorId = command.operatorId
                    audit(it, "REFUND_INSTRUCTION", command.operatorId, now, command.idempotencyKey, custodyIntent(command.amountCfa), null)
                    publishRefundTriggered(it)
                }
        }

    private fun replay(record: ReturnRequestRecord, action: String, intent: String): PersistedReturnResult? =
        storedCustodyHistory(record.custodyHistoryJson).firstOrNull { it.action == action }?.let {
            if (it.intentHash == intent) accepted(record)
            else rejected("return_retry_conflict", "This custody action is already recorded with different details. Refresh the return.")
        }

    private fun audit(record: ReturnRequestRecord, action: String, actorId: String, at: Instant, key: String, intent: String, metadata: String?) {
        require(actorId.isNotBlank() && key.isNotBlank()) { "Custody actor and retry key are required." }
        storedCustodyHistory(record.custodyHistoryJson)
        val history = custodyMapper.readTree(record.custodyHistoryJson) as com.fasterxml.jackson.databind.node.ArrayNode
        history.add(custodyMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf(
            "action" to action, "createdAt" to at.toString(), "actorUserId" to actorId,
            "metadata" to metadata, "idempotencyKey" to key, "intentHash" to intent)))
        record.custodyHistoryJson = objectMapper.writeValueAsString(history)
    }

    private fun custodyIntent(vararg values: Any?): String = Sha256ReturnPinHasher().hash(objectMapper.writeValueAsString(values.toList()))

    @Transactional(readOnly = true)
    fun listAll(): List<ReturnRequestSnapshot> = returns.findAllByOrderByUpdatedAtAsc().map { it.toSnapshot() }

    @Transactional(readOnly = true)
    fun listForMerchant(merchantId: String): List<MerchantReturnSnapshot> = returns.findForMerchant(merchantId).map { record ->
        val order = orders.findById(record.orderId).orElseThrow()
        MerchantReturnSnapshot(record.id, record.orderId, record.status, record.createdAt, record.droppedAt,
            record.collectedBySequoAt, record.receivedBySequoAt, record.inspectionDecision,
            if (record.refundInstructionId != null) "REQUESTED" else if (record.status == ReturnStatus.RefundPending || record.refundIdempotencyKey != null) "LEGACY_REVIEW_REQUIRED" else "NOT_INSTRUCTED",
            if (record.refundInstructionId != null) "NOT_SUBMITTED" else "UNCONFIRMED", order.paymentStatus.name,
            orderLines.findByOrderIdAndSellerIdOrderByLineIndexAsc(record.orderId, merchantId).map {
                MerchantReturnLine(it.productId, it.productName, it.quantity)
            })
    }

    @Transactional(readOnly = true)
    fun listForRelay(relayPointId: String): List<ReturnRequestSnapshot> =
        returns.findByRelayPointIdOrderByUpdatedAtDesc(relayPointId).map { relayView(it.toSnapshot()) }

    fun relayView(snapshot: ReturnRequestSnapshot): ReturnRequestSnapshot = snapshot.copy(
        customerId = "", reason = "", requestedRefundCfa = 0, refundAmountCfa = null,
        receivingOperatorId = null, conditionAssessment = null, responsibility = null,
        receiptConditionAssessment = null, receiptResponsibility = null,
        collectingOperatorId = null, inspectingOperatorId = null, refundInstruction = null,
        history = snapshot.history.filter { it.action in setOf("RELAY_DROPOFF", "SEQUO_COLLECTION") }.map { it.copy(metadata = null) })

    @Transactional(readOnly = true)
    fun get(returnId: String, canRead: (customerId: String, relayPointId: String?) -> Boolean = { _, _ -> true }): ReturnRequestSnapshot? {
        val record = returns.findById(returnId).orElse(null) ?: return null
        if (!canRead(record.customerId, record.relayPointId)) throw org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.FORBIDDEN)
        return record.toSnapshot()
    }

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
        val orderId = returns.orderReference(returnId) ?: return rejected("return_not_found", "Return request was not found.")
        orders.lockForCompletion(orderId)
        val record = returns.lockForAction(returnId)
            ?: return rejected("return_not_found", "Return request was not found.")
        try {
            storedCustodyHistory(record.custodyHistoryJson)
        } catch (_: Exception) {
            return rejected("return_history_review_required", RETURN_HISTORY_REVIEW_MESSAGE)
        }
        return operation(record)
    }

    private fun ReturnProcessingResult.persist(
        record: ReturnRequestRecord,
        updatedAt: Instant,
        refundAmountCfa: Int? = null,
        afterSave: (ReturnRequestRecord) -> Unit = {},
    ): PersistedReturnResult = when (this) {
        is ReturnProcessingResult.Accepted -> {
            record.apply(returnRequest, updatedAt, refundAmountCfa)
            val saved = returns.save(record)
            afterSave(saved)
            accepted(saved)
        }
        is ReturnProcessingResult.Rejected -> PersistedReturnResult.Rejected(rejection)
    }

    private fun accepted(record: ReturnRequestRecord) = PersistedReturnResult.Accepted(record.toSnapshot())

    private fun rejected(code: String, message: String) =
        PersistedReturnResult.Rejected(ReturnRejection(code, message))

    private fun publishReturnRequested(record: ReturnRequestRecord, order: CustomerOrderRecord) {
        publisher.publishEvent(
            record.toNotificationEvent(
                eventId = "${record.id}:return-requested",
                eventType = NotificationEventType.RETURN_REQUESTED,
                title = "Return requested",
                body = "Return ${record.id} was requested for order ${record.orderId}.",
                merchantUserIds = order.merchantUserIds(),
            )
        )
    }

    private fun publishReturnPinCreated(record: ReturnRequestRecord) {
        publisher.publishEvent(
            record.toNotificationEvent(
                eventId = "${record.id}:return-pin-created",
                eventType = NotificationEventType.RETURN_PIN_CREATED,
                title = "Return PIN ready",
                body = "Your return PIN is ready for relay drop-off.",
            )
        )
    }

    private fun publishReturnReceivedBySequo(record: ReturnRequestRecord) {
        publisher.publishEvent(
            record.toNotificationEvent(
                eventId = "${record.id}:received-by-sequo",
                eventType = NotificationEventType.RETURN_RECEIVED_BY_SEQUO,
                title = "Return received by Sequo",
                body = "Return ${record.id} was received by Sequo and can move toward refund review.",
                merchantUserIds = record.orderMerchantUserIds(),
            )
        )
    }

    private fun publishRefundTriggered(record: ReturnRequestRecord) {
        publisher.publishEvent(
            record.toNotificationEvent(
                eventId = "${record.id}:refund-triggered",
                eventType = NotificationEventType.REFUND_TRIGGERED,
                title = "Refund instruction recorded",
                body = "A refund instruction was recorded for return ${record.id}. Payment has not been confirmed.",
                merchantUserIds = record.orderMerchantUserIds(),
            )
        )
    }

    private fun ReturnRequestRecord.toNotificationEvent(
        eventId: String,
        eventType: NotificationEventType,
        title: String,
        body: String,
        merchantUserIds: Set<String> = emptySet(),
    ): NotificationWorkflowEvent =
        NotificationWorkflowEvent(
            eventId = eventId,
            eventType = eventType,
            aggregateType = "RETURN_REQUEST",
            aggregateId = id,
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "customerUserId" to customerId,
                    "merchantUserIds" to merchantUserIds,
                    "title" to title,
                    "body" to body,
                    "actionUrl" to "/returns/$id",
                    "messagePayload" to mapOf(
                        "returnId" to id,
                        "orderId" to orderId,
                        "status" to status.name,
                    ),
                )
            ),
        )

    private fun CustomerOrderRecord.merchantUserIds(): Set<String> =
        orderLines.findByOrderIdOrderByLineIndexAsc(id).map { it.sellerId }.toSet()

    private fun ReturnRequestRecord.orderMerchantUserIds(): Set<String> =
        orderLines.findByOrderIdOrderByLineIndexAsc(orderId).map { it.sellerId }.toSet()
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
    collectedBySequoAt = request.collectedBySequoAt
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
    collectedBySequoAt = collectedBySequoAt,
    inspectionDecision = inspectionDecision,
    refundAmountCfa = refundAmountCfa,
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
    collectedBySequoAt = collectedBySequoAt,
    collectingOperatorId = collectingOperatorId,
    inspectionDecision = inspectionDecision,
    inspectedAt = inspectedAt,
    inspectingOperatorId = inspectingOperatorId,
    history = storedCustodyHistory(custodyHistoryJson).map { ReturnCustodyEvent(it.action, it.createdAt, it.actorUserId, it.metadata) },
    refundInstruction = refundInstructionId?.let { ReturnRefundInstruction(it, requireNotNull(refundAmountCfa),
        requireNotNull(refundInstructedAt), refundInstructingOperatorId) },
    refundInstructionState = if (refundInstructionId != null) "REQUESTED" else if (status == ReturnStatus.RefundPending || refundIdempotencyKey != null) "LEGACY_REVIEW_REQUIRED" else "NOT_INSTRUCTED",
    receiptConditionAssessment = receiptConditionAssessment,
    receiptResponsibility = receiptResponsibility,
)


data class ReturnCustodyEvent(val action: String, val createdAt: Instant, val actorUserId: String?, val metadata: String?)
data class ReturnRefundInstruction(
    val id: String,
    val amountCfa: Int,
    val requestedAt: Instant,
    val requestedByUserId: String?,
    val currency: String = "XOF",
    val status: String = "REQUESTED",
    val paymentStatus: String = "NOT_SUBMITTED",
)

private data class StoredReturnCustodyEvent(
    val action: String, val createdAt: Instant, val actorUserId: String,
    val metadata: String?, val idempotencyKey: String, val intentHash: String,
)
private const val RETURN_HISTORY_REVIEW_MESSAGE = "Stored return history requires operator review."
private val custodyMapper = ObjectMapper()
    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
private fun storedCustodyHistory(json: String): List<StoredReturnCustodyEvent> {
    try {
        val root = custodyMapper.readTree(json)
        require(root != null && root.isArray)
        return root.map { node ->
            require(node.isObject)
            fun text(name: String): String = node[name]?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
                ?: throw IllegalArgumentException()
            val metadata = node["metadata"]?.takeUnless { it.isNull }?.let { require(it.isTextual); it.textValue() }
            StoredReturnCustodyEvent(text("action"), Instant.parse(text("createdAt")), text("actorUserId"), metadata,
                text("idempotencyKey"), text("intentHash"))
        }
    } catch (_: Exception) {
        // Jackson errors can contain the original input. Never expose stored custody data in an API error.
        throw IllegalArgumentException(RETURN_HISTORY_REVIEW_MESSAGE)
    }
}


data class MerchantReturnLine(val productId: String, val productName: String, val quantity: Int)
data class MerchantReturnSnapshot(
    val id: String, val orderId: String, val status: ReturnStatus, val requestedAt: Instant,
    val droppedAt: Instant?, val collectedBySequoAt: Instant?, val receivedBySequoAt: Instant?,
    val inspectionDecision: ReturnInspectionDecision?, val refundInstructionState: String,
    val refundPaymentStatus: String, val orderPaymentStatus: String, val lines: List<MerchantReturnLine>,
)
