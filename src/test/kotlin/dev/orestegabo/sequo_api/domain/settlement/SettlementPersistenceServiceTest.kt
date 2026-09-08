package dev.orestegabo.sequo_api.domain.settlement

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class SettlementPersistenceServiceTest @Autowired constructor(
    private val service: SettlementPersistenceService,
    private val payouts: MerchantPayoutAccrualRecordRepository,
    private val ledger: SettlementLedgerEntryRecordRepository,
) {
    @Test
    fun `accrual is persisted idempotently with its ledger entries`() {
        ledger.deleteAll()
        payouts.deleteAll()
        val receivedAt = Instant.parse("2026-08-01T10:00:00Z")
        val command = MerchantPayoutAccrualCommand(
            accrualId = "payout-persistence-1",
            merchantId = "merchant-1",
            orderId = "order-1",
            sourceOrderItemId = "item-1",
            merchantNetCfa = 8_000,
            commissionCfa = 1_000,
            platformMarginCfa = 500,
            packageReceivedAt = receivedAt,
            workflowType = SettlementWorkflowType.DeliveryConfirmed,
            activeReturnHold = true,
        )

        val first = service.accrueMerchantPayout(command)
        val second = service.accrueMerchantPayout(command)

        assertTrue(first is SettlementResult.Accrued)
        assertTrue(second is SettlementResult.Accrued)
        assertEquals(1, payouts.count())
        assertEquals(3, ledger.count())
        assertEquals(MerchantPayoutStatus.HeldReturnWindow, second.payout.status)
    }

    @Test
    fun `eligible payouts are promoted only after the hold deadline`() {
        ledger.deleteAll()
        payouts.deleteAll()
        val receivedAt = Instant.parse("2026-08-01T10:00:00Z")
        service.accrueMerchantPayout(
            MerchantPayoutAccrualCommand(
                accrualId = "payout-eligible-1", merchantId = "merchant-2", orderId = "order-2",
                sourceOrderItemId = "item-2", merchantNetCfa = 4_000, commissionCfa = 500,
                platformMarginCfa = 200, packageReceivedAt = receivedAt,
                workflowType = SettlementWorkflowType.CustomerPickupConfirmed,
            )
        )

        assertEquals(0, service.evaluateEligible(receivedAt.minusSeconds(1)).size)
        assertEquals(1, service.evaluateEligible(receivedAt.plusSeconds(1)).size)
        assertEquals(MerchantPayoutStatus.Eligible, payouts.findById("payout-eligible-1").orElseThrow().status)
    }
}
