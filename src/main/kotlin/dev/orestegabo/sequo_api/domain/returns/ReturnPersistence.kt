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
    val dropoffMethod: String? = null,
    val instructions: String? = null,
    val relayPoints: List<dev.orestegabo.sequo_api.domain.relay.RelayDestination> = emptyList(),
    val returnPin: String? = null,
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
            dropoffMethod = routing.method, instructions = routing.instructions, relayPoints = routing.points, returnPin = raw)
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

    @Transactional
    fun recordRelayDropoff(command: PersistedRelayDropoffCommand): PersistedReturnResult =
        withExisting(command.returnId) { record ->
            if (record.droppedAt != null && record.relayPointId == command.relayPointId && Sha256ReturnPinHasher().matches(command.rawReturnPin, record.returnPinHash))
                return@withExisting accepted(record)
            if (!dev.orestegabo.sequo_api.domain.relay.relayCategoriesAllowed(relayEligibility.categories(record.orderId)))
                return@withExisting rejected("relay_return_ineligible", "This order contains an item excluded from relay handling. Contact SEQUO for return instructions.")
            if (record.customerCredentialVersion == 1) {
                val order = orders.findById(record.orderId).orElseThrow()
                if (plan(order).method != "RELAY" || relayAvailability.available(forReturns = true).none { it.id == command.relayPointId })
                    return@withExisting rejected("relay_return_unavailable", "This return is not authorized for that relay drop-off.")
            }
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
            ).persist(record, command.receivedAt) { saved ->
                publishReturnReceivedBySequo(saved)
            }
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
            ).persist(record, Instant.now(), refundAmountCfa = command.amountCfa) { saved ->
                publishRefundTriggered(saved)
            }
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
        val orderId = returns.orderReference(returnId) ?: return rejected("return_not_found", "Return request was not found.")
        orders.lockForCompletion(orderId)
        val record = returns.lockForAction(returnId)
            ?: return rejected("return_not_found", "Return request was not found.")
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
                title = "Refund started",
                body = "Refund processing has started for return ${record.id}.",
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
