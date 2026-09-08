package dev.orestegabo.sequo_api.domain.subscription

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionBenefitsServiceTest {

    private val service = SubscriptionBenefitsService()
    private val now = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun activeMonthlySubscriptionAppliesPercentageDiscountToEligibleDeliveryFee() {
        val snapshot = service.calculate(
            SubscriptionBenefitRequest(
                subscription = subscription(),
                customerId = "customer-1",
                deliveryMode = SubscriptionDeliveryMode.FastDelivery,
                eligiblePerKmFeeCfa = 1_000,
                quotedAt = now,
            )
        )

        assertTrue(snapshot.subscriptionActive)
        assertEquals("prime-monthly", snapshot.tierCode)
        assertEquals(150, snapshot.subscriptionDiscountCfa)
        assertEquals(0, snapshot.loyaltyDiscountCfa)
        assertEquals(850, snapshot.feeAfterBenefitsCfa)
    }

    @Test
    fun monthlyDiscountRespectsConfiguredCap() {
        val snapshot = service.calculate(
            SubscriptionBenefitRequest(
                subscription = subscription(
                    tier = tier(maxDiscountCfaPerMonth = 200),
                    discountUsedThisPeriodCfa = 150,
                ),
                customerId = "customer-1",
                deliveryMode = SubscriptionDeliveryMode.FastDelivery,
                eligiblePerKmFeeCfa = 1_000,
                quotedAt = now,
            )
        )

        assertEquals(50, snapshot.subscriptionDiscountCfa)
        assertEquals(0, snapshot.remainingMonthlyDiscountCapCfa)
        assertEquals(950, snapshot.feeAfterBenefitsCfa)
    }

    @Test
    fun expiredPastDueWrongCustomerAndIneligibleModesReceiveNoDiscount() {
        val requests = listOf(
            benefitRequest(subscription = subscription(status = CustomerSubscriptionStatus.PastDue)),
            benefitRequest(subscription = subscription(paidThroughAt = now.minusSeconds(1))),
            benefitRequest(subscription = subscription(), customerId = "customer-2"),
            benefitRequest(subscription = subscription(), deliveryMode = SubscriptionDeliveryMode.PointDeRelai),
            benefitRequest(subscription = subscription(tier = tier(isActive = false))),
            benefitRequest(subscription = subscription(cancelAtPeriodEnd = true)),
        )

        requests.forEach { request ->
            val snapshot = service.calculate(request)

            assertFalse(snapshot.subscriptionActive)
            assertEquals(0, snapshot.subscriptionDiscountCfa)
            assertEquals(1_000, snapshot.feeAfterBenefitsCfa)
        }
    }

    @Test
    fun multiYearLoyaltyMultiplierAppliesAfterSubscriptionDiscount() {
        val snapshot = service.calculate(
            SubscriptionBenefitRequest(
                subscription = subscription(startedAt = now.minusSeconds(800L * 24 * 60 * 60)),
                customerId = "customer-1",
                deliveryMode = SubscriptionDeliveryMode.FastDelivery,
                eligiblePerKmFeeCfa = 1_000,
                quotedAt = now,
                loyaltyRules = listOf(
                    LoyaltyMultiplierRule(minMonthsActive = 0, multiplier = BigDecimal("1.00")),
                    LoyaltyMultiplierRule(minMonthsActive = 12, multiplier = BigDecimal("0.95")),
                    LoyaltyMultiplierRule(minMonthsActive = 24, multiplier = BigDecimal("0.90")),
                ),
            )
        )

        assertEquals(26, snapshot.monthsActive)
        assertEquals(150, snapshot.subscriptionDiscountCfa)
        assertEquals(85, snapshot.loyaltyDiscountCfa)
        assertEquals(765, snapshot.feeAfterBenefitsCfa)
        assertEquals(BigDecimal("0.90"), snapshot.loyaltyMultiplier)
    }

    @Test
    fun loyaltyDiscountRespectsPerOrderCap() {
        val snapshot = service.calculate(
            SubscriptionBenefitRequest(
                subscription = subscription(startedAt = now.minusSeconds(800L * 24 * 60 * 60)),
                customerId = "customer-1",
                deliveryMode = SubscriptionDeliveryMode.FastDelivery,
                eligiblePerKmFeeCfa = 1_000,
                quotedAt = now,
                loyaltyRules = listOf(
                    LoyaltyMultiplierRule(
                        minMonthsActive = 24,
                        multiplier = BigDecimal("0.80"),
                        maxDiscountCfaPerOrder = 100,
                    ),
                ),
            )
        )

        assertEquals(150, snapshot.subscriptionDiscountCfa)
        assertEquals(100, snapshot.loyaltyDiscountCfa)
        assertEquals(750, snapshot.feeAfterBenefitsCfa)
    }

    @Test
    fun renewalRestartsPaidPeriodAndMonthlyDiscountUsage() {
        val pastDue = subscription(
            status = CustomerSubscriptionStatus.PastDue,
            currentPeriodStartsAt = now.minusSeconds(31 * 24 * 60 * 60),
            currentPeriodEndsAt = now.minusSeconds(1),
            paidThroughAt = now.minusSeconds(1),
            discountUsedThisPeriodCfa = 400,
        )

        val renewed = service.renew(pastDue, renewedAt = now)

        assertEquals(CustomerSubscriptionStatus.Active, renewed.status)
        assertEquals(now, renewed.currentPeriodStartsAt)
        assertEquals(now.plusSeconds(30 * 24 * 60 * 60), renewed.currentPeriodEndsAt)
        assertEquals(now.plusSeconds(30 * 24 * 60 * 60), renewed.paidThroughAt)
        assertEquals(0, renewed.discountUsedThisPeriodCfa)
    }

    private fun benefitRequest(
        subscription: CustomerSubscription?,
        customerId: String = "customer-1",
        deliveryMode: SubscriptionDeliveryMode = SubscriptionDeliveryMode.FastDelivery,
    ): SubscriptionBenefitRequest =
        SubscriptionBenefitRequest(
            subscription = subscription,
            customerId = customerId,
            deliveryMode = deliveryMode,
            eligiblePerKmFeeCfa = 1_000,
            quotedAt = now,
        )

    private fun subscription(
        tier: SubscriptionTier = tier(),
        status: CustomerSubscriptionStatus = CustomerSubscriptionStatus.Active,
        startedAt: Instant = now.minusSeconds(60L * 24 * 60 * 60),
        currentPeriodStartsAt: Instant = now.minusSeconds(5 * 24 * 60 * 60),
        currentPeriodEndsAt: Instant = now.plusSeconds(25 * 24 * 60 * 60),
        paidThroughAt: Instant = now.plusSeconds(25 * 24 * 60 * 60),
        discountUsedThisPeriodCfa: Int = 0,
        cancelAtPeriodEnd: Boolean = false,
    ): CustomerSubscription =
        CustomerSubscription(
            subscriptionId = "subscription-1",
            customerId = "customer-1",
            tier = tier,
            status = status,
            startedAt = startedAt,
            currentPeriodStartsAt = currentPeriodStartsAt,
            currentPeriodEndsAt = currentPeriodEndsAt,
            paidThroughAt = paidThroughAt,
            discountUsedThisPeriodCfa = discountUsedThisPeriodCfa,
            cancelAtPeriodEnd = cancelAtPeriodEnd,
        )

    private fun tier(
        maxDiscountCfaPerMonth: Int? = null,
        isActive: Boolean = true,
    ): SubscriptionTier =
        SubscriptionTier(
            tierCode = "prime-monthly",
            monthlyPriceCfa = 2_500,
            perKmDiscountPercent = 15,
            eligibleDeliveryModes = setOf(
                SubscriptionDeliveryMode.FastDelivery,
                SubscriptionDeliveryMode.GroupedSequo,
            ),
            maxDiscountCfaPerMonth = maxDiscountCfaPerMonth,
            isActive = isActive,
        )
}
