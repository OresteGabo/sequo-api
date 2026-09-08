package dev.orestegabo.sequo_api.domain.referral

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferralDeliveryCreditServiceTest {

    private val service = ReferralDeliveryCreditService()
    private val now = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun issuesDeliveryCreditWithSourceAndExpirySnapshot() {
        val result = service.issue(issueCommand(amountCfa = 1_000))

        assertTrue(result is ReferralCreditResult.Issued)
        assertEquals("credit-1", result.credit.id)
        assertEquals("customer-1", result.credit.customerId)
        assertEquals("referral-campaign-1", result.credit.sourceReferralId)
        assertEquals(1_000, result.credit.amountCfa)
        assertEquals(1_000, result.credit.remainingCfa)
        assertEquals(ReferralDeliveryCreditStatus.Active, result.credit.status)
        assertEquals(now.plusSeconds(30 * 24 * 60 * 60), result.credit.expiresAt)
    }

    @Test
    fun appliesCreditOnlyToDeliveryFeeAndKeepsRemainingAmount() {
        val credit = issuedCredit(amountCfa = 1_000)

        val result = service.applyToDeliveryFee(
            ReferralCreditApplicationCommand(
                credit = credit,
                customerId = "customer-1",
                deliveryFeeAfterDiscountsCfa = 400,
                use = ReferralCreditUse.DeliveryFee,
                appliedAt = now.plusSeconds(60),
            )
        )

        assertTrue(result is ReferralCreditResult.Accepted)
        assertEquals(400, result.application.appliedAmountCfa)
        assertEquals(600, result.application.remainingAmountCfa)
        assertEquals(0, result.application.deliveryFeeAfterCreditCfa)
        assertEquals(ReferralDeliveryCreditStatus.Active, result.application.credit.status)
        assertEquals("referral-campaign-1", result.application.sourceReferralId)
        assertEquals(1_000, result.application.originalCreditAmountCfa)
    }

    @Test
    fun consumesCreditWhenRemainingAmountReachesZero() {
        val credit = issuedCredit(amountCfa = 300)

        val result = service.applyToDeliveryFee(
            ReferralCreditApplicationCommand(
                credit = credit,
                customerId = "customer-1",
                deliveryFeeAfterDiscountsCfa = 500,
                use = ReferralCreditUse.DeliveryFee,
                appliedAt = now.plusSeconds(60),
            )
        )

        assertTrue(result is ReferralCreditResult.Accepted)
        assertEquals(300, result.application.appliedAmountCfa)
        assertEquals(200, result.application.deliveryFeeAfterCreditCfa)
        assertEquals(0, result.application.remainingAmountCfa)
        assertEquals(ReferralDeliveryCreditStatus.Consumed, result.application.credit.status)
    }

    @Test
    fun rejectsCashWithdrawalWalletTransferAndPayoutUses() {
        val credit = issuedCredit(amountCfa = 1_000)
        val blockedUses = listOf(
            ReferralCreditUse.CashWithdrawal,
            ReferralCreditUse.WalletTransfer,
            ReferralCreditUse.MerchantPayout,
            ReferralCreditUse.CourierPayout,
            ReferralCreditUse.ItemPrice,
            ReferralCreditUse.PlatformMargin,
            ReferralCreditUse.ServiceFee,
            ReferralCreditUse.Tip,
            ReferralCreditUse.Refund,
        )

        blockedUses.forEach { use ->
            val result = service.applyToDeliveryFee(
                ReferralCreditApplicationCommand(
                    credit = credit,
                    customerId = "customer-1",
                    deliveryFeeAfterDiscountsCfa = 400,
                    use = use,
                    appliedAt = now.plusSeconds(60),
                )
            )

            assertTrue(result is ReferralCreditResult.Rejected)
            assertEquals("delivery_credit_only", result.rejection.code)
        }
    }

    @Test
    fun rejectsExpiredRevokedAndWrongCustomerCredits() {
        val credit = issuedCredit(amountCfa = 1_000)
        val expired = service.expireIfNeeded(credit, now.plusSeconds(31 * 24 * 60 * 60))
        val revoked = service.revoke(credit, now.plusSeconds(60))

        val expiredResult = apply(expired)
        val revokedResult = apply(revoked)
        val wrongCustomer = service.applyToDeliveryFee(
            ReferralCreditApplicationCommand(
                credit = credit,
                customerId = "customer-2",
                deliveryFeeAfterDiscountsCfa = 400,
                use = ReferralCreditUse.DeliveryFee,
                appliedAt = now.plusSeconds(60),
            )
        )

        assertTrue(expiredResult is ReferralCreditResult.Rejected)
        assertEquals("credit_not_active", expiredResult.rejection.code)
        assertTrue(revokedResult is ReferralCreditResult.Rejected)
        assertEquals("credit_not_active", revokedResult.rejection.code)
        assertTrue(wrongCustomer is ReferralCreditResult.Rejected)
        assertEquals("customer_mismatch", wrongCustomer.rejection.code)
    }

    @Test
    fun rejectsInvalidIssueRequests() {
        val invalidAmount = service.issue(issueCommand(amountCfa = 0))
        val invalidExpiry = service.issue(
            issueCommand(
                amountCfa = 500,
                expiresAt = now,
            )
        )

        assertTrue(invalidAmount is ReferralCreditResult.Rejected)
        assertEquals("invalid_credit_amount", invalidAmount.rejection.code)
        assertTrue(invalidExpiry is ReferralCreditResult.Rejected)
        assertEquals("invalid_expiry", invalidExpiry.rejection.code)
    }

    private fun apply(credit: ReferralDeliveryCredit): ReferralCreditResult =
        service.applyToDeliveryFee(
            ReferralCreditApplicationCommand(
                credit = credit,
                customerId = "customer-1",
                deliveryFeeAfterDiscountsCfa = 400,
                use = ReferralCreditUse.DeliveryFee,
                appliedAt = now.plusSeconds(60),
            )
        )

    private fun issuedCredit(amountCfa: Int): ReferralDeliveryCredit =
        (service.issue(issueCommand(amountCfa = amountCfa)) as ReferralCreditResult.Issued).credit

    private fun issueCommand(
        amountCfa: Int,
        expiresAt: Instant? = now.plusSeconds(30 * 24 * 60 * 60),
    ): ReferralCreditIssueCommand =
        ReferralCreditIssueCommand(
            creditId = "credit-1",
            customerId = "customer-1",
            sourceReferralId = "referral-campaign-1",
            amountCfa = amountCfa,
            expiresAt = expiresAt,
            issuedAt = now,
        )
}
