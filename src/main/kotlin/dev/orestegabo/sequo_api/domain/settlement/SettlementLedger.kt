package dev.orestegabo.sequo_api.domain.settlement

import java.time.Duration
import java.time.Instant

enum class SettlementLedgerDirection {
    Debit,
    Credit,
}

enum class SettlementLedgerAccount {
    MerchantPayable,
    CourierPayable,
    RelayPayable,
    SequoCommissionRevenue,
    SequoPlatformMarginRevenue,
    SequoDeliveryShortfallExpense,
    RefundLiability,
    Adjustment,
    Hold,
}

enum class SettlementSourceType {
    OrderItem,
    DeliveryMission,
    RelayParcel,
    ReturnRequest,
    Refund,
    Payout,
    Adjustment,
}

enum class MerchantPayoutStatus {
    Accrued,
    HeldReturnWindow,
    HeldDispute,
    Eligible,
    Batched,
    Approved,
    SentToProvider,
    Paid,
    Failed,
    Adjusted,
}

enum class SettlementWorkflowType {
    DeliveryConfirmed,
    CustomerPickupConfirmed,
    SequoCustodyConfirmed,
}

data class SettlementLedgerEntry(
    val id: String,
    val account: SettlementLedgerAccount,
    val direction: SettlementLedgerDirection,
    val amountCfa: Int,
    val merchantId: String? = null,
    val courierId: String? = null,
    val relayPointId: String? = null,
    val sourceType: SettlementSourceType,
    val sourceId: String,
    val description: String,
    val createdAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "id is required." }
        require(amountCfa > 0) { "amountCfa must be positive." }
        require(sourceId.isNotBlank()) { "sourceId is required." }
        require(description.isNotBlank()) { "description is required." }
    }
}

data class MerchantPayoutAccrual(
    val id: String,
    val merchantId: String,
    val orderId: String,
    val sourceOrderItemId: String,
    val merchantNetCfa: Int,
    val commissionCfa: Int,
    val platformMarginCfa: Int,
    val packageReceivedAt: Instant,
    val payoutEligibleAt: Instant,
    val payoutDueBy: Instant,
    val status: MerchantPayoutStatus,
    val workflowType: SettlementWorkflowType,
    val activeReturnHold: Boolean,
    val activeDisputeHold: Boolean,
    val ledgerEntries: List<SettlementLedgerEntry>,
) {
    init {
        require(id.isNotBlank()) { "id is required." }
        require(merchantId.isNotBlank()) { "merchantId is required." }
        require(orderId.isNotBlank()) { "orderId is required." }
        require(sourceOrderItemId.isNotBlank()) { "sourceOrderItemId is required." }
        require(merchantNetCfa >= 0) { "merchantNetCfa cannot be negative." }
        require(commissionCfa >= 0) { "commissionCfa cannot be negative." }
        require(platformMarginCfa >= 0) { "platformMarginCfa cannot be negative." }
        require(!payoutDueBy.isBefore(packageReceivedAt)) { "payoutDueBy cannot be before packageReceivedAt." }
        require(!payoutEligibleAt.isBefore(packageReceivedAt)) {
            "payoutEligibleAt cannot be before packageReceivedAt."
        }
    }
}

data class MerchantPayoutAccrualCommand(
    val accrualId: String,
    val merchantId: String,
    val orderId: String,
    val sourceOrderItemId: String,
    val merchantNetCfa: Int,
    val commissionCfa: Int,
    val platformMarginCfa: Int,
    val packageReceivedAt: Instant,
    val workflowType: SettlementWorkflowType,
    val activeReturnHold: Boolean = false,
    val activeDisputeHold: Boolean = false,
)

data class DeliveryShortfallCommand(
    val entryId: String,
    val deliveryMissionId: String,
    val customerDeliveryFeeCfa: Int,
    val finalCourierFeeCfa: Int,
    val courierId: String,
    val createdAt: Instant,
)

data class SettlementAdjustmentCommand(
    val adjustmentEntryId: String,
    val originalEntry: SettlementLedgerEntry,
    val amountCfa: Int,
    val direction: SettlementLedgerDirection,
    val reason: String,
    val createdAt: Instant,
)

data class SettlementRejection(
    val code: String,
    val message: String,
)

sealed class SettlementResult {
    data class Accrued(val payout: MerchantPayoutAccrual) : SettlementResult()
    data class Posted(val entry: SettlementLedgerEntry?) : SettlementResult()
    data class Rejected(val rejection: SettlementRejection) : SettlementResult()
}

class SettlementLedgerService(
    private val payoutDueWithin: Duration = Duration.ofDays(7),
    private val returnWindowHold: Duration = Duration.ofHours(72),
) {
    init {
        require(!payoutDueWithin.isNegative && !payoutDueWithin.isZero) { "payoutDueWithin must be positive." }
        require(!returnWindowHold.isNegative) { "returnWindowHold cannot be negative." }
    }

    fun accrueMerchantPayout(command: MerchantPayoutAccrualCommand): SettlementResult {
        validateAccrual(command)?.let { return SettlementResult.Rejected(it) }

        val status = when {
            command.activeDisputeHold -> MerchantPayoutStatus.HeldDispute
            command.activeReturnHold -> MerchantPayoutStatus.HeldReturnWindow
            else -> MerchantPayoutStatus.Accrued
        }
        val payoutEligibleAt = if (command.activeReturnHold) {
            command.packageReceivedAt.plus(returnWindowHold)
        } else {
            command.packageReceivedAt
        }
        val ledgerEntries = buildList {
            if (command.merchantNetCfa > 0) {
                add(
                    SettlementLedgerEntry(
                        id = "${command.accrualId}:merchant-payable",
                        account = SettlementLedgerAccount.MerchantPayable,
                        direction = SettlementLedgerDirection.Credit,
                        amountCfa = command.merchantNetCfa,
                        merchantId = command.merchantId,
                        sourceType = SettlementSourceType.OrderItem,
                        sourceId = command.sourceOrderItemId,
                        description = "Merchant payable accrued after package receipt.",
                        createdAt = command.packageReceivedAt,
                    )
                )
            }
            if (command.commissionCfa > 0) {
                add(
                    SettlementLedgerEntry(
                        id = "${command.accrualId}:commission",
                        account = SettlementLedgerAccount.SequoCommissionRevenue,
                        direction = SettlementLedgerDirection.Credit,
                        amountCfa = command.commissionCfa,
                        merchantId = command.merchantId,
                        sourceType = SettlementSourceType.OrderItem,
                        sourceId = command.sourceOrderItemId,
                        description = "Sequo merchant commission revenue.",
                        createdAt = command.packageReceivedAt,
                    )
                )
            }
            if (command.platformMarginCfa > 0) {
                add(
                    SettlementLedgerEntry(
                        id = "${command.accrualId}:platform-margin",
                        account = SettlementLedgerAccount.SequoPlatformMarginRevenue,
                        direction = SettlementLedgerDirection.Credit,
                        amountCfa = command.platformMarginCfa,
                        merchantId = command.merchantId,
                        sourceType = SettlementSourceType.OrderItem,
                        sourceId = command.sourceOrderItemId,
                        description = "Sequo platform margin revenue.",
                        createdAt = command.packageReceivedAt,
                    )
                )
            }
        }

        return SettlementResult.Accrued(
            MerchantPayoutAccrual(
                id = command.accrualId,
                merchantId = command.merchantId,
                orderId = command.orderId,
                sourceOrderItemId = command.sourceOrderItemId,
                merchantNetCfa = command.merchantNetCfa,
                commissionCfa = command.commissionCfa,
                platformMarginCfa = command.platformMarginCfa,
                packageReceivedAt = command.packageReceivedAt,
                payoutEligibleAt = payoutEligibleAt,
                payoutDueBy = command.packageReceivedAt.plus(payoutDueWithin),
                status = status,
                workflowType = command.workflowType,
                activeReturnHold = command.activeReturnHold,
                activeDisputeHold = command.activeDisputeHold,
                ledgerEntries = ledgerEntries,
            )
        )
    }

    fun markEligible(payout: MerchantPayoutAccrual, evaluatedAt: Instant): MerchantPayoutAccrual =
        if (
            payout.status == MerchantPayoutStatus.Accrued &&
            !evaluatedAt.isBefore(payout.payoutEligibleAt) &&
            !payout.activeReturnHold &&
            !payout.activeDisputeHold
        ) {
            payout.copy(status = MerchantPayoutStatus.Eligible)
        } else {
            payout
        }

    fun postDeliveryShortfall(command: DeliveryShortfallCommand): SettlementResult {
        if (command.entryId.isBlank()) return rejected("missing_entry_id", "Ledger entry id is required.")
        if (command.deliveryMissionId.isBlank()) return rejected("missing_delivery_mission_id", "Delivery mission id is required.")
        if (command.courierId.isBlank()) return rejected("missing_courier_id", "Courier id is required.")
        if (command.customerDeliveryFeeCfa < 0 || command.finalCourierFeeCfa < 0) {
            return rejected("invalid_delivery_amount", "Delivery amounts cannot be negative.")
        }
        val shortfall = command.finalCourierFeeCfa - command.customerDeliveryFeeCfa
        if (shortfall <= 0) return SettlementResult.Posted(null)

        return SettlementResult.Posted(
            SettlementLedgerEntry(
                id = command.entryId,
                account = SettlementLedgerAccount.SequoDeliveryShortfallExpense,
                direction = SettlementLedgerDirection.Debit,
                amountCfa = shortfall,
                courierId = command.courierId,
                sourceType = SettlementSourceType.DeliveryMission,
                sourceId = command.deliveryMissionId,
                description = "Sequo delivery shortfall expense.",
                createdAt = command.createdAt,
            )
        )
    }

    fun adjust(command: SettlementAdjustmentCommand): SettlementResult {
        if (command.adjustmentEntryId.isBlank()) return rejected("missing_adjustment_entry_id", "Adjustment entry id is required.")
        if (command.amountCfa <= 0) return rejected("invalid_adjustment_amount", "Adjustment amount must be positive.")
        if (command.reason.isBlank()) return rejected("missing_adjustment_reason", "Adjustment reason is required.")

        return SettlementResult.Posted(
            SettlementLedgerEntry(
                id = command.adjustmentEntryId,
                account = SettlementLedgerAccount.Adjustment,
                direction = command.direction,
                amountCfa = command.amountCfa,
                merchantId = command.originalEntry.merchantId,
                courierId = command.originalEntry.courierId,
                relayPointId = command.originalEntry.relayPointId,
                sourceType = SettlementSourceType.Adjustment,
                sourceId = command.originalEntry.id,
                description = command.reason,
                createdAt = command.createdAt,
            )
        )
    }

    private fun validateAccrual(command: MerchantPayoutAccrualCommand): SettlementRejection? {
        if (command.accrualId.isBlank()) return SettlementRejection("missing_accrual_id", "Accrual id is required.")
        if (command.merchantId.isBlank()) return SettlementRejection("missing_merchant_id", "Merchant id is required.")
        if (command.orderId.isBlank()) return SettlementRejection("missing_order_id", "Order id is required.")
        if (command.sourceOrderItemId.isBlank()) return SettlementRejection("missing_source_order_item_id", "Source order item id is required.")
        if (command.merchantNetCfa < 0 || command.commissionCfa < 0 || command.platformMarginCfa < 0) {
            return SettlementRejection("invalid_money_amount", "Settlement amounts cannot be negative.")
        }
        if (command.merchantNetCfa == 0 && command.commissionCfa == 0 && command.platformMarginCfa == 0) {
            return SettlementRejection("empty_settlement", "At least one settlement amount is required.")
        }

        return null
    }

    private fun rejected(code: String, message: String): SettlementResult.Rejected =
        SettlementResult.Rejected(SettlementRejection(code, message))
}
