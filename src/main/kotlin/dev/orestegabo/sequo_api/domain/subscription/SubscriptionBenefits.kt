package dev.orestegabo.sequo_api.domain.subscription

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class SubscriptionDeliveryMode {
    FastDelivery,
    GroupedSequo,
    PointDeRelai,
}

enum class CustomerSubscriptionStatus {
    Active,
    PastDue,
    Cancelled,
    Expired,
}

data class SubscriptionTier(
    val tierCode: String,
    val monthlyPriceCfa: Int,
    val perKmDiscountPercent: Int,
    val eligibleDeliveryModes: Set<SubscriptionDeliveryMode>,
    val maxDiscountCfaPerMonth: Int? = null,
    val isActive: Boolean = true,
) {
    init {
        require(tierCode.isNotBlank()) { "tierCode is required." }
        require(monthlyPriceCfa >= 0) { "monthlyPriceCfa cannot be negative." }
        require(perKmDiscountPercent in 0..100) { "perKmDiscountPercent must be between 0 and 100." }
        require(eligibleDeliveryModes.isNotEmpty()) { "eligibleDeliveryModes cannot be empty." }
        require(maxDiscountCfaPerMonth == null || maxDiscountCfaPerMonth >= 0) {
            "maxDiscountCfaPerMonth cannot be negative."
        }
    }
}

data class CustomerSubscription(
    val subscriptionId: String,
    val customerId: String,
    val tier: SubscriptionTier,
    val status: CustomerSubscriptionStatus,
    val startedAt: Instant,
    val currentPeriodStartsAt: Instant,
    val currentPeriodEndsAt: Instant,
    val paidThroughAt: Instant,
    val discountUsedThisPeriodCfa: Int = 0,
    val cancelAtPeriodEnd: Boolean = false,
) {
    init {
        require(subscriptionId.isNotBlank()) { "subscriptionId is required." }
        require(customerId.isNotBlank()) { "customerId is required." }
        require(!currentPeriodEndsAt.isBefore(currentPeriodStartsAt)) {
            "currentPeriodEndsAt cannot be before currentPeriodStartsAt."
        }
        require(!paidThroughAt.isBefore(currentPeriodStartsAt)) {
            "paidThroughAt cannot be before currentPeriodStartsAt."
        }
        require(discountUsedThisPeriodCfa >= 0) { "discountUsedThisPeriodCfa cannot be negative." }
    }
}

data class LoyaltyMultiplierRule(
    val minMonthsActive: Int,
    val multiplier: BigDecimal,
    val maxDiscountCfaPerOrder: Int? = null,
) {
    init {
        require(minMonthsActive >= 0) { "minMonthsActive cannot be negative." }
        require(multiplier > BigDecimal.ZERO && multiplier <= BigDecimal.ONE) {
            "multiplier must be greater than 0 and no more than 1."
        }
        require(maxDiscountCfaPerOrder == null || maxDiscountCfaPerOrder >= 0) {
            "maxDiscountCfaPerOrder cannot be negative."
        }
    }
}

data class SubscriptionBenefitRequest(
    val subscription: CustomerSubscription?,
    val customerId: String,
    val deliveryMode: SubscriptionDeliveryMode,
    val eligiblePerKmFeeCfa: Int,
    val quotedAt: Instant,
    val loyaltyRules: List<LoyaltyMultiplierRule> = emptyList(),
)

data class SubscriptionBenefitSnapshot(
    val tierCode: String?,
    val subscriptionActive: Boolean,
    val renewalState: CustomerSubscriptionStatus?,
    val monthsActive: Int,
    val eligiblePerKmFeeCfa: Int,
    val subscriptionDiscountCfa: Int,
    val loyaltyDiscountCfa: Int,
    val totalDiscountCfa: Int,
    val feeAfterBenefitsCfa: Int,
    val remainingMonthlyDiscountCapCfa: Int?,
    val loyaltyMultiplier: BigDecimal,
)

class SubscriptionBenefitsService {
    fun calculate(request: SubscriptionBenefitRequest): SubscriptionBenefitSnapshot {
        require(request.customerId.isNotBlank()) { "customerId is required." }
        require(request.eligiblePerKmFeeCfa >= 0) { "eligiblePerKmFeeCfa cannot be negative." }

        val subscription = request.subscription
        if (subscription == null || !isUsable(subscription, request)) {
            return noBenefits(request)
        }

        val remainingCap = subscription.remainingMonthlyCap()
        val rawSubscriptionDiscount = (request.eligiblePerKmFeeCfa * subscription.tier.perKmDiscountPercent) / 100
        val subscriptionDiscount = if (remainingCap == null) {
            rawSubscriptionDiscount
        } else {
            rawSubscriptionDiscount.coerceAtMost(remainingCap)
        }
        val feeAfterSubscription = request.eligiblePerKmFeeCfa - subscriptionDiscount
        val monthsActive = ChronoUnit.DAYS.between(
            subscription.startedAt.truncatedTo(ChronoUnit.DAYS),
            request.quotedAt.truncatedTo(ChronoUnit.DAYS),
        ).toInt().coerceAtLeast(0) / DAYS_PER_MONTH
        val loyaltyRule = request.loyaltyRules
            .filter { monthsActive >= it.minMonthsActive }
            .maxByOrNull { it.minMonthsActive }
        val multiplier = loyaltyRule?.multiplier ?: BigDecimal.ONE
        val uncappedFeeAfterLoyalty = BigDecimal(feeAfterSubscription).multiply(multiplier)
            .setScale(0, RoundingMode.HALF_UP)
            .toInt()
        val rawLoyaltyDiscount = feeAfterSubscription - uncappedFeeAfterLoyalty
        val loyaltyDiscount = loyaltyRule?.maxDiscountCfaPerOrder?.let(rawLoyaltyDiscount::coerceAtMost)
            ?: rawLoyaltyDiscount

        return SubscriptionBenefitSnapshot(
            tierCode = subscription.tier.tierCode,
            subscriptionActive = true,
            renewalState = subscription.status,
            monthsActive = monthsActive,
            eligiblePerKmFeeCfa = request.eligiblePerKmFeeCfa,
            subscriptionDiscountCfa = subscriptionDiscount,
            loyaltyDiscountCfa = loyaltyDiscount,
            totalDiscountCfa = subscriptionDiscount + loyaltyDiscount,
            feeAfterBenefitsCfa = feeAfterSubscription - loyaltyDiscount,
            remainingMonthlyDiscountCapCfa = remainingCap?.minus(subscriptionDiscount),
            loyaltyMultiplier = multiplier,
        )
    }

    fun renew(
        subscription: CustomerSubscription,
        renewedAt: Instant,
        period: Duration = Duration.ofDays(30),
    ): CustomerSubscription {
        require(!period.isNegative && !period.isZero) { "renewal period must be positive." }
        val newPeriodEnd = renewedAt.plus(period)

        return subscription.copy(
            status = CustomerSubscriptionStatus.Active,
            currentPeriodStartsAt = renewedAt,
            currentPeriodEndsAt = newPeriodEnd,
            paidThroughAt = newPeriodEnd,
            discountUsedThisPeriodCfa = 0,
        )
    }

    private fun isUsable(subscription: CustomerSubscription, request: SubscriptionBenefitRequest): Boolean =
        subscription.customerId == request.customerId &&
            subscription.status == CustomerSubscriptionStatus.Active &&
            subscription.tier.isActive &&
            request.deliveryMode in subscription.tier.eligibleDeliveryModes &&
            !request.quotedAt.isBefore(subscription.currentPeriodStartsAt) &&
            request.quotedAt.isBefore(subscription.paidThroughAt) &&
            !subscription.cancelAtPeriodEnd

    private fun CustomerSubscription.remainingMonthlyCap(): Int? =
        tier.maxDiscountCfaPerMonth?.minus(discountUsedThisPeriodCfa)?.coerceAtLeast(0)

    private fun noBenefits(request: SubscriptionBenefitRequest): SubscriptionBenefitSnapshot =
        SubscriptionBenefitSnapshot(
            tierCode = request.subscription?.tier?.tierCode,
            subscriptionActive = false,
            renewalState = request.subscription?.status,
            monthsActive = 0,
            eligiblePerKmFeeCfa = request.eligiblePerKmFeeCfa,
            subscriptionDiscountCfa = 0,
            loyaltyDiscountCfa = 0,
            totalDiscountCfa = 0,
            feeAfterBenefitsCfa = request.eligiblePerKmFeeCfa,
            remainingMonthlyDiscountCapCfa = request.subscription?.remainingMonthlyCap(),
            loyaltyMultiplier = BigDecimal.ONE,
        )

    private companion object {
        const val DAYS_PER_MONTH = 30
    }
}
