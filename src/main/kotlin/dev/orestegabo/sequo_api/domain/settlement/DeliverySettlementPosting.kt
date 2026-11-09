package dev.orestegabo.sequo_api.domain.settlement

import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordStatus
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRepository
import dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import jakarta.persistence.*
import java.time.Instant
import org.springframework.context.event.EventListener
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Published only by an authorized persisted completion, never by a payment or rider request body. */
data class DeliverySettlementReadyEvent(val missionId: String)

enum class DeliverySettlementPostingStatus { POSTED, REVIEW_REQUIRED }

@Entity @Table(name = "delivery_settlements")
class DeliverySettlementRecord(
    @Id @Column(name = "mission_id") val missionId: String,
    @Column(name = "order_id", nullable = false) val orderId: String,
    @Column(name = "courier_id", nullable = false) val courierId: String,
    @Enumerated(EnumType.STRING) @Column(name = "workforce_type") val workforceType: DeliveryWorkforceType?,
    @Column(name = "customer_delivery_fee_allocated_cfa", nullable = false) val customerDeliveryFeeAllocatedCfa: Int,
    @Column(name = "courier_payable_cfa", nullable = false) val courierPayableCfa: Int,
    @Column(name = "shortfall_cfa", nullable = false) val shortfallCfa: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val status: DeliverySettlementPostingStatus,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)

interface DeliverySettlementRecordRepository : JpaRepository<DeliverySettlementRecord, String> {
    fun findByOrderId(orderId: String): List<DeliverySettlementRecord>
}

/** Accrual only: this service does not initiate or confirm a provider transfer. */
@Service
class DeliverySettlementPostingService(
    private val missions: DeliveryMissionRepository,
    private val orders: CustomerOrderRecordRepository,
    private val users: UserRepository,
    private val couriers: SettlementCourierRecordRepository,
    private val postings: DeliverySettlementRecordRepository,
    private val ledger: SettlementLedgerEntryRecordRepository,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onCompleted(event: DeliverySettlementReadyEvent) { postDeliverySettlement(event.missionId) }

    @Transactional
    fun postDeliverySettlement(missionId: String): DeliverySettlementRecord? {
        val mission = missions.lockForAction(missionId) ?: return null
        postings.findById(missionId).orElse(null)?.let { return it }
        if (mission.status !in setOf(DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER,
                DeliveryMissionRecordStatus.DEPOSITED_AT_RELAY, DeliveryMissionRecordStatus.RELEASED_BY_RELAY,
                DeliveryMissionRecordStatus.HANDED_TO_SEQUO)) return null
        val order = orders.findById(mission.orderId).orElse(null) ?: return null
        if (dev.orestegabo.sequo_api.domain.testaccess.isNoFundsTestOrder(order.paymentReference)) return null
        if (order.paymentStatus != PaymentValidationStatus.Validated) return null
        val courierId = mission.courierId ?: return null
        // The same owner lock is used by checkout. It serializes contribution allocation across
        // distinct mission rows without locking another mission or changing the paid order.
        require(users.lockCheckoutOwner(order.customerId) != null) { "Paid order owner is unavailable." }
        postings.findById(missionId).orElse(null)?.let { return it }
        val workforce = mission.assignmentWorkforceType ?: courierWorkforce(couriers.findById(courierId).orElse(null)?.workforceType)
        val at = mission.deliveredAt ?: mission.relayDepositedAt ?: mission.updatedAt
        // A completed historical mission with existing money needs reconciliation, not a second accrual.
        val existing = ledger.findBySourceTypeAndSourceIdOrderByCreatedAtAsc(SettlementSourceType.DeliveryMission, missionId)
        val occupiedPostingId = ledger.existsById("$missionId:courier-payable") || ledger.existsById("$missionId:delivery-shortfall")
        val priorPostings = postings.findByOrderId(order.id).associateBy { it.missionId }
        val unsettledLegacyLeg = missions.findByOrderIdOrderByCreatedAtAsc(order.id).any { other ->
            val id = requireNotNull(other.id)
            id != missionId && other.status in setOf(DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER,
                DeliveryMissionRecordStatus.DEPOSITED_AT_RELAY, DeliveryMissionRecordStatus.RELEASED_BY_RELAY,
                DeliveryMissionRecordStatus.HANDED_TO_SEQUO) && priorPostings[id]?.status != DeliverySettlementPostingStatus.POSTED &&
                (other.settlementPolicyVersion != 1 || priorPostings[id]?.status == DeliverySettlementPostingStatus.REVIEW_REQUIRED ||
                    ledger.findBySourceTypeAndSourceIdOrderByCreatedAtAsc(SettlementSourceType.DeliveryMission, id).isNotEmpty())
        }
        if (mission.settlementPolicyVersion != 1 || unsettledLegacyLeg || existing.isNotEmpty() || occupiedPostingId || workforce == null || order.orderStatus !=
            dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT) {
            return postings.save(DeliverySettlementRecord(missionId, order.id, courierId, workforce, 0, 0, 0,
                DeliverySettlementPostingStatus.REVIEW_REQUIRED, at))
        }
        val payable = if (workforce == DeliveryWorkforceType.Freelancer) mission.courierFeeCfa else 0
        val allocated = postings.findByOrderId(order.id).sumOf { it.customerDeliveryFeeAllocatedCfa.toLong() }
        val remainingContribution = (order.deliveryFeeCfa.toLong() - allocated).coerceAtLeast(0)
        val contribution = minOf(remainingContribution, payable.toLong()).toInt()
        val shortfall = payable - contribution
        val record = postings.save(DeliverySettlementRecord(missionId, order.id, courierId, workforce,
            contribution, payable, shortfall, DeliverySettlementPostingStatus.POSTED, at))
        if (payable > 0) ledger.save(SettlementLedgerEntryRecord(
            id = "$missionId:courier-payable", account = SettlementLedgerAccount.CourierPayable,
            direction = SettlementLedgerDirection.Credit, amountCfa = payable, courierId = courierId,
            sourceType = SettlementSourceType.DeliveryMission, sourceId = missionId,
            description = "Independent courier pay accrued for completed paid mission; provider transfer unconfirmed.",
            createdAt = at, workforceType = workforce,
        ))
        if (shortfall > 0) ledger.save(SettlementLedgerEntryRecord(
            id = "$missionId:delivery-shortfall", account = SettlementLedgerAccount.SequoDeliveryShortfallExpense,
            direction = SettlementLedgerDirection.Debit, amountCfa = shortfall, courierId = courierId,
            sourceType = SettlementSourceType.DeliveryMission, sourceId = missionId,
            description = "SEQUO shortfall after $contribution CFA of the order's delivery fee was allocated to this mission.",
            createdAt = at, workforceType = workforce,
        ))
        return record
    }
}

internal fun courierWorkforce(value: String?): DeliveryWorkforceType? = when (value?.uppercase()) {
    "FREELANCER", "INDEPENDENT" -> DeliveryWorkforceType.Freelancer
    "SEQUO_SALARIED", "SEQUOSALARIED", "SALARIED" -> DeliveryWorkforceType.SequoSalaried
    else -> null
}
