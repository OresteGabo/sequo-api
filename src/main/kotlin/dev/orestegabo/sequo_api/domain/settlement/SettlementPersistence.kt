package dev.orestegabo.sequo_api.domain.settlement

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
@Table(name = "merchant_payout_accruals")
class MerchantPayoutAccrualRecord(
    @Id val id: String,
    @Column(name = "merchant_id", nullable = false) val merchantId: String,
    @Column(name = "order_id", nullable = false) val orderId: String,
    @Column(name = "source_order_item_id", nullable = false) val sourceOrderItemId: String,
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
    @Column(name = "courier_id") val courierId: String? = null,
    @Column(name = "relay_point_id") val relayPointId: String? = null,
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false) val sourceType: SettlementSourceType,
    @Column(name = "source_id", nullable = false) val sourceId: String,
    @Column(nullable = false) val description: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
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
}

@Service
class SettlementPersistenceService(
    private val domain: SettlementLedgerService = SettlementLedgerService(),
    private val payouts: MerchantPayoutAccrualRecordRepository,
    private val ledger: SettlementLedgerEntryRecordRepository,
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
            record.toDomain(ledger.findBySourceTypeAndSourceIdOrderByCreatedAtAsc(SettlementSourceType.OrderItem, record.sourceOrderItemId))
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
}

private fun MerchantPayoutAccrual.toRecord() = MerchantPayoutAccrualRecord(
    id, merchantId, orderId, sourceOrderItemId, merchantNetCfa, commissionCfa, platformMarginCfa,
    packageReceivedAt, payoutEligibleAt, payoutDueBy, status, workflowType,
    activeReturnHold, activeDisputeHold, packageReceivedAt, packageReceivedAt,
)

private fun MerchantPayoutAccrualRecord.toDomain(entries: List<SettlementLedgerEntryRecord>) = MerchantPayoutAccrual(
    id, merchantId, orderId, sourceOrderItemId, merchantNetCfa, commissionCfa, platformMarginCfa,
    packageReceivedAt, payoutEligibleAt, payoutDueBy, status, workflowType,
    activeReturnHold, activeDisputeHold, entries.map { it.toDomain() },
)

private fun SettlementLedgerEntry.toRecord() = SettlementLedgerEntryRecord(
    id, account, direction, amountCfa, merchantId, courierId, relayPointId,
    sourceType, sourceId, description, createdAt,
)

private fun SettlementLedgerEntryRecord.toDomain() = SettlementLedgerEntry(
    id, account, direction, amountCfa, merchantId, courierId, relayPointId,
    sourceType, sourceId, description, createdAt,
)
