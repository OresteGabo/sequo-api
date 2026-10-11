package dev.orestegabo.sequo_api.domain.settlement

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.security.authentication.TestingAuthenticationToken

class RiderPayoutVisibilityTest {
    private val payouts = Mockito.mock(MerchantPayoutAccrualRecordRepository::class.java)
    private val ledger = Mockito.mock(SettlementLedgerEntryRecordRepository::class.java)
    private val couriers = Mockito.mock(SettlementCourierRecordRepository::class.java)
    private val service = SettlementPersistenceService(
        payouts = payouts,
        ledger = ledger,
        couriers = couriers,
    )

    @Test
    fun freelancerSummaryOnlyCountsCourierPayableCredits() {
        Mockito.`when`(couriers.findByUserId("rider-user-1")).thenReturn(
            CourierRecord(
                id = "courier-1",
                userId = "rider-user-1",
                workforceType = "FREELANCER",
            )
        )
        Mockito.`when`(ledger.findByCourierIdOrderByCreatedAtDesc("courier-1")).thenReturn(
            listOf(
                ledgerRecord(
                    id = "entry-payable",
                    account = SettlementLedgerAccount.CourierPayable,
                    direction = SettlementLedgerDirection.Credit,
                    amountCfa = 650,
                ),
                ledgerRecord(
                    id = "entry-shortfall",
                    account = SettlementLedgerAccount.SequoDeliveryShortfallExpense,
                    direction = SettlementLedgerDirection.Debit,
                    amountCfa = 250,
                ),
            )
        )

        val summary = service.riderPayoutSummary("rider-user-1")
        val history = service.riderPayoutHistory("rider-user-1")

        assertEquals(true, summary.payable)
        assertEquals(650, summary.availableBalanceCfa)
        assertEquals(2, summary.historyCount)
        assertEquals(650, history.first { it.id == "entry-payable" }.payableAmountCfa)
        assertEquals(0, history.first { it.id == "entry-shortfall" }.payableAmountCfa)
    }

    @Test
    fun salariedCourierDoesNotSeeLedgerAsPayable() {
        Mockito.`when`(couriers.findByUserId("salary-user-1")).thenReturn(
            CourierRecord(
                id = "salary-courier-1",
                userId = "salary-user-1",
                workforceType = "SEQUO_SALARIED",
            )
        )
        Mockito.`when`(ledger.findByCourierIdOrderByCreatedAtDesc("salary-courier-1")).thenReturn(
            listOf(
                ledgerRecord(
                    id = "entry-payable",
                    account = SettlementLedgerAccount.CourierPayable,
                    direction = SettlementLedgerDirection.Credit,
                    amountCfa = 650,
                ),
            )
        )

        val summary = service.riderPayoutSummary("salary-user-1")
        val history = service.riderPayoutHistory("salary-user-1")

        assertEquals(false, summary.payable)
        assertEquals(0, summary.availableBalanceCfa)
        assertEquals(RiderPayoutEntryStatus.NOT_APPLICABLE, history.single().status)
        assertEquals(false, history.single().payableToRider)
    }

    @Test
    fun controllerRequiresCourierRoleAndUsesAuthenticatedUser() {
        Mockito.`when`(couriers.findByUserId("rider-user-1")).thenReturn(
            CourierRecord(
                id = "courier-1",
                userId = "rider-user-1",
                workforceType = "FREELANCER",
            )
        )
        Mockito.`when`(ledger.findByCourierIdOrderByCreatedAtDesc("courier-1")).thenReturn(emptyList())
        val controller = RiderPayoutController(service)

        val forbidden = controller.summary(
            TestingAuthenticationToken("customer-1", null),
            "customer-1",
        )
        val ok = controller.summary(
            TestingAuthenticationToken("rider-user-1", null, RoleCode.COURIER.toGrantedAuthority()),
            "rider-user-1",
        )

        assertEquals(403, forbidden.statusCode.value())
        assertEquals(200, ok.statusCode.value())
        assertTrue(ok.body is RiderPayoutSummaryResponse)
    }

    private fun ledgerRecord(
        id: String,
        account: SettlementLedgerAccount,
        direction: SettlementLedgerDirection,
        amountCfa: Int,
    ): SettlementLedgerEntryRecord =
        SettlementLedgerEntryRecord(
            id = id,
            account = account,
            direction = direction,
            amountCfa = amountCfa,
            courierId = "courier-1",
            sourceType = SettlementSourceType.DeliveryMission,
            sourceId = "mission-1",
            description = "Rider payout visibility test entry.",
            createdAt = Instant.parse("2026-10-10T10:00:00Z"),
        )
}
