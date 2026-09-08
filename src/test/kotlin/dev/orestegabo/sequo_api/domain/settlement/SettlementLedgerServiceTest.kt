package dev.orestegabo.sequo_api.domain.settlement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettlementLedgerServiceTest {

    private val service = SettlementLedgerService()
    private val receivedAt = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun merchantPayoutIsScheduledWithinOneWeekAfterPackageReceipt() {
        val result = service.accrueMerchantPayout(accrualCommand())

        assertTrue(result is SettlementResult.Accrued)
        assertEquals(MerchantPayoutStatus.Accrued, result.payout.status)
        assertEquals(receivedAt, result.payout.payoutEligibleAt)
        assertEquals(receivedAt.plusSeconds(7 * 24 * 60 * 60), result.payout.payoutDueBy)
        assertEquals(3, result.payout.ledgerEntries.size)
        assertEquals(
            SettlementLedgerAccount.MerchantPayable,
            result.payout.ledgerEntries.single { it.id.endsWith(":merchant-payable") }.account,
        )
    }

    @Test
    fun returnWindowHoldDelaysEligibilityButKeepsDueWithinOneWeek() {
        val result = service.accrueMerchantPayout(accrualCommand(activeReturnHold = true))

        assertTrue(result is SettlementResult.Accrued)
        assertEquals(MerchantPayoutStatus.HeldReturnWindow, result.payout.status)
        assertEquals(receivedAt.plusSeconds(72 * 60 * 60), result.payout.payoutEligibleAt)
        assertEquals(receivedAt.plusSeconds(7 * 24 * 60 * 60), result.payout.payoutDueBy)
    }

    @Test
    fun disputeHoldBlocksEligibility() {
        val accrued = service.accrueMerchantPayout(accrualCommand(activeDisputeHold = true)) as SettlementResult.Accrued

        val evaluated = service.markEligible(accrued.payout, receivedAt.plusSeconds(8 * 24 * 60 * 60))

        assertEquals(MerchantPayoutStatus.HeldDispute, evaluated.status)
    }

    @Test
    fun accruedPayoutBecomesEligibleWhenNoHoldIsActive() {
        val accrued = service.accrueMerchantPayout(accrualCommand()) as SettlementResult.Accrued

        val evaluated = service.markEligible(accrued.payout, receivedAt)

        assertEquals(MerchantPayoutStatus.Eligible, evaluated.status)
    }

    @Test
    fun deliveryShortfallPostsSequoExpenseWithoutReducingMerchantPayout() {
        val result = service.postDeliveryShortfall(
            DeliveryShortfallCommand(
                entryId = "shortfall-1",
                deliveryMissionId = "mission-1",
                customerDeliveryFeeCfa = 400,
                finalCourierFeeCfa = 650,
                courierId = "courier-1",
                createdAt = receivedAt,
            )
        )

        assertTrue(result is SettlementResult.Posted)
        assertEquals(SettlementLedgerAccount.SequoDeliveryShortfallExpense, result.entry?.account)
        assertEquals(SettlementLedgerDirection.Debit, result.entry?.direction)
        assertEquals(250, result.entry?.amountCfa)
        assertEquals("courier-1", result.entry?.courierId)
        assertNull(result.entry?.merchantId)
    }

    @Test
    fun noShortfallDoesNotCreateLedgerEntry() {
        val result = service.postDeliveryShortfall(
            DeliveryShortfallCommand(
                entryId = "shortfall-1",
                deliveryMissionId = "mission-1",
                customerDeliveryFeeCfa = 700,
                finalCourierFeeCfa = 650,
                courierId = "courier-1",
                createdAt = receivedAt,
            )
        )

        assertTrue(result is SettlementResult.Posted)
        assertNull(result.entry)
    }

    @Test
    fun adjustmentCreatesSeparateAppendOnlyEntry() {
        val accrued = service.accrueMerchantPayout(accrualCommand()) as SettlementResult.Accrued
        val original = accrued.payout.ledgerEntries.first()

        val adjustment = service.adjust(
            SettlementAdjustmentCommand(
                adjustmentEntryId = "adjustment-1",
                originalEntry = original,
                amountCfa = 300,
                direction = SettlementLedgerDirection.Debit,
                reason = "Merchant-liable refund adjustment.",
                createdAt = receivedAt.plusSeconds(60),
            )
        )

        assertTrue(adjustment is SettlementResult.Posted)
        assertEquals(SettlementLedgerAccount.Adjustment, adjustment.entry?.account)
        assertEquals(original.id, adjustment.entry?.sourceId)
        assertEquals("merchant-1", adjustment.entry?.merchantId)
        assertEquals(300, adjustment.entry?.amountCfa)
        assertEquals(8_500, original.amountCfa)
    }

    @Test
    fun invalidAccrualIsRejected() {
        val result = service.accrueMerchantPayout(
            accrualCommand(
                merchantNetCfa = 0,
                commissionCfa = 0,
                platformMarginCfa = 0,
            )
        )

        assertTrue(result is SettlementResult.Rejected)
        assertEquals("empty_settlement", result.rejection.code)
    }

    private fun accrualCommand(
        merchantNetCfa: Int = 8_500,
        commissionCfa: Int = 1_500,
        platformMarginCfa: Int = 800,
        activeReturnHold: Boolean = false,
        activeDisputeHold: Boolean = false,
    ): MerchantPayoutAccrualCommand =
        MerchantPayoutAccrualCommand(
            accrualId = "settlement-1",
            merchantId = "merchant-1",
            orderId = "order-1",
            sourceOrderItemId = "order-item-1",
            merchantNetCfa = merchantNetCfa,
            commissionCfa = commissionCfa,
            platformMarginCfa = platformMarginCfa,
            packageReceivedAt = receivedAt,
            workflowType = SettlementWorkflowType.SequoCustodyConfirmed,
            activeReturnHold = activeReturnHold,
            activeDisputeHold = activeDisputeHold,
        )
}
