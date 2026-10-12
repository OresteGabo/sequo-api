package dev.orestegabo.sequo_api.contracts

import dev.orestegabo.sequo_api.domain.bargaining.BargainingResult
import dev.orestegabo.sequo_api.domain.bargaining.BargainingService
import dev.orestegabo.sequo_api.domain.bargaining.BargainingSession
import dev.orestegabo.sequo_api.domain.bargaining.BargainingSessionScope
import dev.orestegabo.sequo_api.domain.bargaining.BargainingStatus
import dev.orestegabo.sequo_api.domain.bargaining.CustomerOfferCommand
import dev.orestegabo.sequo_api.domain.bargaining.MerchantOfferDecisionCommand
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionInput
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionService
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingInput
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.referral.ReferralCreditApplicationCommand
import dev.orestegabo.sequo_api.domain.referral.ReferralCreditIssueCommand
import dev.orestegabo.sequo_api.domain.referral.ReferralCreditResult
import dev.orestegabo.sequo_api.domain.referral.ReferralCreditUse
import dev.orestegabo.sequo_api.domain.referral.ReferralDeliveryCredit
import dev.orestegabo.sequo_api.domain.referral.ReferralDeliveryCreditService
import dev.orestegabo.sequo_api.domain.relay.RelayParcelCategory
import dev.orestegabo.sequo_api.domain.relay.RelayParcelCustodyAction
import dev.orestegabo.sequo_api.domain.relay.RelayParcelCustodyInput
import dev.orestegabo.sequo_api.domain.relay.RelayParcelPolicy
import dev.orestegabo.sequo_api.domain.relay.RelayStorageFeeRequest
import dev.orestegabo.sequo_api.domain.relay.RelayStorageFeeService
import dev.orestegabo.sequo_api.domain.returns.ReturnEligibilityInput
import dev.orestegabo.sequo_api.domain.returns.ReturnItemCategory
import dev.orestegabo.sequo_api.domain.returns.ReturnProcessingResult
import dev.orestegabo.sequo_api.domain.returns.ReturnProcessingService
import dev.orestegabo.sequo_api.domain.returns.ReturnRequestCommand
import dev.orestegabo.sequo_api.domain.subscription.CustomerSubscription
import dev.orestegabo.sequo_api.domain.subscription.CustomerSubscriptionStatus
import dev.orestegabo.sequo_api.domain.subscription.SubscriptionBenefitRequest
import dev.orestegabo.sequo_api.domain.subscription.SubscriptionBenefitsService
import dev.orestegabo.sequo_api.domain.subscription.SubscriptionDeliveryMode
import dev.orestegabo.sequo_api.domain.subscription.SubscriptionTier
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractBusinessParameterReadinessTest {
    private val now = Instant.parse("2026-10-12T10:00:00Z")

    @Test
    fun deliveryPricingKeepsContractMinimumAndPerKilometerParameters() {
        val service = DeliveryPricingService()

        val insideIncludedDistance = service.calculate(DeliveryPricingInput(distanceKm = 5.0))
        val oneExtraKilometer = service.calculate(DeliveryPricingInput(distanceKm = 5.1))
        val threeExtraKilometers = service.calculate(DeliveryPricingInput(distanceKm = 7.2))

        assertEquals(400, DeliveryPricingService.DefaultSettings.minimumDeliveryFeeCfa)
        assertEquals(100, DeliveryPricingService.DefaultSettings.extraKmFeeCfa)
        assertEquals(5, DeliveryPricingService.DefaultSettings.includedKm)
        assertEquals(400, insideIncludedDistance.finalDeliveryFeeCfa)
        assertEquals(500, oneExtraKilometer.finalDeliveryFeeCfa)
        assertEquals(700, threeExtraKilometers.finalDeliveryFeeCfa)
    }

    @Test
    fun commissionRatesRemainConfigurableWithinContractBand() {
        val service = MerchantCommissionService()

        val defaultCommission = service.calculate(
            MerchantCommissionInput(
                merchantId = "merchant-1",
                baseAmountCfa = 10_000,
                platformMarginCfa = 0,
            )
        )
        val lowerOverride = service.calculate(
            MerchantCommissionInput(
                merchantId = "merchant-1",
                baseAmountCfa = 10_000,
                platformMarginCfa = 0,
                merchantOverrideRateBps = 500,
            )
        )

        assertEquals(1500, defaultCommission.commissionRateBps)
        assertEquals(1_500, defaultCommission.commissionCfa)
        assertEquals(500, lowerOverride.commissionRateBps)
        assertEquals(500, lowerOverride.commissionCfa)
    }

    @Test
    fun subscriberDiscountsAndReferralCreditsArePreparedForDeliveryFeesOnly() {
        val subscriptionSnapshot = SubscriptionBenefitsService().calculate(
            SubscriptionBenefitRequest(
                subscription = subscription(),
                customerId = "customer-1",
                deliveryMode = SubscriptionDeliveryMode.FastDelivery,
                eligiblePerKmFeeCfa = 1_000,
                quotedAt = now,
            )
        )
        val credit = issuedReferralCredit(amountCfa = 1_000)
        val applied = ReferralDeliveryCreditService().applyToDeliveryFee(
            ReferralCreditApplicationCommand(
                credit = credit,
                customerId = "customer-1",
                deliveryFeeAfterDiscountsCfa = subscriptionSnapshot.feeAfterBenefitsCfa,
                use = ReferralCreditUse.DeliveryFee,
                appliedAt = now.plusSeconds(60),
            )
        ) as ReferralCreditResult.Accepted
        val rejectedCashUse = ReferralDeliveryCreditService().applyToDeliveryFee(
            ReferralCreditApplicationCommand(
                credit = credit,
                customerId = "customer-1",
                deliveryFeeAfterDiscountsCfa = 850,
                use = ReferralCreditUse.CashWithdrawal,
                appliedAt = now.plusSeconds(60),
            )
        )

        assertEquals(150, subscriptionSnapshot.subscriptionDiscountCfa)
        assertEquals(850, subscriptionSnapshot.feeAfterBenefitsCfa)
        assertEquals(850, applied.application.appliedAmountCfa)
        assertEquals(0, applied.application.deliveryFeeAfterCreditCfa)
        assertTrue(rejectedCashUse is ReferralCreditResult.Rejected)
        assertEquals("delivery_credit_only", rejectedCashUse.rejection.code)
    }

    @Test
    fun bargainingAttemptsAndNegotiatedPriceLockAreEnforced() {
        val service = BargainingService()
        val session = openBargainingSession(service)
        val offered = service.submitCustomerOffer(
            CustomerOfferCommand(
                session = session,
                offerId = "offer-1",
                customerId = "customer-1",
                amountCfa = 8_500,
                offeredAt = now.plusSeconds(60),
            )
        ) as BargainingResult.Accepted
        val accepted = service.acceptCustomerOffer(
            MerchantOfferDecisionCommand(
                session = offered.session,
                merchantId = "merchant-1",
                decidedAt = now.plusSeconds(120),
                acceptedLockId = "lock-1",
            )
        ) as BargainingResult.Accepted

        assertEquals(1, offered.session.customerAttemptsUsed)
        assertEquals(BargainingStatus.Accepted, accepted.session.status)
        assertEquals(8_500, accepted.session.acceptedPriceLock?.acceptedAmountCfa)
        assertEquals(now.plusSeconds(120).plus(Duration.ofHours(24)), accepted.session.acceptedPriceLock?.expiresAt)
    }

    @Test
    fun returnDelayAndExcludedReturnCategoriesAreEnforced() {
        val service = ReturnProcessingService()
        val acceptedAtDeadline = service.requestReturn(returnCommand(requestedAt = now.plus(Duration.ofHours(72))))
        val rejectedAfterDeadline = service.requestReturn(returnCommand(requestedAt = now.plus(Duration.ofHours(72)).plusSeconds(1)))
        val rejectedFood = service.requestReturn(returnCommand(category = ReturnItemCategory.Food))

        assertTrue(acceptedAtDeadline is ReturnProcessingResult.Accepted)
        assertTrue(rejectedAfterDeadline is ReturnProcessingResult.Rejected)
        assertEquals("return_window_expired", rejectedAfterDeadline.rejection.code)
        assertTrue(rejectedFood is ReturnProcessingResult.Rejected)
        assertEquals("item_not_returnable", rejectedFood.rejection.code)
    }

    @Test
    fun relayStorageFeesAndExcludedRelayCategoriesAreEnforced() {
        val policy = RelayParcelPolicy()
        val depositedAt = Instant.parse("2026-10-01T10:00:00Z")
        val storage = RelayStorageFeeService().calculate(
            RelayStorageFeeRequest(
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(17)),
                dailyFeeCfa = 250,
            )
        )
        val custody = policy.evaluateCustody(
            RelayParcelCustodyInput(
                category = RelayParcelCategory.GeneralGoods,
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(14)),
            )
        )

        assertFalse(policy.relayPickupAllowed(RelayParcelCategory.Food))
        assertFalse(policy.relayPickupAllowed(RelayParcelCategory.Perishable))
        assertTrue(policy.relayPickupAllowed(RelayParcelCategory.GeneralGoods))
        assertEquals(3, storage.chargeableDays)
        assertEquals(750, storage.totalFeeCfa)
        assertEquals(RelayParcelCustodyAction.StorageFeeEligible, custody.action)
    }

    private fun subscription(): CustomerSubscription =
        CustomerSubscription(
            subscriptionId = "subscription-1",
            customerId = "customer-1",
            tier = SubscriptionTier(
                tierCode = "prime-monthly",
                monthlyPriceCfa = 2_500,
                perKmDiscountPercent = 15,
                eligibleDeliveryModes = setOf(SubscriptionDeliveryMode.FastDelivery),
                maxDiscountCfaPerMonth = null,
                isActive = true,
            ),
            status = CustomerSubscriptionStatus.Active,
            startedAt = now.minus(Duration.ofDays(30)),
            currentPeriodStartsAt = now.minus(Duration.ofDays(1)),
            currentPeriodEndsAt = now.plus(Duration.ofDays(29)),
            paidThroughAt = now.plus(Duration.ofDays(29)),
            discountUsedThisPeriodCfa = 0,
            cancelAtPeriodEnd = false,
        )

    private fun issuedReferralCredit(amountCfa: Int): ReferralDeliveryCredit =
        (ReferralDeliveryCreditService().issue(
            ReferralCreditIssueCommand(
                creditId = "credit-1",
                customerId = "customer-1",
                sourceReferralId = "referral-campaign-1",
                amountCfa = amountCfa,
                expiresAt = now.plus(Duration.ofDays(30)),
                issuedAt = now,
            )
        ) as ReferralCreditResult.Issued).credit

    private fun openBargainingSession(service: BargainingService): BargainingSession =
        (service.openSession(
            sessionId = "session-1",
            scope = BargainingSessionScope(
                customerId = "customer-1",
                merchantId = "merchant-1",
                productId = "product-1",
                variantId = null,
                catalogPriceCfa = 10_000,
                bargainingEnabled = true,
            ),
            openedAt = now,
        ) as BargainingResult.Accepted).session

    private fun returnCommand(
        requestedAt: Instant = now.plus(Duration.ofHours(1)),
        category: ReturnItemCategory = ReturnItemCategory.ReturnableGoods,
    ): ReturnRequestCommand =
        ReturnRequestCommand(
            returnId = "return-1",
            orderId = "order-1",
            customerId = "customer-1",
            reason = "Wrong size delivered.",
            requestedRefundCfa = 2_500,
            rawReturnPin = "123456",
            eligibility = ReturnEligibilityInput(
                orderId = "order-1",
                orderOwnerId = "customer-1",
                requesterCustomerId = "customer-1",
                itemCategory = category,
                deliveredAt = now,
                requestedAt = requestedAt,
            ),
        )
}
