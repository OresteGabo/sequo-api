package dev.orestegabo.sequo_api.domain.subscription

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.auth.hasRole
import dev.orestegabo.sequo_api.domain.platform.CustomerSubscriptionRecord
import dev.orestegabo.sequo_api.domain.platform.CustomerSubscriptionRepository
import dev.orestegabo.sequo_api.domain.platform.CustomerSubscriptionStatus as StoredSubscriptionStatus
import dev.orestegabo.sequo_api.domain.platform.ReferralCreditStatus
import dev.orestegabo.sequo_api.domain.platform.ReferralDeliveryCreditRepository
import dev.orestegabo.sequo_api.domain.platform.SubscriptionPlanRecord
import dev.orestegabo.sequo_api.domain.platform.SubscriptionPlanRepository
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingBreakdown
import dev.orestegabo.sequo_api.domain.referral.*
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@Configuration(proxyBeanMethods = false)
class BenefitsClockConfiguration {
    @Bean("benefitsClock") @ConditionalOnMissingBean(name = ["benefitsClock"])
    fun benefitsClock(): Clock = Clock.systemUTC()
}

@Component
class ReferralAwardPolicy(
    @Value("\${sequo.referrals.credit-cfa:0}") val creditCfa: Int,
    @Value("\${sequo.referrals.approval-reference:}") val approvalReference: String,
    @Value("\${sequo.referrals.expiry-days:0}") val expiryDays: Long,
) {
    init { require(creditCfa >= 0 && expiryDays >= 0) { "Referral amount and expiry cannot be negative." } }
    val enabled: Boolean get() = creditCfa > 0 && approvalReference.isNotBlank()
}

enum class DeliveryBenefitApplicationStatus { RESERVED, APPLIED, RELEASED }

@Entity @Table(name = "delivery_benefit_applications")
class DeliveryBenefitApplicationRecord(
    @Id @Column(name = "checkout_id") val checkoutId: String,
    @Column(name = "customer_id", nullable = false) val customerId: String,
    @Column(name = "subscription_id") val subscriptionId: String?,
    @Column(name = "period_starts_at") val periodStartsAt: Instant?,
    @Column(name = "subscription_discount_cfa", nullable = false) val subscriptionDiscountCfa: Int,
    @Column(name = "loyalty_discount_cfa", nullable = false) val loyaltyDiscountCfa: Int,
    @Column(name = "credit_id") val creditId: String?,
    @Column(name = "credit_applied_cfa", nullable = false) val creditAppliedCfa: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: DeliveryBenefitApplicationStatus,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "applied_at") var appliedAt: Instant? = null,
    @Column(name = "released_at") var releasedAt: Instant? = null,
)

interface DeliveryBenefitApplicationRepository : JpaRepository<DeliveryBenefitApplicationRecord, String> {
    fun findByCustomerId(customerId: String): List<DeliveryBenefitApplicationRecord>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DeliveryBenefitApplicationRecord a where a.checkoutId = :checkoutId")
    fun lockForAction(checkoutId: String): DeliveryBenefitApplicationRecord?
}

internal data class DeliveryBenefitReview(
    val delivery: DeliveryPricingBreakdown,
    val subscriptionId: String?,
    val periodStartsAt: Instant?,
    val creditId: String?,
    val revisionContext: List<Any?>,
)

data class CustomerDeliveryBenefits(
    val subscriptionActive: Boolean,
    val subscriptionStatus: String?,
    val planName: String?,
    val monthlyPriceCfa: Int?,
    val deliveryDiscountPercent: Int,
    val loyaltyEnabled: Boolean,
    val availableReferralCreditCfa: Int,
    val status: String,
    val currency: String = "XOF",
)

@Service
class DeliveryBenefitsService(
    private val subscriptions: CustomerSubscriptionRepository,
    private val plans: SubscriptionPlanRepository,
    private val credits: ReferralDeliveryCreditRepository,
    private val applications: DeliveryBenefitApplicationRepository,
    private val users: UserRepository,
    @Qualifier("benefitsClock") private val clock: Clock,
) {
    private val benefits = SubscriptionBenefitsService()
    private val referral = ReferralDeliveryCreditService()

    fun currentTime(): Instant = clock.instant()

    @Transactional(readOnly = true)
    fun summary(customerId: String): CustomerDeliveryBenefits {
        val now = clock.instant()
        val subscription = subscriptions.findTopByUserIdOrderByCreatedAtDesc(customerId)
        val plan = subscription?.let { plans.findById(it.planId).orElse(null) }
        val active = usable(subscription, plan, now)
        val held = applications.findByCustomerId(customerId).filter { it.status == DeliveryBenefitApplicationStatus.RESERVED }
        val available = availableCredits(customerId, now, held).sumOf { it.second.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val state = when {
            active -> "ACTIVE"
            subscription?.status == StoredSubscriptionStatus.PAUSED || subscription?.activationReference.isNullOrBlank() && subscription != null -> "PENDING_VERIFICATION"
            subscription != null && !now.isBefore(subscription.currentPeriodEndsAt) -> "EXPIRED"
            else -> "NO_APPROVED_SUBSCRIPTION"
        }
        return CustomerDeliveryBenefits(active, subscription?.status?.name, plan?.name, plan?.monthlyPriceCfa,
            if (active) requireNotNull(plan).deliveryDiscountPercent else 0,
            active && requireNotNull(subscription).loyaltyTenureVerified && requireNotNull(plan).loyaltyMonthsThreshold > 0 && plan.loyaltyDiscountPercent > 0,
            available, state)
    }

    @Transactional(readOnly = true)
    fun subscriberPriority(customerId: String): Boolean {
        val subscription = subscriptions.findTopByUserIdOrderByCreatedAtDesc(customerId)
        return usable(subscription, subscription?.let { plans.findById(it.planId).orElse(null) }, clock.instant())
    }

    @Transactional(readOnly = true)
    internal fun review(customerId: String, base: DeliveryPricingBreakdown, mode: SubscriptionDeliveryMode): DeliveryBenefitReview {
        val now = clock.instant()
        val stored = subscriptions.findTopByUserIdOrderByCreatedAtDesc(customerId)
        val plan = stored?.let { plans.findById(it.planId).orElse(null) }
        val applicationRows = applications.findByCustomerId(customerId)
        val active = usable(stored, plan, now)
        val subscription = if (active) {
            val record = requireNotNull(stored)
            val approved = requireNotNull(plan)
            val used = applicationRows.filter { it.subscriptionId == record.id && it.periodStartsAt == record.currentPeriodStartsAt && it.status != DeliveryBenefitApplicationStatus.RELEASED }
                .sumOf { it.subscriptionDiscountCfa.toLong() + it.loyaltyDiscountCfa }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            CustomerSubscription(record.id, customerId,
                SubscriptionTier(approved.id, approved.monthlyPriceCfa, approved.deliveryDiscountPercent, SubscriptionDeliveryMode.entries.toSet(), approved.monthlyDiscountCapCfa),
                CustomerSubscriptionStatus.Active, record.startedAt, requireNotNull(record.currentPeriodStartsAt), record.currentPeriodEndsAt,
                record.currentPeriodEndsAt, used, record.cancelAtPeriodEnd)
        } else null
        val loyalty = if (subscription != null && requireNotNull(stored).loyaltyTenureVerified && requireNotNull(plan).loyaltyMonthsThreshold > 0 && plan.loyaltyDiscountPercent > 0)
            listOf(LoyaltyMultiplierRule(plan.loyaltyMonthsThreshold, BigDecimal(100 - plan.loyaltyDiscountPercent).divide(BigDecimal(100)))) else emptyList()
        val benefit = benefits.calculate(SubscriptionBenefitRequest(subscription, customerId, mode, base.baseFeeCfa, now, loyalty))
        val credit = availableCredits(customerId, now, applicationRows.filter { it.status == DeliveryBenefitApplicationStatus.RESERVED }).firstOrNull()
        val applied = credit?.let { (record, available) ->
            val domain = ReferralDeliveryCredit(record.id, customerId, record.sourceCode, record.creditCfa, available,
                ReferralDeliveryCreditStatus.Active, record.expiresAt, record.createdAt, record.createdAt)
            (referral.applyToDeliveryFee(ReferralCreditApplicationCommand(domain, customerId, benefit.feeAfterBenefitsCfa, ReferralCreditUse.DeliveryFee, now)) as ReferralCreditResult.Accepted).application.appliedAmountCfa
        } ?: 0
        return DeliveryBenefitReview(base.copy(subscriptionDiscountCfa = benefit.subscriptionDiscountCfa,
            loyaltyDiscountCfa = benefit.loyaltyDiscountCfa, referralCreditAppliedCfa = applied,
            finalDeliveryFeeCfa = benefit.feeAfterBenefitsCfa - applied), subscription?.subscriptionId,
            stored?.currentPeriodStartsAt.takeIf { subscription != null }, credit?.first?.id.takeIf { applied > 0 },
            listOf(subscription?.subscriptionId, stored?.activationReference, stored?.currentPeriodStartsAt?.toString(), stored?.currentPeriodEndsAt?.toString(),
                plan?.approvalReference, stored?.loyaltyTenureVerified, benefit.remainingMonthlyDiscountCapCfa, credit?.first?.id, credit?.second))
    }

    @Transactional
    internal fun reserve(checkoutId: String, customerId: String, review: DeliveryBenefitReview) {
        // Checkout already holds this customer's row lock. No money or credit is consumed while payment is pending.
        require(users.lockCheckoutOwner(customerId)?.active == true) { "Customer account is unavailable." }
        if (applications.existsById(checkoutId)) return
        val d = review.delivery
        applications.save(DeliveryBenefitApplicationRecord(checkoutId, customerId, review.subscriptionId, review.periodStartsAt,
            d.subscriptionDiscountCfa, d.loyaltyDiscountCfa, review.creditId, d.referralCreditAppliedCfa,
            DeliveryBenefitApplicationStatus.RESERVED, clock.instant()))
    }

    @Transactional
    fun complete(checkoutId: String, customerId: String) {
        if (!applications.existsById(checkoutId)) return // Legacy pending checkout keeps its frozen historical terms.
        require(users.lockCheckoutOwner(customerId)?.active == true) { "Customer account is unavailable." }
        val application = applications.lockForAction(checkoutId) ?: return
        require(application.customerId == customerId) { "Benefit application owner mismatch." }
        if (application.status == DeliveryBenefitApplicationStatus.APPLIED) return
        require(application.status == DeliveryBenefitApplicationStatus.RESERVED) { "Released benefit reservation requires reconciliation." }
        application.creditId?.let { id ->
            val credit = credits.findById(id).orElseThrow()
            require(credit.referrerUserId == customerId && credit.status == ReferralCreditStatus.AVAILABLE && credit.remainingCfa >= application.creditAppliedCfa) { "Reserved credit requires operator reconciliation." }
            credit.remainingCfa -= application.creditAppliedCfa
            if (credit.remainingCfa == 0) credit.status = ReferralCreditStatus.APPLIED
            credits.save(credit)
        }
        application.status = DeliveryBenefitApplicationStatus.APPLIED
        application.appliedAt = clock.instant()
        applications.save(application)
    }

    @Transactional
    fun release(checkoutId: String, customerId: String) {
        if (!applications.existsById(checkoutId)) return
        require(users.lockCheckoutOwner(customerId)?.active == true) { "Customer account is unavailable." }
        val application = applications.lockForAction(checkoutId) ?: return
        require(application.customerId == customerId) { "Benefit application owner mismatch." }
        if (application.status != DeliveryBenefitApplicationStatus.RESERVED) return
        application.status = DeliveryBenefitApplicationStatus.RELEASED
        application.releasedAt = clock.instant()
        applications.save(application)
    }

    private fun usable(subscription: CustomerSubscriptionRecord?, plan: SubscriptionPlanRecord?, now: Instant): Boolean =
        subscription != null && plan != null && plan.active && !plan.approvalReference.isNullOrBlank() &&
            plan.deliveryDiscountPercent in 0..100 && plan.loyaltyDiscountPercent in 0..100 && plan.monthlyPriceCfa >= 0 &&
            (plan.monthlyDiscountCapCfa == null || requireNotNull(plan.monthlyDiscountCapCfa) >= 0) &&
            subscription.status == StoredSubscriptionStatus.ACTIVE && !subscription.activationReference.isNullOrBlank() &&
            subscription.currentPeriodStartsAt?.let { !now.isBefore(it) } == true && !now.isBefore(subscription.startedAt) &&
            now.isBefore(subscription.currentPeriodEndsAt)

    private fun availableCredits(customerId: String, now: Instant, held: List<DeliveryBenefitApplicationRecord>) =
        credits.findByReferrerUserIdOrReferredUserIdOrderByCreatedAtDesc(customerId, customerId)
            .filter { it.referrerUserId == customerId && it.status == ReferralCreditStatus.AVAILABLE && !it.approvalReference.isNullOrBlank() && it.creditCfa > 0 && it.remainingCfa in 1..it.creditCfa && !it.createdAt.isAfter(now) && (it.expiresAt == null || now.isBefore(it.expiresAt)) }
            .sortedWith(compareBy({ it.expiresAt ?: Instant.MAX }, { it.createdAt }, { it.id }))
            .map { credit -> credit to (credit.remainingCfa.toLong() - held.filter { it.creditId == credit.id }.sumOf { it.creditAppliedCfa.toLong() }).coerceAtLeast(0).toInt() }
            .filter { it.second > 0 }
}

@RestController
class CustomerDeliveryBenefitsController(private val benefits: DeliveryBenefitsService) {
    @GetMapping("/api/subscriptions/benefits")
    fun benefits(authentication: Authentication?): ResponseEntity<Any> = when {
        authentication == null -> ResponseEntity.status(401).build()
        !authentication.hasRole(RoleCode.CUSTOMER) -> ResponseEntity.status(403).build()
        else -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(benefits.summary(authentication.name))
    }
}
