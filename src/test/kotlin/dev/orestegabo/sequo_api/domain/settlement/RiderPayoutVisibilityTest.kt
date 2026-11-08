package dev.orestegabo.sequo_api.domain.settlement

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.web.server.ResponseStatusException

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
    fun legacyPayableWithoutWorkforceSnapshotRequiresReviewForCurrentlySalariedCourier() {
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
        assertEquals(650, summary.reviewRequiredCfa)
        assertEquals(RiderPayoutEntryStatus.REVIEW_REQUIRED, history.single().status)
        assertEquals(false, history.single().payableToRider)
    }

    @Test
    fun earnedFreelancerLiabilitySurvivesLaterSalariedEmployment() {
        Mockito.`when`(couriers.findByUserId("rider-user-1")).thenReturn(
            CourierRecord("courier-1", userId = "rider-user-1", workforceType = "SEQUO_SALARIED")
        )
        Mockito.`when`(ledger.findByCourierIdOrderByCreatedAtDesc("courier-1")).thenReturn(listOf(
            ledgerRecord("earned-while-freelance", SettlementLedgerAccount.CourierPayable,
                SettlementLedgerDirection.Credit, 650, workforceType = DeliveryWorkforceType.Freelancer),
        ))

        val summary = service.riderPayoutSummary("rider-user-1")
        assertEquals(650, summary.earnedTotalCfa)
        assertEquals(650, summary.availableBalanceCfa)
        assertEquals(0, summary.reviewRequiredCfa)
        assertEquals(RiderPayoutEntryStatus.AVAILABLE, service.riderPayoutHistory("rider-user-1").single().status)
    }

    @Test
    fun liabilityCorrectionsHoldsAndUnverifiedTransfersAreNettedWithoutClaimingProviderPayment() {
        Mockito.`when`(couriers.findByUserId("rider-user-1")).thenReturn(
            CourierRecord("courier-1", userId = "rider-user-1", workforceType = "FREELANCER")
        )
        val entries = listOf(
            ledgerRecord("earned", SettlementLedgerAccount.CourierPayable, SettlementLedgerDirection.Credit, 1000),
            ledgerRecord("reversal", SettlementLedgerAccount.CourierPayable, SettlementLedgerDirection.Debit, 200),
            ledgerRecord("correction-debit", SettlementLedgerAccount.Adjustment, SettlementLedgerDirection.Debit, 100,
                sourceType = SettlementSourceType.Adjustment, sourceId = "earned"),
            ledgerRecord("correction-credit", SettlementLedgerAccount.Adjustment, SettlementLedgerDirection.Credit, 50,
                sourceType = SettlementSourceType.Adjustment, sourceId = "earned"),
            ledgerRecord("hold", SettlementLedgerAccount.Hold, SettlementLedgerDirection.Debit, 100),
            ledgerRecord("provider-unverified", SettlementLedgerAccount.CourierPayable, SettlementLedgerDirection.Debit, 100,
                sourceType = SettlementSourceType.Payout, sourceId = "unverified-payout"),
            // A SEQUO expense is never money payable to the courier and must not reduce their liability.
            ledgerRecord("shortfall", SettlementLedgerAccount.SequoDeliveryShortfallExpense, SettlementLedgerDirection.Debit, 250),
        )
        Mockito.`when`(ledger.findByCourierIdOrderByCreatedAtDesc("courier-1")).thenReturn(entries)

        val summary = service.riderPayoutSummary("rider-user-1")
        assertEquals(750, summary.earnedTotalCfa)
        assertEquals(100, summary.onHoldCfa)
        assertEquals(100, summary.unverifiedTransferCfa)
        assertEquals(550, summary.availableBalanceCfa)
        assertEquals(0, summary.reviewRequiredCfa)
        assertEquals(0, summary.paidCfa)
        assertEquals(0, summary.processingCfa)
        assertEquals(0, summary.paidOrProcessingCfa)
        assertEquals("NOT_INTEGRATED", summary.transferStatus)
        assertEquals(RiderPayoutEntryStatus.REVIEW_REQUIRED,
            service.riderPayoutHistory("rider-user-1").single { it.id == "provider-unverified" }.status)

        val correctedTransfer = entries + ledgerRecord("transfer-correction", SettlementLedgerAccount.Adjustment,
            SettlementLedgerDirection.Credit, 100, sourceType = SettlementSourceType.Adjustment, sourceId = "provider-unverified")
        Mockito.`when`(ledger.findByCourierIdOrderByCreatedAtDesc("courier-1")).thenReturn(correctedTransfer)
        val corrected = service.riderPayoutSummary("rider-user-1")
        assertEquals(750, corrected.earnedTotalCfa)
        assertEquals(0, corrected.unverifiedTransferCfa)
        assertEquals(100, corrected.onHoldCfa)
        assertEquals(650, corrected.availableBalanceCfa)
        assertEquals(0, corrected.paidOrProcessingCfa)

        Mockito.`when`(ledger.findByCourierIdOrderByCreatedAtDesc("courier-1")).thenReturn(correctedTransfer +
            ledgerRecord("hold-released", SettlementLedgerAccount.Hold, SettlementLedgerDirection.Credit, 100))
        val released = service.riderPayoutSummary("rider-user-1")
        assertEquals(0, released.onHoldCfa)
        assertEquals(750, released.availableBalanceCfa)
        assertEquals(750, released.earnedTotalCfa)
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

    @Test
    fun unlinkedRiderCannotReadAnotherCouriersLedgerByMatchingItsRecordId() {
        Mockito.`when`(couriers.findByUserId("orphan-user")).thenReturn(null)
        Mockito.`when`(couriers.findById("orphan-user")).thenReturn(Optional.of(
            CourierRecord("orphan-user", userId = "different-owner", workforceType = "FREELANCER")
        ))

        val summary = assertFailsWith<ResponseStatusException> { service.riderPayoutSummary("orphan-user") }
        val history = assertFailsWith<ResponseStatusException> { service.riderPayoutHistory("orphan-user") }
        assertEquals(404, summary.statusCode.value())
        assertEquals(404, history.statusCode.value())
        Mockito.verifyNoInteractions(ledger)
        Mockito.verify(couriers, Mockito.never()).findById("orphan-user")
    }

    private fun ledgerRecord(
        id: String,
        account: SettlementLedgerAccount,
        direction: SettlementLedgerDirection,
        amountCfa: Int,
        workforceType: DeliveryWorkforceType? = null,
        sourceType: SettlementSourceType = SettlementSourceType.DeliveryMission,
        sourceId: String = "mission-1",
    ): SettlementLedgerEntryRecord =
        SettlementLedgerEntryRecord(
            id = id,
            account = account,
            direction = direction,
            amountCfa = amountCfa,
            courierId = "courier-1",
            sourceType = sourceType,
            sourceId = sourceId,
            workforceType = workforceType,
            description = "Rider payout visibility test entry.",
            createdAt = Instant.parse("2026-10-10T10:00:00Z"),
        )
}
