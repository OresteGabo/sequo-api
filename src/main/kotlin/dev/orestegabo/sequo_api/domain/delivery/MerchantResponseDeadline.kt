package dev.orestegabo.sequo_api.domain.delivery

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.notification.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.Environment
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.time.Instant

/** These defaults affect newly created paid merchant parts only; no legacy deadlines are backfilled. */
@Component
class MerchantResponseDeadlinePolicy(environment: Environment) {
    val enabled = environment.getProperty("sequo.merchant-fulfillment.response-deadline.enabled", Boolean::class.java, true)
    val responseWindow: Duration = Duration.parse(environment.getProperty("sequo.merchant-fulfillment.response-deadline.response-window", "PT10M"))
    val alertWindow: Duration = Duration.parse(environment.getProperty("sequo.merchant-fulfillment.response-deadline.alert-window", "PT45S"))
    init {
        require(!responseWindow.isZero && !responseWindow.isNegative) { "Merchant response window must be positive." }
        require(!alertWindow.isZero && !alertWindow.isNegative && alertWindow <= Duration.ofSeconds(45) && alertWindow <= responseWindow) {
            "Incoming alert window must be positive, at most 45 seconds, and no longer than the response window."
        }
    }
}

data class MerchantResponseExpiryOutcome(
    val subOrderId: String,
    val orderId: String,
    val responseExpiredAt: Instant,
    val disposition: String,
)
data class MerchantResponseExpiryRun(val evaluatedAt: Instant, val expired: List<MerchantResponseExpiryOutcome>)

@Service
class MerchantResponseDeadlineService(
    private val parts: MerchantSubOrderRepository,
    private val orders: CustomerOrderRecordRepository,
    private val missions: DeliveryMissionRepository,
    private val events: CustomerOrderEventRecordRepository,
    private val publisher: ApplicationEventPublisher,
    private val recipients: MerchantOrderNotificationRecipients,
    transactions: PlatformTransactionManager,
) {
    private val json = ObjectMapper()
    private val expiryTransaction = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun enforce(limit: Int = 100, actorUserId: String? = null, at: Instant = Instant.now()): MerchantResponseExpiryRun {
        require(limit in 1..200) { "Response deadline scan limit must be between 1 and 200." }
        // Scalar candidates avoid attaching stale entities before waiting on the common parent lock.
        val expired = parts.findDueResponseIds(at, org.springframework.data.domain.PageRequest.of(0, limit)).mapNotNull { id ->
            // Commit one parent at a time: a batch never holds unrelated order locks in reverse dispatch order.
            expiryTransaction.execute {
                val orderId = parts.findOrderIdForAction(id) ?: return@execute null
                val order = orders.lockForCompletion(orderId) ?: return@execute null
                val part = parts.lockForAction(id) ?: return@execute null
                expireLocked(order, part, at, actorUserId)
            }
        }
        return MerchantResponseExpiryRun(at, expired)
    }

    /** Caller holds parent then part locks, in the same order as accept and dispatch. */
    fun expireLocked(order: CustomerOrderRecord, part: MerchantSubOrder, at: Instant, actorUserId: String? = null): MerchantResponseExpiryOutcome? {
        val deadline = part.responseDeadlineAt ?: return null
        if (part.status != MerchantSubOrderStatus.MERCHANT_PENDING || part.responseExpiredAt != null || at.isBefore(deadline) ||
            order.paymentStatus != PaymentValidationStatus.Validated || order.orderStatus != CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT) return null
        val id = requireNotNull(part.id)
        part.status = MerchantSubOrderStatus.CANCELLED
        part.responseExpiredAt = at
        part.rejectionReason = "Merchant response deadline expired without acceptance."
        part.updatedAt = at
        val disposition = recordDisposition(order, part, at, actorUserId, expired = true)
        val eventId = "$id:merchant-response-expired"
        publisher.publishEvent(NotificationWorkflowEvent(
            eventId = eventId, eventType = NotificationEventType.MERCHANT_SLA_WARNING,
            aggregateType = "MERCHANT_SUB_ORDER", aggregateId = id,
            payload = json.writeValueAsString(mapOf("customerUserId" to order.customerId,
                "title" to "Merchant confirmation expired", "body" to order.customerFacingStatus,
                "actionUrl" to "sequo://orders/${order.id}", "severity" to NotificationSeverity.ACTION_REQUIRED.name,
                "messagePayload" to mapOf("subOrderId" to id, "orderId" to order.id, "responseExpiredAt" to at.toString(), "disposition" to disposition)))))
        recipients.users(part.merchantId).toList().chunked(8).forEachIndexed { batch, users ->
            publisher.publishEvent(NotificationWorkflowEvent(
                eventId = "$eventId:merchant:$batch", eventType = NotificationEventType.MERCHANT_SLA_WARNING,
                aggregateType = "MERCHANT_SUB_ORDER", aggregateId = id,
                payload = json.writeValueAsString(mapOf("merchantUserIds" to users, "title" to "Merchant confirmation expired",
                    "body" to order.customerFacingStatus, "actionUrl" to "sequo://merchant/sub-orders/$id",
                    "severity" to NotificationSeverity.ACTION_REQUIRED.name,
                    "messagePayload" to mapOf("subOrderId" to id, "orderId" to order.id, "disposition" to disposition)))))
        }
        return MerchantResponseExpiryOutcome(id, order.id, at, disposition)
    }

    /** A timely refusal of a new timed part follows the same whole/partial-order safety rules. */
    fun rejectLocked(order: CustomerOrderRecord, part: MerchantSubOrder, reason: String, at: Instant, actorUserId: String? = null): Boolean {
        val deadline = part.responseDeadlineAt ?: return false
        if (part.status != MerchantSubOrderStatus.MERCHANT_PENDING || !at.isBefore(deadline)) return false
        part.status = MerchantSubOrderStatus.REJECTED
        part.rejectionReason = reason
        part.updatedAt = at
        recordDisposition(order, part, at, actorUserId = actorUserId, expired = false)
        return true
    }

    private fun recordDisposition(order: CustomerOrderRecord, part: MerchantSubOrder, at: Instant,
        actorUserId: String?, expired: Boolean): String {
        val id = requireNotNull(part.id)
        // Parent serialization protects sibling accept/preparation. Do not erase confirmed work.
        val siblings = parts.findIdsForOrder(order.id).sorted().mapNotNull(parts::lockForAction)
        val hasConfirmedWork = siblings.any { it.acceptedAt != null || it.status in confirmedStatuses }
        val hasMission = missions.findByOrderIdOrderByCreatedAtAsc(order.id).isNotEmpty()
        val disposition = if (!hasConfirmedWork && !hasMission) ORDER_CANCELLED else OPERATIONS_REVIEW_REQUIRED
        part.responseDisposition = disposition
        parts.save(part)
        if (disposition == ORDER_CANCELLED) {
            siblings.filter { it.id != id && it.status == MerchantSubOrderStatus.MERCHANT_PENDING }.forEach {
                it.status = MerchantSubOrderStatus.CANCELLED
                it.responseDisposition = ORDER_CANCELLED
                it.updatedAt = at
                parts.save(it)
            }
            order.orderStatus = CustomerOrderStatus.CANCELLED
            order.customerFacingStatus = if (expired) CANCELLED_MESSAGE else REJECTED_CANCELLED_MESSAGE
        } else {
            val state = if (expired) "expired" else "was refused"
            order.customerFacingStatus = "Merchant confirmation $state; SEQUO review is required. Payment remains verified; no refund has been executed."
        }
        order.updatedAt = at
        orders.save(order)
        val eventId = "$id:merchant-response-${if (expired) "expired" else "rejected"}"
        if (!events.existsById(eventId)) events.save(CustomerOrderEventRecord(
            id = eventId, orderId = order.id,
            eventType = if (expired) CustomerOrderEventType.MERCHANT_RESPONSE_EXPIRED else CustomerOrderEventType.MERCHANT_RESPONSE_REJECTED,
            sourceType = "MERCHANT_SUB_ORDER", sourceId = id, actorUserId = actorUserId,
            metadata = json.writeValueAsString(mapOf("subOrderId" to id, "responseDeadlineAt" to part.responseDeadlineAt?.toString(),
                "disposition" to disposition, "reason" to part.rejectionReason, "paymentStatus" to order.paymentStatus.name,
                "refundExecuted" to false)), createdAt = at))
        return disposition
    }

    companion object {
        const val CANCELLED_MESSAGE = "Cancelled: merchant confirmation expired. Payment remains verified; no refund has been executed."
        const val REJECTED_CANCELLED_MESSAGE = "Cancelled: merchant declined confirmation. Payment remains verified; no refund has been executed."
        const val ORDER_CANCELLED = "ORDER_CANCELLED"
        const val OPERATIONS_REVIEW_REQUIRED = "OPERATIONS_REVIEW_REQUIRED"
        private val confirmedStatuses = setOf(MerchantSubOrderStatus.ACCEPTED, MerchantSubOrderStatus.PREPARING,
            MerchantSubOrderStatus.PACKED_READY, MerchantSubOrderStatus.HANDED_TO_COURIER)
    }
}

@Component
@ConditionalOnProperty(prefix = "sequo.merchant-fulfillment.response-deadline.scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class MerchantResponseDeadlineScheduler(private val deadlines: MerchantResponseDeadlineService) {
    @Scheduled(fixedDelayString = "\${sequo.merchant-fulfillment.response-deadline.scheduler.fixed-delay-ms:15000}")
    fun run() { deadlines.enforce() }
}

@RestController
@RequestMapping("/api/merchant/sub-orders/response-deadlines")
class MerchantResponseDeadlineController(private val deadlines: MerchantResponseDeadlineService) {
    data class EnforceRequest(val limit: Int? = null)
    @PostMapping("/enforce")
    fun enforce(authentication: Authentication?, @RequestBody(required = false) request: EnforceRequest?): ResponseEntity<Any> = when {
        authentication == null || !authentication.isAuthenticated -> ResponseEntity.status(401).cacheControl(CacheControl.noStore()).build()
        !authentication.hasAnyRole(RoleGroups.AdminOperations) -> ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build()
        else -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deadlines.enforce(request?.limit ?: 100, authentication.name))
    }
}
