package dev.orestegabo.sequo_api.domain.settlement

import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry
import dev.orestegabo.sequo_api.domain.reference.referenceOrNull

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
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrder
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "merchant_payout_accruals")
class MerchantPayoutAccrualRecord(
    @Id val id: String,
    @Column(name = "merchant_id", nullable = false) val merchantId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchant_payout_accruals_merchant"))
    val merchant: MerchantRecord? = null,
    @Column(name = "order_id", nullable = false) val orderId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchant_payout_accruals_order"))
    val order: CustomerOrderRecord? = null,
    @Column(name = "source_order_item_id", nullable = false) val sourceOrderItemId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_order_item_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchant_payout_accruals_source_order_item"))
    val sourceOrderItem: MerchantSubOrder? = null,
    @Column(name = "merchant_net_cfa", nullable = false) val merchantNetCfa: Int,
    @Column(name = "commission_cfa", nullable = false) val commissionCfa: Int,
    @Column(name = "platform_margin_cfa", nullable = false) val platformMarginCfa: Int,
    @Column(name = "package_received_at", nullable = false) val packageReceivedAt: Instant,
    @Column(name = "payout_eligible_at", nullable = false) var payoutEligibleAt: Instant,
    @Column(name = "payout_due_by", nullable = false) val payoutDueBy: Instant,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: MerchantPayoutStatus,
    @Enumerated(EnumType.STRING) @Column(name = "workflow_type", nullable = false) val workflowType: SettlementWorkflowType,
    @Column(name = "active_return_hold", nullable = false) var activeReturnHold: Boolean,
    @Column(name = "active_dispute_hold", nullable = false) var activeDisputeHold: Boolean,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "settlement_ledger_entries")
class SettlementLedgerEntryRecord(
    @Id val id: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val account: SettlementLedgerAccount,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val direction: SettlementLedgerDirection,
    @Column(name = "amount_cfa", nullable = false) val amountCfa: Int,
    @Column(name = "merchant_id") val merchantId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_settlement_ledger_entries_merchant"))
    val merchant: MerchantRecord? = null,
    @Column(name = "courier_id") val courierId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courier_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_settlement_ledger_entries_courier"))
    val courier: CourierRecord? = null,
    @Column(name = "relay_point_id") val relayPointId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_point_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_settlement_ledger_entries_relay_point"))
    val relayPoint: RelayPointRecord? = null,
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false) val sourceType: SettlementSourceType,
    @Column(name = "source_id", nullable = false) val sourceId: String,
    @Column(nullable = false) val description: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Enumerated(EnumType.STRING) @Column(name = "workforce_type") val workforceType: dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType? = null,
)

interface MerchantPayoutAccrualRecordRepository : JpaRepository<MerchantPayoutAccrualRecord, String> {
    fun findByMerchantIdOrderByCreatedAtDesc(merchantId: String): List<MerchantPayoutAccrualRecord>
    fun findByMerchantIdAndStatusOrderByCreatedAtDesc(
        merchantId: String,
        status: MerchantPayoutStatus,
    ): List<MerchantPayoutAccrualRecord>
    fun countByStatusIn(statuses: Collection<MerchantPayoutStatus>): Long
    fun findByStatusAndPayoutEligibleAtLessThanEqualOrderByPayoutEligibleAtAsc(
        status: MerchantPayoutStatus,
        evaluatedAt: Instant,
    ): List<MerchantPayoutAccrualRecord>
}

interface SettlementLedgerEntryRecordRepository : JpaRepository<SettlementLedgerEntryRecord, String> {
    fun findBySourceTypeAndSourceIdOrderByCreatedAtAsc(
        sourceType: SettlementSourceType,
        sourceId: String,
    ): List<SettlementLedgerEntryRecord>

    fun findByCourierIdOrderByCreatedAtDesc(courierId: String): List<SettlementLedgerEntryRecord>
}

interface SettlementCourierRecordRepository : JpaRepository<CourierRecord, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from CourierRecord c where c.id = :id")
    fun lockForAdministration(id: String): CourierRecord?

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from CourierRecord c where c.status = 'ACTIVE' order by c.id")
    fun lockActiveForDispatch(): List<CourierRecord>

    fun findByUserId(userId: String): CourierRecord?
}

@Service
class SettlementPersistenceService(
    private val domain: SettlementLedgerService = SettlementLedgerService(),
    private val payouts: MerchantPayoutAccrualRecordRepository,
    private val ledger: SettlementLedgerEntryRecordRepository,
    private val couriers: SettlementCourierRecordRepository,
    private val references: PublicReferenceRegistry? = null,
) {
    @Transactional
    fun accrueMerchantPayout(command: MerchantPayoutAccrualCommand): SettlementResult {
        payouts.findById(command.accrualId).orElse(null)?.let { return SettlementResult.Accrued(it.toDomain(ledger.findBySourceTypeAndSourceIdOrderByCreatedAtAsc(SettlementSourceType.OrderItem, it.sourceOrderItemId))) }
        val result = domain.accrueMerchantPayout(command)
        if (result is SettlementResult.Accrued) {
            payouts.save(result.payout.toRecord())
            result.payout.ledgerEntries.forEach { ledger.save(it.toRecord()) }
        }
        return result
    }

    @Transactional
    fun postDeliveryShortfall(command: DeliveryShortfallCommand): SettlementResult {
        if (ledger.existsById(command.entryId)) {
            return SettlementResult.Posted(ledger.findById(command.entryId).orElseThrow().toDomain())
        }
        val result = domain.postDeliveryShortfall(command)
        if (result is SettlementResult.Posted && result.entry != null) ledger.save(result.entry.toRecord())
        return result
    }

    @Transactional
    fun postRelayStorageFee(command: RelayStorageFeeLedgerCommand): SettlementResult {
        if (ledger.existsById(command.entryId)) {
            return SettlementResult.Posted(ledger.findById(command.entryId).orElseThrow().toDomain())
        }
        val result = domain.postRelayStorageFee(command)
        if (result is SettlementResult.Posted && result.entry != null) ledger.save(result.entry.toRecord())
        return result
    }

    @Transactional
    fun adjust(command: SettlementAdjustmentCommand): SettlementResult {
        if (ledger.existsById(command.adjustmentEntryId)) {
            return SettlementResult.Posted(ledger.findById(command.adjustmentEntryId).orElseThrow().toDomain())
        }
        val result = domain.adjust(command)
        if (result is SettlementResult.Posted && result.entry != null) ledger.save(result.entry.toRecord())
        return result
    }

    @Transactional
    fun evaluateEligible(evaluatedAt: Instant = Instant.now()): List<MerchantPayoutAccrual> =
        payouts.findByStatusAndPayoutEligibleAtLessThanEqualOrderByPayoutEligibleAtAsc(MerchantPayoutStatus.Accrued, evaluatedAt)
            .filter { !it.activeReturnHold && !it.activeDisputeHold }
            .map { record ->
                record.status = MerchantPayoutStatus.Eligible
                record.updatedAt = evaluatedAt
                payouts.save(record).toDomain(ledger.findBySourceTypeAndSourceIdOrderByCreatedAtAsc(SettlementSourceType.OrderItem, record.sourceOrderItemId))
            }

    @Transactional(readOnly = true)
    fun listMerchantPayouts(
        merchantId: String,
        status: MerchantPayoutStatus? = null,
    ): List<MerchantPayoutAccrual> {
        require(merchantId.isNotBlank()) { "merchantId is required." }
        val records = status?.let {
            payouts.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, it)
        } ?: payouts.findByMerchantIdOrderByCreatedAtDesc(merchantId)
        return records.map { record ->
            // Imported legacy source links must not disclose another store's ledger rows.
            record.toDomain(ledger.findBySourceTypeAndSourceIdOrderByCreatedAtAsc(SettlementSourceType.OrderItem, record.sourceOrderItemId)
                .filter { it.merchantId == record.merchantId })
        }
    }

    @Transactional(readOnly = true)
    fun listLedgerEntries(sourceType: SettlementSourceType, sourceId: String): List<SettlementLedgerEntry> {
        require(sourceId.isNotBlank()) { "sourceId is required." }
        return ledger.findBySourceTypeAndSourceIdOrderByCreatedAtAsc(sourceType, sourceId).map { it.toDomain() }
    }

    @Transactional(readOnly = true)
    fun countPayoutsByStatus(statuses: Collection<MerchantPayoutStatus>): Long {
        require(statuses.isNotEmpty()) { "At least one payout status is required." }
        return payouts.countByStatusIn(statuses)
    }

    @Transactional(readOnly = true)
    fun riderPayoutSummary(userId: String): RiderPayoutSummaryResponse {
        val context = riderPayoutContext(userId)
        val rows = ledger.findByCourierIdOrderByCreatedAtDesc(context.courierId).map { it.toDomain() }
        val classified = classifyRiderEntries(context, rows)
        fun signed(entry: SettlementLedgerEntry): Long = entry.amountCfa.toLong() * if (entry.direction == SettlementLedgerDirection.Credit) 1 else -1
        fun total(kind: RiderLedgerKind): Long = classified.filter { it.second == kind }.sumOf { signed(it.first) }
        fun money(value: Long): Int {
            require(value <= Int.MAX_VALUE) { "Recorded payout balance requires financial review." }
            return value.coerceAtLeast(0).toInt()
        }
        val earned = total(RiderLedgerKind.EARNING)
        val held = (-total(RiderLedgerKind.HOLD)).coerceAtLeast(0)
        val unverified = (-total(RiderLedgerKind.TRANSFER)).coerceAtLeast(0)
        return RiderPayoutSummaryResponse(
            courierReference = references.referenceOrNull(PublicReferenceKind.COURIER, context.courierId),
            riderId = context.courierId, workforceType = context.workforceType, payable = context.payable,
            availableBalanceCfa = money(earned - held - unverified), onHoldCfa = money(held),
            paidOrProcessingCfa = 0, historyCount = rows.size,
            message = "Recorded accruals only; provider transfers are not integrated. Salaried missions do not accrue independent pay. Historical freelance liabilities remain recorded.",
            earnedTotalCfa = money(earned),
            settledMissionCount = classified.filter { it.second == RiderLedgerKind.EARNING && it.first.account == SettlementLedgerAccount.CourierPayable &&
                it.first.sourceType == SettlementSourceType.DeliveryMission && it.first.direction == SettlementLedgerDirection.Credit }.map { it.first.sourceId }.distinct().size,
            unverifiedTransferCfa = money(unverified),
            reviewRequiredCfa = money(classified.filter { it.second == RiderLedgerKind.REVIEW }.sumOf { it.first.amountCfa.toLong() }),
        )
    }

    @Transactional(readOnly = true)
    fun riderPayoutHistory(userId: String): List<RiderPayoutHistoryItemResponse> {
        val context = riderPayoutContext(userId)
        val rows = ledger.findByCourierIdOrderByCreatedAtDesc(context.courierId).map { it.toDomain() }
        return classifyRiderEntries(context, rows).map { (entry, kind) ->
            val status = when (kind) {
                RiderLedgerKind.EARNING -> if (entry.account == SettlementLedgerAccount.Adjustment || entry.direction == SettlementLedgerDirection.Debit) RiderPayoutEntryStatus.CORRECTED else RiderPayoutEntryStatus.AVAILABLE
                RiderLedgerKind.HOLD -> RiderPayoutEntryStatus.ON_HOLD
                RiderLedgerKind.TRANSFER, RiderLedgerKind.REVIEW -> RiderPayoutEntryStatus.REVIEW_REQUIRED
                RiderLedgerKind.OTHER -> RiderPayoutEntryStatus.NOT_APPLICABLE
            }
            RiderPayoutHistoryItemResponse(entry.id, entry.sourceId, entry.account, entry.direction, entry.amountCfa,
                if (kind in setOf(RiderLedgerKind.EARNING, RiderLedgerKind.HOLD)) entry.amountCfa else 0,
                status, kind == RiderLedgerKind.EARNING && entry.direction == SettlementLedgerDirection.Credit, entry.description, entry.createdAt, entry.workforceType,
                publicReference = references.referenceOrNull(PublicReferenceKind.LEDGER, entry.id),
                missionReference = if (entry.sourceType == SettlementSourceType.DeliveryMission) references.referenceOrNull(PublicReferenceKind.DELIVERY, entry.sourceId) else null)
        }
    }

    private fun classifyRiderEntries(context: RiderPayoutContext, rows: List<SettlementLedgerEntry>): List<Pair<SettlementLedgerEntry, RiderLedgerKind>> {
        val byId = rows.associateBy { it.id }
        fun historicalFreelance(entry: SettlementLedgerEntry): Boolean = entry.workforceType == dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType.Freelancer ||
            (entry.workforceType == null && (context.payable || rows.any { it.sourceType == entry.sourceType && it.sourceId == entry.sourceId &&
                it.account == SettlementLedgerAccount.CourierPayable && it.workforceType == dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType.Freelancer }))
        fun original(entry: SettlementLedgerEntry): SettlementLedgerEntry? {
            var current = entry
            val seen = mutableSetOf<String>()
            while (current.account == SettlementLedgerAccount.Adjustment) {
                if (!seen.add(current.id) || seen.size > 32) return null
                current = byId[current.sourceId] ?: ledger.findById(current.sourceId).orElse(null)?.toDomain() ?: return null
                if (current.courierId != context.courierId) return null
            }
            return current
        }
        return rows.map { entry ->
            val kind = when {
                entry.sourceType == SettlementSourceType.Payout && entry.account == SettlementLedgerAccount.CourierPayable -> RiderLedgerKind.TRANSFER
                entry.account == SettlementLedgerAccount.CourierPayable -> when {
                    historicalFreelance(entry) -> RiderLedgerKind.EARNING
                    entry.workforceType == dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType.SequoSalaried -> RiderLedgerKind.OTHER
                    else -> RiderLedgerKind.REVIEW
                }
                entry.account == SettlementLedgerAccount.Hold -> if (historicalFreelance(entry)) RiderLedgerKind.HOLD else RiderLedgerKind.REVIEW
                entry.account == SettlementLedgerAccount.Adjustment -> original(entry)?.let { source ->
                    if (source.account != SettlementLedgerAccount.CourierPayable) RiderLedgerKind.OTHER
                    else if (source.sourceType == SettlementSourceType.Payout) RiderLedgerKind.TRANSFER
                    else if (historicalFreelance(source)) RiderLedgerKind.EARNING else RiderLedgerKind.REVIEW
                } ?: RiderLedgerKind.REVIEW
                else -> RiderLedgerKind.OTHER
            }
            entry to kind
        }
    }

    private fun riderPayoutContext(userId: String): RiderPayoutContext {
        require(userId.isNotBlank()) { "userId is required." }
        val courier = couriers.findByUserId(userId) ?: throw org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND, "A linked rider account is required for payout reads.")
        return RiderPayoutContext(courier.id, courier.workforceType?.takeIf { it.isNotBlank() } ?: "UNKNOWN",
            courierWorkforce(courier.workforceType) == dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType.Freelancer)
    }

}

data class RiderPayoutSummaryResponse(
    val riderId: String,
    val workforceType: String,
    val payable: Boolean,
    val availableBalanceCfa: Int,
    val onHoldCfa: Int,
    val paidOrProcessingCfa: Int,
    val historyCount: Int,
    val message: String,
    val earnedTotalCfa: Int = 0,
    val settledMissionCount: Int = 0,
    val paidCfa: Int = 0,
    val processingCfa: Int = 0,
    val transferStatus: String = "NOT_INTEGRATED",
    val unverifiedTransferCfa: Int = 0,
    val reviewRequiredCfa: Int = 0,
    val courierReference: String? = null,
)

enum class RiderPayoutEntryStatus {
    NOT_APPLICABLE,
    AVAILABLE,
    ON_HOLD,
    PROCESSING,
    PAID,
    CORRECTED,
    REVIEW_REQUIRED,
}

data class RiderPayoutHistoryItemResponse(
    val id: String,
    val missionId: String,
    val account: SettlementLedgerAccount,
    val direction: SettlementLedgerDirection,
    val amountCfa: Int,
    val payableAmountCfa: Int,
    val status: RiderPayoutEntryStatus,
    val payableToRider: Boolean,
    val description: String,
    val createdAt: Instant,
    val workforceType: dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType? = null,
    val publicReference: String? = null,
    val missionReference: String? = null,
)

private data class RiderPayoutContext(
    val courierId: String,
    val workforceType: String,
    val payable: Boolean,
)

private enum class RiderLedgerKind { EARNING, HOLD, TRANSFER, REVIEW, OTHER }

private fun MerchantPayoutAccrual.toRecord() = MerchantPayoutAccrualRecord(
    id = id,
    merchantId = merchantId,
    orderId = orderId,
    sourceOrderItemId = sourceOrderItemId,
    merchantNetCfa = merchantNetCfa,
    commissionCfa = commissionCfa,
    platformMarginCfa = platformMarginCfa,
    packageReceivedAt = packageReceivedAt,
    payoutEligibleAt = payoutEligibleAt,
    payoutDueBy = payoutDueBy,
    status = status,
    workflowType = workflowType,
    activeReturnHold = activeReturnHold,
    activeDisputeHold = activeDisputeHold,
    createdAt = packageReceivedAt,
    updatedAt = packageReceivedAt,
)

private fun MerchantPayoutAccrualRecord.toDomain(entries: List<SettlementLedgerEntryRecord>) = MerchantPayoutAccrual(
    id, merchantId, orderId, sourceOrderItemId, merchantNetCfa, commissionCfa, platformMarginCfa,
    packageReceivedAt, payoutEligibleAt, payoutDueBy, status, workflowType,
    activeReturnHold, activeDisputeHold, entries.map { it.toDomain() },
)

private fun SettlementLedgerEntry.toRecord() = SettlementLedgerEntryRecord(
    id = id,
    account = account,
    direction = direction,
    amountCfa = amountCfa,
    merchantId = merchantId,
    courierId = courierId,
    relayPointId = relayPointId,
    sourceType = sourceType,
    sourceId = sourceId,
    description = description,
    createdAt = createdAt,
    workforceType = workforceType,
)

private fun SettlementLedgerEntryRecord.toDomain() = SettlementLedgerEntry(
    id, account, direction, amountCfa, merchantId, courierId, relayPointId,
    sourceType, sourceId, description, createdAt, workforceType,
)
