package dev.orestegabo.sequo_api.domain.platform

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.Authentication
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationGroup
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationGroupType
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationMenu
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationOption
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationResult
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationSelection
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationService
import org.springframework.transaction.annotation.Isolation
import org.springframework.stereotype.Service
import dev.orestegabo.sequo_api.domain.payment.PaymentConfigurationStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.Clock
import java.util.UUID

enum class CustomerAddressKind { HOME, WORK, RELAY, OTHER }
enum class CustomerSubscriptionStatus { ACTIVE, PAUSED, CANCELLED, EXPIRED }
enum class ReferralCreditStatus { RESERVED, AVAILABLE, APPLIED, EXPIRED, CANCELLED }
enum class PaymentProviderReadiness { PLANNED, SANDBOX_READY, LIVE_READY, DISABLED }
enum class CooperativeApprovalStatus { DRAFT, PENDING_REVIEW, APPROVED, REJECTED, SUSPENDED }
enum class CooperativeMemberRole { OWNER, MEMBER }

@Entity
@Table(name = "customer_profiles")
class CustomerProfileRecord(
    @Id @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "display_name", nullable = false) var displayName: String,
    @Column(name = "phone_number", length = 64) var phoneNumber: String? = null,
    @Column(name = "receipt_email", length = 254) var receiptEmail: String? = null,
    @Column(name = "default_address_id") var defaultAddressId: String? = null,
    @Column(name = "marketing_opt_in", nullable = false) var marketingOptIn: Boolean = false,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = createdAt,
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "customer_addresses")
class CustomerAddressRecord(
    @Id val id: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) var kind: CustomerAddressKind,
    @Column(nullable = false, length = 180) var label: String,
    @Column(name = "recipient_name", nullable = false, length = 180) var recipientName: String,
    @Column(name = "phone_number", nullable = false, length = 64) var phoneNumber: String,
    @Column(nullable = false, length = 120) var city: String,
    @Column(length = 160) var area: String? = null,
    @Column(name = "street_hint", length = 240) var streetHint: String? = null,
    @Column(name = "latitude") var latitude: Double? = null,
    @Column(name = "longitude") var longitude: Double? = null,
    @Column(nullable = false) var active: Boolean = true,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = createdAt,
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "subscription_plans")
class SubscriptionPlanRecord(
    @Id val id: String,
    @Column(nullable = false, length = 120) var name: String,
    @Column(name = "monthly_price_cfa", nullable = false) var monthlyPriceCfa: Int,
    @Column(name = "delivery_discount_percent", nullable = false) var deliveryDiscountPercent: Int,
    @Column(name = "monthly_discount_cap_cfa") var monthlyDiscountCapCfa: Int? = null,
    @Column(name = "loyalty_months_threshold", nullable = false) var loyaltyMonthsThreshold: Int = 0,
    @Column(name = "loyalty_discount_percent", nullable = false) var loyaltyDiscountPercent: Int = 0,
    @Column(nullable = false) var active: Boolean = true,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "approval_reference") var approvalReference: String? = null,
)

@Entity
@Table(name = "customer_subscriptions")
class CustomerSubscriptionRecord(
    @Id val id: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "plan_id", nullable = false) val planId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_customer_subscriptions_plan"))
    val plan: SubscriptionPlanRecord? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) var status: CustomerSubscriptionStatus,
    @Column(name = "started_at", nullable = false) val startedAt: Instant = Instant.now(),
    @Column(name = "current_period_ends_at", nullable = false) var currentPeriodEndsAt: Instant,
    @Column(name = "cancel_at_period_end", nullable = false) var cancelAtPeriodEnd: Boolean = false,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = createdAt,
    @Version @Column(nullable = false) var version: Long = 0,
    @Column(name = "current_period_starts_at") var currentPeriodStartsAt: Instant? = null,
    @Column(name = "activation_reference") var activationReference: String? = null,
    @Column(name = "loyalty_tenure_verified", nullable = false) var loyaltyTenureVerified: Boolean = false,
)

@Entity
@Table(name = "referral_delivery_credits")
class ReferralDeliveryCreditRecord(
    @Id val id: String,
    @Column(name = "referrer_user_id", nullable = false) val referrerUserId: String,
    @Column(name = "referred_user_id", nullable = false) val referredUserId: String,
    @Column(name = "credit_cfa", nullable = false) val creditCfa: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) var status: ReferralCreditStatus,
    @Column(name = "source_code", length = 64) val sourceCode: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "expires_at") val expiresAt: Instant? = null,
    @Column(name = "remaining_cfa", nullable = false) var remainingCfa: Int = creditCfa,
    @Column(name = "approval_reference") var approvalReference: String? = null,
)

@Entity
@Table(name = "payment_provider_configurations")
class PaymentProviderConfigurationRecord(
    @Id @Column(name = "provider_id") val providerId: String,
    @Column(name = "display_name", nullable = false, length = 120) var displayName: String,
    @Column(name = "country_code", nullable = false, length = 8) var countryCode: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) var readiness: PaymentProviderReadiness,
    @Column(name = "customer_checkout_enabled", nullable = false) var customerCheckoutEnabled: Boolean = false,
    @Column(name = "refund_enabled", nullable = false) var refundEnabled: Boolean = false,
    @Column(name = "courier_payout_enabled", nullable = false) var courierPayoutEnabled: Boolean = false,
    @Column(length = 500) var notes: String? = null,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "product_customization_groups")
class ProductCustomizationGroupRecord(
    @Id val id: String,
    @Column(name = "product_id", nullable = false) val productId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_product_customization_groups_product"))
    val product: ProductRecord? = null,
    @Column(nullable = false, length = 120) var name: String,
    @Column(name = "min_choices", nullable = false) var minChoices: Int = 0,
    @Column(name = "max_choices", nullable = false) var maxChoices: Int = 1,
    @Column(name = "sort_order", nullable = false) var sortOrder: Int = 0,
    @Column(nullable = false) var active: Boolean = true,
)

@Entity
@Table(name = "product_customization_options")
class ProductCustomizationOptionRecord(
    @Id val id: String,
    @Column(name = "group_id", nullable = false) val groupId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_product_customization_options_group"))
    val group: ProductCustomizationGroupRecord? = null,
    @Column(nullable = false, length = 140) var name: String,
    @Column(name = "price_delta_cfa", nullable = false) var priceDeltaCfa: Int = 0,
    @Column(name = "sort_order", nullable = false) var sortOrder: Int = 0,
    @Column(nullable = false) var active: Boolean = true,
)

@Entity
@Table(name = "merchant_cooperatives")
class MerchantCooperativeRecord(
    @Id val id: String,
    @Column(nullable = false, length = 120) var code: String,
    @Column(nullable = false, length = 180) var name: String,
    @Column(nullable = false, length = 120) var city: String,
    @Column(length = 160) var area: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) var status: CooperativeApprovalStatus,
    @Column(name = "reviewed_by_user_id") var reviewedByUserId: String? = null,
    @Column(name = "reviewed_at") var reviewedAt: Instant? = null,
    @Column(name = "review_reason", columnDefinition = "text") var reviewReason: String? = null,
    @Column(name = "review_history_json", columnDefinition = "text") var reviewHistoryJson: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = createdAt,
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "merchant_cooperative_members")
class MerchantCooperativeMemberRecord(
    @Id val id: String,
    @Column(name = "cooperative_id", nullable = false) val cooperativeId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cooperative_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchant_cooperative_members_cooperative"))
    val cooperative: MerchantCooperativeRecord? = null,
    @Column(name = "merchant_id", nullable = false) val merchantId: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) var role: CooperativeMemberRole,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

interface CustomerProfileRepository : JpaRepository<CustomerProfileRecord, String> {
    fun findByUserId(userId: String): CustomerProfileRecord?
}

interface CustomerAddressRepository : JpaRepository<CustomerAddressRecord, String> {
    fun findByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId: String): List<CustomerAddressRecord>
    fun findByIdAndUserId(id: String, userId: String): CustomerAddressRecord?
}

interface SubscriptionPlanRepository : JpaRepository<SubscriptionPlanRecord, String> {
    fun findByActiveTrueOrderByMonthlyPriceCfaAsc(): List<SubscriptionPlanRecord>
}

interface CustomerSubscriptionRepository : JpaRepository<CustomerSubscriptionRecord, String> {
    fun findTopByUserIdOrderByCreatedAtDesc(userId: String): CustomerSubscriptionRecord?
}

interface ReferralDeliveryCreditRepository : JpaRepository<ReferralDeliveryCreditRecord, String> {
    fun findByReferrerUserIdOrReferredUserIdOrderByCreatedAtDesc(referrerUserId: String, referredUserId: String): List<ReferralDeliveryCreditRecord>
    fun findFirstByReferrerUserIdAndReferredUserIdOrderByCreatedAtAsc(referrerUserId: String, referredUserId: String): ReferralDeliveryCreditRecord?
}

interface PaymentProviderConfigurationRepository : JpaRepository<PaymentProviderConfigurationRecord, String> {
    fun findAllByOrderByDisplayNameAsc(): List<PaymentProviderConfigurationRecord>
}

interface ProductCustomizationGroupRepository : JpaRepository<ProductCustomizationGroupRecord, String> {
    fun findByProductIdInOrderBySortOrderAsc(productIds: Collection<String>): List<ProductCustomizationGroupRecord>
    fun deleteByProductId(productId: String): Long
}

interface ProductCustomizationOptionRepository : JpaRepository<ProductCustomizationOptionRecord, String> {
    fun findByGroupIdInOrderBySortOrderAsc(groupIds: Collection<String>): List<ProductCustomizationOptionRecord>
    fun deleteByGroupIdIn(groupIds: Collection<String>): Long
}

interface MerchantCooperativeRepository : JpaRepository<MerchantCooperativeRecord, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from MerchantCooperativeRecord c where c.id = :id")
    fun lockForReview(id: String): MerchantCooperativeRecord?

    fun findAllByOrderByCreatedAtDesc(): List<MerchantCooperativeRecord>
}

interface MerchantCooperativeMemberRepository : JpaRepository<MerchantCooperativeMemberRecord, String> {
    fun findByCooperativeIdOrderByCreatedAtAsc(cooperativeId: String): List<MerchantCooperativeMemberRecord>
    fun existsByCooperativeIdAndMerchantId(cooperativeId: String, merchantId: String): Boolean
}

data class UpsertCustomerProfileRequest(
    val displayName: String,
    val phoneNumber: String? = null,
    val receiptEmail: String? = null,
    val defaultAddressId: String? = null,
    val marketingOptIn: Boolean = false,
)

data class UpsertCustomerAddressRequest(
    val kind: CustomerAddressKind = CustomerAddressKind.HOME,
    val label: String,
    val recipientName: String,
    val phoneNumber: String,
    val city: String,
    val area: String? = null,
    val streetHint: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class StartSubscriptionRequest(val planId: String)
data class CreateReferralCreditRequest(val referredUserId: String, val sourceCode: String? = null)
data class ProductCustomizationOptionRequest(
    val name: String,
    val priceDeltaCfa: Int? = null,
    val sortOrder: Int? = null,
    val active: Boolean? = null,
    val id: String? = null,
)

data class ProductCustomizationGroupRequest(
    val name: String,
    val minChoices: Int? = null,
    val maxChoices: Int? = null,
    val sortOrder: Int? = null,
    val active: Boolean? = null,
    val options: List<ProductCustomizationOptionRequest> = emptyList(),
    val id: String? = null,
)

data class ReplaceProductCustomizationsRequest(val groups: List<ProductCustomizationGroupRequest>)
data class QuoteProductCustomizationsRequest(val selections: List<FoodCustomizationSelection> = emptyList())
data class ProductCustomizationQuote(val productId: String, val basePriceCfa: Int, val optionPriceCfa: Int, val unitPriceCfa: Int)
data class CreateCooperativeRequest(val code: String, val name: String, val city: String, val area: String? = null, val ownerMerchantId: String)
data class AddCooperativeMemberRequest(val merchantId: String, val role: CooperativeMemberRole? = null)
data class ReviewCooperativeRequest(val status: CooperativeApprovalStatus, val reason: String? = null)

data class CustomerProfileDto(
    val userId: String,
    val displayName: String,
    val phoneNumber: String?,
    val receiptEmail: String?,
    val defaultAddressId: String?,
    val marketingOptIn: Boolean,
)
data class CustomerAddressDto(
    val id: String,
    val kind: CustomerAddressKind,
    val label: String,
    val recipientName: String,
    val phoneNumber: String,
    val city: String,
    val area: String?,
    val streetHint: String?,
    val latitude: Double?,
    val longitude: Double?,
)
data class SubscriptionPlanDto(
    val id: String,
    val name: String,
    val monthlyPriceCfa: Int,
    val deliveryDiscountPercent: Int,
    val monthlyDiscountCapCfa: Int?,
    val loyaltyMonthsThreshold: Int,
    val loyaltyDiscountPercent: Int,
)
data class CustomerSubscriptionDto(
    val id: String,
    val userId: String,
    val planId: String,
    val status: CustomerSubscriptionStatus,
    val startedAt: Instant,
    val currentPeriodEndsAt: Instant,
    val cancelAtPeriodEnd: Boolean,
)
data class ReferralCreditDto(
    val id: String,
    val referrerUserId: String,
    val referredUserId: String,
    val creditCfa: Int,
    val status: ReferralCreditStatus,
    val sourceCode: String?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val remainingCfa: Int? = creditCfa,
)
data class PaymentProviderConfigurationDto(
    val providerId: String,
    val displayName: String,
    val countryCode: String,
    val readiness: PaymentProviderReadiness,
    val customerCheckoutEnabled: Boolean,
    val refundEnabled: Boolean,
    val courierPayoutEnabled: Boolean,
    val notes: String?,
    val apiConfigured: Boolean = false,
    val checkoutConnected: Boolean = false,
)
data class ProductCustomizationGroupDto(
    val id: String,
    val productId: String,
    val name: String,
    val minChoices: Int,
    val maxChoices: Int,
    val sortOrder: Int,
    val active: Boolean,
    val options: List<ProductCustomizationOptionDto>,
)
data class ProductCustomizationOptionDto(
    val id: String,
    val name: String,
    val priceDeltaCfa: Int,
    val sortOrder: Int,
    val active: Boolean,
)
data class CooperativeDto(
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val area: String?,
    val status: CooperativeApprovalStatus,
    val members: List<CooperativeMemberDto>,
    val reviewedByUserId: String? = null,
    val reviewedAt: Instant? = null,
    val reviewReason: String? = null,
    val history: List<dev.orestegabo.sequo_api.domain.delivery.OperatorHistoryEntry> = emptyList(),
)
data class CooperativeMemberDto(val id: String, val merchantId: String, val role: CooperativeMemberRole)

@Service
class PlatformCommerceService(
    private val profiles: CustomerProfileRepository,
    private val addresses: CustomerAddressRepository,
    private val plans: SubscriptionPlanRepository,
    private val subscriptions: CustomerSubscriptionRepository,
    private val referralCredits: ReferralDeliveryCreditRepository,
    private val paymentProviders: PaymentProviderConfigurationRepository,
    private val paymentConfiguration: PaymentConfigurationStatus,
    private val products: CommerceProductRepository,
    private val customizationGroups: ProductCustomizationGroupRepository,
    private val customizationOptions: ProductCustomizationOptionRepository,
    private val cooperatives: MerchantCooperativeRepository,
    private val cooperativeMembers: MerchantCooperativeMemberRepository,
    private val merchants: CommerceMerchantRepository,
    private val users: UserRepository,
    private val referralAwardPolicy: dev.orestegabo.sequo_api.domain.subscription.ReferralAwardPolicy,
    @org.springframework.beans.factory.annotation.Qualifier("benefitsClock") private val benefitsClock: Clock,
) {
    @Transactional(readOnly = true)
    fun profile(userId: String): CustomerProfileDto? = profiles.findByUserId(userId)?.toDto()

    @Transactional
    fun upsertProfile(userId: String, request: UpsertCustomerProfileRequest): CustomerProfileDto {
        val now = Instant.now()
        val receiptEmail = request.receiptEmail?.let { ApiInputPolicy.normalizedEmail(it, "receiptEmail") }
        val defaultAddressId = ApiInputPolicy.optionalIdentifier(request.defaultAddressId, "defaultAddressId")?.also {
            require(addresses.findByIdAndUserId(it, userId)?.active == true) {
                "defaultAddressId does not reference a saved address."
            }
        }
        val profile = profiles.findByUserId(userId)?.also {
            it.displayName = ApiInputPolicy.requiredShortText(request.displayName, "displayName", 180)
            it.phoneNumber = ApiInputPolicy.optionalShortText(request.phoneNumber, "phoneNumber", 64)
            it.receiptEmail = receiptEmail
            it.defaultAddressId = defaultAddressId
            it.marketingOptIn = request.marketingOptIn
            it.updatedAt = now
        } ?: CustomerProfileRecord(
            userId = userId,
            displayName = ApiInputPolicy.requiredShortText(request.displayName, "displayName", 180),
            phoneNumber = ApiInputPolicy.optionalShortText(request.phoneNumber, "phoneNumber", 64),
            receiptEmail = receiptEmail,
            defaultAddressId = defaultAddressId,
            marketingOptIn = request.marketingOptIn,
            createdAt = now,
            updatedAt = now,
        )
        return profiles.save(profile).toDto()
    }

    @Transactional(readOnly = true)
    fun addresses(userId: String): List<CustomerAddressDto> =
        addresses.findByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId).map { it.toDto() }

    @Transactional
    fun upsertAddress(userId: String, addressId: String?, request: UpsertCustomerAddressRequest): CustomerAddressDto {
        request.latitude?.let { require(it in -90.0..90.0) { "latitude must be between -90 and 90." } }
        request.longitude?.let { require(it in -180.0..180.0) { "longitude must be between -180 and 180." } }
        val now = Instant.now()
        val existing = addressId?.let {
            requireNotNull(addresses.findByIdAndUserId(ApiInputPolicy.requiredIdentifier(it, "addressId"), userId)) {
                "addressId does not reference a saved address."
            }
        }
        val record = existing?.also { applyAddress(it, request, now) } ?: CustomerAddressRecord(
            id = UUID.randomUUID().toString(),
            userId = userId,
            kind = request.kind,
            label = ApiInputPolicy.requiredShortText(request.label, "label", 180),
            recipientName = ApiInputPolicy.requiredShortText(request.recipientName, "recipientName", 180),
            phoneNumber = ApiInputPolicy.requiredShortText(request.phoneNumber, "phoneNumber", 64),
            city = ApiInputPolicy.requiredShortText(request.city, "city", 120),
            area = ApiInputPolicy.optionalShortText(request.area, "area", 160),
            streetHint = ApiInputPolicy.optionalShortText(request.streetHint, "streetHint", 240),
            latitude = request.latitude,
            longitude = request.longitude,
            createdAt = now,
            updatedAt = now,
        )
        return addresses.save(record).toDto()
    }

    @Transactional(readOnly = true)
    fun subscriptionPlans(): List<SubscriptionPlanDto> =
        plans.findByActiveTrueOrderByMonthlyPriceCfaAsc().filter { !it.approvalReference.isNullOrBlank() }.map { it.toDto() }

    @Transactional(readOnly = true)
    fun currentSubscription(userId: String): CustomerSubscriptionDto? =
        subscriptions.findTopByUserIdOrderByCreatedAtDesc(userId)?.toDto()

    @Transactional
    fun startSubscription(userId: String, request: StartSubscriptionRequest): CustomerSubscriptionDto {
        requireCustomerAccount(userId)
        val planId = ApiInputPolicy.requiredIdentifier(request.planId, "planId")
        val plan = plans.findById(planId).orElse(null)
        require(plan?.active == true && !plan.approvalReference.isNullOrBlank()) { "Subscription plan is unavailable or awaiting SEQUO approval." }
        subscriptions.findTopByUserIdOrderByCreatedAtDesc(userId)?.let { existing ->
            if (existing.planId == planId) return existing.toDto()
            require(existing.status != CustomerSubscriptionStatus.ACTIVE) { "Changing an active subscription requires SEQUO review." }
        }
        val now = benefitsClock.instant()
        return subscriptions.save(
            CustomerSubscriptionRecord(
                id = UUID.randomUUID().toString(),
                userId = userId,
                planId = planId,
                status = CustomerSubscriptionStatus.PAUSED,
                startedAt = now,
                currentPeriodEndsAt = now,
                createdAt = now,
                updatedAt = now,
            )
        ).toDto()
    }

    @Transactional(readOnly = true)
    fun referralCredits(userId: String): List<ReferralCreditDto> =
        referralCredits.findByReferrerUserIdOrReferredUserIdOrderByCreatedAtDesc(userId, userId).map {
            it.toDto().let { dto -> if (it.referrerUserId == userId) dto else dto.copy(remainingCfa = null) }
        }

    @Transactional
    fun createReferralCredit(userId: String, request: CreateReferralCreditRequest): ReferralCreditDto {
        requireCustomerAccount(userId)
        val referredUserId = ApiInputPolicy.requiredIdentifier(request.referredUserId, "referredUserId")
        require(referredUserId != userId) { "A user cannot refer themselves." }
        require(users.findById(referredUserId).orElse(null)?.active == true) { "Referred account is unavailable." }
        referralCredits.findFirstByReferrerUserIdAndReferredUserIdOrderByCreatedAtAsc(userId, referredUserId)?.let { return it.toDto() }
        require(referralAwardPolicy.enabled) { "Referral awards are awaiting an approved SEQUO policy." }
        val now = benefitsClock.instant()
        return referralCredits.save(
            ReferralDeliveryCreditRecord(
                id = UUID.randomUUID().toString(),
                referrerUserId = userId,
                referredUserId = referredUserId,
                creditCfa = referralAwardPolicy.creditCfa,
                status = ReferralCreditStatus.RESERVED,
                sourceCode = ApiInputPolicy.optionalShortText(request.sourceCode, "sourceCode", 64),
                createdAt = now,
                expiresAt = referralAwardPolicy.expiryDays.takeIf { it > 0 }?.let { now.plusSeconds(Math.multiplyExact(it, 86400L)) },
            )
        ).toDto()
    }

    private fun requireCustomerAccount(userId: String) {
        val user = users.lockCheckoutOwner(userId)
        require(user?.active == true && RoleCode.CUSTOMER in user.roles) { "An active customer account is required." }
    }

    @Transactional(readOnly = true)
    fun paymentProviders(): List<PaymentProviderConfigurationDto> =
        paymentProviders.findAllByOrderByDisplayNameAsc().map {
            it.toDto().copy(apiConfigured = paymentConfiguration.apiConfigured(it.providerId))
        }

    @Transactional(readOnly = true)
    fun customizations(productId: String): List<ProductCustomizationGroupDto> {
        val id = ApiInputPolicy.requiredIdentifier(productId, "productId")
        return customizationsByProduct(listOf(id))[id].orEmpty()
    }

    @Transactional(readOnly = true)
    fun publicCustomizations(productId: String): List<ProductCustomizationGroupDto> {
        val id = ApiInputPolicy.requiredIdentifier(productId, "productId")
        if (products.findByIdAndStatus(id, CatalogProductStatus.ACTIVE) == null) return emptyList()
        return customizations(id).filter { it.active }
    }

    @Transactional(readOnly = true)
    fun customizationsByProduct(productIds: Collection<String>): Map<String, List<ProductCustomizationGroupDto>> {
        if (productIds.isEmpty()) return emptyMap()
        val groups = customizationGroups.findByProductIdInOrderBySortOrderAsc(productIds)
        if (groups.isEmpty()) return emptyMap()
        val options = customizationOptions.findByGroupIdInOrderBySortOrderAsc(groups.map { it.id }).groupBy { it.groupId }
        return groups.groupBy({ it.productId }, { it.toDto(options[it.id].orEmpty()) })
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = [IllegalArgumentException::class])
    fun quoteCustomizations(productId: String, request: QuoteProductCustomizationsRequest): ProductCustomizationQuote {
        val product = requireNotNull(products.findByIdAndStatus(
            ApiInputPolicy.requiredIdentifier(productId, "productId"), CatalogProductStatus.ACTIVE,
        )) { "Product is unavailable." }
        val base = requireNotNull(product.basePriceCfa) { "Product has no listed price." }
        val groups = customizations(product.id).filter { it.active }.mapNotNull { group ->
            require(group.minChoices <= group.options.size) { "Required options are unavailable." }
            if (group.options.isEmpty()) return@mapNotNull null
            FoodCustomizationGroup(
                group.id, group.name, FoodCustomizationGroupType.OptionalMultiChoice,
                group.options.map { FoodCustomizationOption(it.id, it.name, it.priceDeltaCfa, it.active) },
                group.minChoices, minOf(group.maxChoices, group.options.size),
            )
        }
        val result = FoodCustomizationService().validateAndSnapshot(FoodCustomizationMenu(product.id, groups), request.selections)
        require(result is FoodCustomizationResult.Accepted) {
            (result as FoodCustomizationResult.Rejected).errors.joinToString(" ") { it.message }
        }
        val options = result.snapshot.groups.sumOf { group -> group.selectedOptions.sumOf { it.priceDeltaCfa.toLong() } }
        require(base + options <= Int.MAX_VALUE) { "Customized product price is too large." }
        return ProductCustomizationQuote(product.id, base, options.toInt(), (base + options).toInt())
    }

    @Transactional
    fun replaceCustomizations(
        productId: String,
        request: ReplaceProductCustomizationsRequest,
        merchantId: String? = null,
    ): List<ProductCustomizationGroupDto> {
        val safeId = ApiInputPolicy.requiredIdentifier(productId, "productId")
        val product = requireNotNull(products.lockForCustomization(safeId)) { "Product does not exist." }
        require(merchantId == null || product.merchantId == merchantId) { "Product does not belong to the authenticated merchant." }
        val existing = customizationGroups.findByProductIdInOrderBySortOrderAsc(listOf(safeId))
        require(request.groups.isEmpty() || existing.isNotEmpty() ||
            product.category?.lowercase() in setOf("food", "perishable")) { "Options are available for food products." }
        require(request.groups.size <= 20) { "Too many option groups." }
        val existingOptions = if (existing.isEmpty()) emptyMap() else customizationOptions
            .findByGroupIdInOrderBySortOrderAsc(existing.map { it.id }).groupBy { it.groupId }
        val retainedGroups = mutableSetOf<String>()
        val newGroupNames = mutableSetOf<String>()
        request.groups.forEach { input ->
            val name = ApiInputPolicy.requiredShortText(input.name, "group.name", 120)
            require(input.id != null || newGroupNames.add(name)) { "Duplicate new option group name." }
            val min = input.minChoices ?: 0
            val max = input.maxChoices ?: 1
            require(input.options.size in 1..50 && min in 0..minOf(max, input.options.size) && max <= 50) {
                "Invalid option group constraints."
            }
            val group = (if (input.id != null) {
                requireNotNull(existing.find { it.id == input.id }) { "Unknown option group." }
            } else existing.singleOrNull { it.name == name })
                ?: ProductCustomizationGroupRecord(UUID.randomUUID().toString(), safeId, name = name)
            require(retainedGroups.add(group.id)) { "Duplicate option group." }
            group.name = name
            group.minChoices = min
            group.maxChoices = max
            group.sortOrder = input.sortOrder ?: 0
            group.active = input.active ?: true
            customizationGroups.save(group)

            val oldOptions = existingOptions[group.id].orEmpty()
            val retainedOptions = mutableSetOf<String>()
            val newOptionNames = mutableSetOf<String>()
            input.options.forEach { inputOption ->
                val optionName = ApiInputPolicy.requiredShortText(inputOption.name, "option.name", 140)
                require(inputOption.id != null || newOptionNames.add(optionName)) { "Duplicate new option name." }
                val delta = inputOption.priceDeltaCfa ?: 0
                require(delta >= 0) { "Option price must be non-negative." }
                val option = (if (inputOption.id != null) {
                    requireNotNull(oldOptions.find { it.id == inputOption.id }) { "Unknown option." }
                } else oldOptions.singleOrNull { it.name == optionName })
                    ?: ProductCustomizationOptionRecord(UUID.randomUUID().toString(), group.id, name = optionName)
                require(retainedOptions.add(option.id)) { "Duplicate option." }
                option.name = optionName
                option.priceDeltaCfa = delta
                option.sortOrder = inputOption.sortOrder ?: 0
                option.active = inputOption.active ?: true
                customizationOptions.save(option)
            }
            customizationOptions.deleteAll(oldOptions.filterNot { it.id in retainedOptions })
        }
        val removed = existing.filterNot { it.id in retainedGroups }
        if (removed.isNotEmpty()) {
            customizationOptions.deleteByGroupIdIn(removed.map { it.id })
            customizationOptions.flush()
            customizationGroups.deleteAll(removed)
        }
        return customizations(safeId)
    }

    @Transactional(readOnly = true)
    fun cooperatives(): List<CooperativeDto> =
        cooperatives.findAllByOrderByCreatedAtDesc().map { it.toDto(cooperativeMembers.findByCooperativeIdOrderByCreatedAtAsc(it.id)) }

    @Transactional
    fun createCooperative(request: CreateCooperativeRequest): CooperativeDto {
        val ownerMerchantId = ApiInputPolicy.requiredIdentifier(request.ownerMerchantId, "ownerMerchantId")
        require(merchants.existsById(ownerMerchantId)) { "ownerMerchantId does not reference an existing merchant." }
        val cooperative = cooperatives.save(
            MerchantCooperativeRecord(
                id = UUID.randomUUID().toString(),
                code = ApiInputPolicy.requiredIdentifier(request.code, "code"),
                name = ApiInputPolicy.requiredShortText(request.name, "name", 180),
                city = ApiInputPolicy.requiredShortText(request.city, "city", 120),
                area = ApiInputPolicy.optionalShortText(request.area, "area", 160),
                status = CooperativeApprovalStatus.PENDING_REVIEW,
            )
        )
        cooperativeMembers.save(
            MerchantCooperativeMemberRecord(
                id = UUID.randomUUID().toString(),
                cooperativeId = cooperative.id,
                merchantId = ownerMerchantId,
                role = CooperativeMemberRole.OWNER,
            )
        )
        return cooperative.toDto(cooperativeMembers.findByCooperativeIdOrderByCreatedAtAsc(cooperative.id))
    }

    @Transactional
    fun addCooperativeMember(cooperativeId: String, request: AddCooperativeMemberRequest): CooperativeDto {
        val safeCooperativeId = ApiInputPolicy.requiredIdentifier(cooperativeId, "cooperativeId")
        val merchantId = ApiInputPolicy.requiredIdentifier(request.merchantId, "merchantId")
        val cooperative = requireNotNull(cooperatives.lockForReview(safeCooperativeId)) { "cooperativeId does not reference an existing cooperative." }
        require(merchants.existsById(merchantId)) { "merchantId does not reference an existing merchant." }
        require(!cooperativeMembers.existsByCooperativeIdAndMerchantId(safeCooperativeId, merchantId)) {
            "merchantId is already a cooperative member."
        }
        cooperativeMembers.save(
            MerchantCooperativeMemberRecord(
                id = UUID.randomUUID().toString(),
                cooperativeId = safeCooperativeId,
                merchantId = merchantId,
                role = request.role ?: CooperativeMemberRole.MEMBER,
            )
        )
        cooperative.status = CooperativeApprovalStatus.PENDING_REVIEW
        cooperative.reviewedByUserId = null; cooperative.reviewedAt = null; cooperative.reviewReason = null
        return cooperative.toDto(cooperativeMembers.findByCooperativeIdOrderByCreatedAtAsc(safeCooperativeId))
    }

    @Transactional
    fun reviewCooperative(cooperativeId: String, reviewerUserId: String, request: ReviewCooperativeRequest): CooperativeDto {
        val safeCooperativeId = ApiInputPolicy.requiredIdentifier(cooperativeId, "cooperativeId")
        require(request.status in setOf(CooperativeApprovalStatus.APPROVED, CooperativeApprovalStatus.REJECTED, CooperativeApprovalStatus.SUSPENDED)) { "Choose an explicit review outcome." }
        val cooperative = requireNotNull(cooperatives.lockForReview(safeCooperativeId)) { "cooperativeId does not reference an existing cooperative." }
        val reason = ApiInputPolicy.requiredLongText(request.reason.orEmpty(), "reason")
        if (cooperative.status == request.status && cooperative.reviewedByUserId == reviewerUserId && cooperative.reviewReason == reason)
            return cooperative.toDto(cooperativeMembers.findByCooperativeIdOrderByCreatedAtAsc(safeCooperativeId))
        cooperative.status = request.status
        cooperative.reviewReason = reason
        cooperative.reviewHistoryJson = dev.orestegabo.sequo_api.domain.delivery.writeHistory(
            dev.orestegabo.sequo_api.domain.delivery.readHistory(cooperative.reviewHistoryJson) +
                dev.orestegabo.sequo_api.domain.delivery.OperatorHistoryEntry(request.status.name, reviewerUserId, reason, Instant.now()))
        cooperative.reviewedByUserId = reviewerUserId
        cooperative.reviewedAt = Instant.now()
        cooperative.updatedAt = cooperative.reviewedAt!!
        return cooperative.toDto(cooperativeMembers.findByCooperativeIdOrderByCreatedAtAsc(safeCooperativeId))
    }

    private fun applyAddress(record: CustomerAddressRecord, request: UpsertCustomerAddressRequest, now: Instant) {
        record.kind = request.kind
        record.label = ApiInputPolicy.requiredShortText(request.label, "label", 180)
        record.recipientName = ApiInputPolicy.requiredShortText(request.recipientName, "recipientName", 180)
        record.phoneNumber = ApiInputPolicy.requiredShortText(request.phoneNumber, "phoneNumber", 64)
        record.city = ApiInputPolicy.requiredShortText(request.city, "city", 120)
        record.area = ApiInputPolicy.optionalShortText(request.area, "area", 160)
        record.streetHint = ApiInputPolicy.optionalShortText(request.streetHint, "streetHint", 240)
        record.latitude = request.latitude
        record.longitude = request.longitude
        record.updatedAt = now
    }
}

@RestController
@RequestMapping("/api/customer/profile")
class CustomerProfileController(private val service: PlatformCommerceService) {
    @GetMapping
    fun profile(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()
        return service.profile(userId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    @PutMapping
    fun upsertProfile(@AuthenticationPrincipal userId: String?, @RequestBody request: UpsertCustomerProfileRequest): ResponseEntity<Any> =
        platformAuthenticated(userId) { service.upsertProfile(it, request) }
}

@RestController
@RequestMapping("/api/customer/addresses")
class CustomerAddressController(private val service: PlatformCommerceService) {
    @GetMapping
    fun addresses(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> =
        platformAuthenticated(userId) { service.addresses(it) }

    @PostMapping
    fun create(@AuthenticationPrincipal userId: String?, @RequestBody request: UpsertCustomerAddressRequest): ResponseEntity<Any> =
        platformAuthenticated(userId) { service.upsertAddress(it, null, request) }

    @PutMapping("/{addressId}")
    fun update(
        @AuthenticationPrincipal userId: String?,
        @PathVariable addressId: String,
        @RequestBody request: UpsertCustomerAddressRequest,
    ): ResponseEntity<Any> =
        platformAuthenticated(userId) { service.upsertAddress(it, addressId, request) }
}

@RestController
@RequestMapping("/api/subscriptions")
class SubscriptionController(private val service: PlatformCommerceService) {
    @GetMapping("/plans")
    fun plans(): ResponseEntity<Any> = platformSafe { service.subscriptionPlans() }

    @GetMapping("/me")
    fun current(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> =
        platformAuthenticated(userId) { service.currentSubscription(it) ?: emptyMap<String, String>() }

    @PostMapping
    fun start(authentication: Authentication?, @RequestBody request: StartSubscriptionRequest): ResponseEntity<Any> =
        platformCustomer(authentication) { service.startSubscription(it, request) }
}

@RestController
@RequestMapping("/api/referrals")
class ReferralController(private val service: PlatformCommerceService) {
    @GetMapping("/credits")
    fun credits(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> =
        platformAuthenticated(userId) { service.referralCredits(it) }

    @PostMapping
    fun create(authentication: Authentication?, @RequestBody request: CreateReferralCreditRequest): ResponseEntity<Any> =
        platformCustomer(authentication) { service.createReferralCredit(it, request) }
}

@RestController
@RequestMapping("/api/payments/providers")
class PaymentProviderReadinessController(private val service: PlatformCommerceService) {
    @GetMapping
    fun providers(): ResponseEntity<Any> = platformSafe { service.paymentProviders() }
}

@RestController
@RequestMapping("/api/catalog/products/{productId}/customizations")
class ProductCustomizationController(private val service: PlatformCommerceService) {
    @GetMapping
    fun list(@PathVariable productId: String): ResponseEntity<Any> =
        platformSafe { service.publicCustomizations(productId) }

    @PostMapping("/quote")
    fun quote(@PathVariable productId: String, @RequestBody request: QuoteProductCustomizationsRequest): ResponseEntity<Any> =
        platformSafe { service.quoteCustomizations(productId, request) }

    @PutMapping
    fun replace(authentication: Authentication?, @PathVariable productId: String, @RequestBody request: ReplaceProductCustomizationsRequest): ResponseEntity<Any> =
        if (authentication?.hasAnyRole(RoleGroups.AdminOnly) == true) {
            platformSafe { service.replaceCustomizations(productId, request) }
        } else ResponseEntity.status(403).build()
}

@RestController
@RequestMapping("/api/cooperatives", "/api/admin/cooperatives")
class CooperativeController(private val service: PlatformCommerceService) {
    @GetMapping
    fun list(authentication: Authentication?, request: jakarta.servlet.http.HttpServletRequest): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (request.requestURI.startsWith("/api/admin/")) staff(authentication) { service.cooperatives() }
        else if (authentication?.hasAnyRole(RoleGroups.AdminOnly) == true) platformSafe { service.cooperatives() }
        else platformSafe { service.cooperatives().filter { it.status == CooperativeApprovalStatus.APPROVED }.map { it.copy(reviewedByUserId = null, reviewedAt = null, reviewReason = null, history = emptyList()) } }
    @PostMapping
    fun create(authentication: Authentication?, @RequestBody request: CreateCooperativeRequest): ResponseEntity<Any> = staff(authentication) { service.createCooperative(request) }
    @PostMapping("/{cooperativeId}/members")
    fun addMember(authentication: Authentication?, @PathVariable cooperativeId: String, @RequestBody request: AddCooperativeMemberRequest): ResponseEntity<Any> = staff(authentication) { service.addCooperativeMember(cooperativeId, request) }
    @PostMapping("/{cooperativeId}/review")
    fun review(authentication: Authentication?, @PathVariable cooperativeId: String, @RequestBody request: ReviewCooperativeRequest): ResponseEntity<Any> =
        staff(authentication) { service.reviewCooperative(cooperativeId, requireNotNull(authentication).name, request) }
    private fun staff(auth: Authentication?, block: () -> Any): ResponseEntity<Any> =
        if (auth == null) ResponseEntity.status(401).build()
        else if (!auth.hasAnyRole(RoleGroups.AdminOnly)) ResponseEntity.status(403).build()
        else platformSafe(block)
}

private fun platformSafe(block: () -> Any): ResponseEntity<Any> = ResponseEntity.ok(block())

private fun platformCustomer(authentication: Authentication?, block: (String) -> Any): ResponseEntity<Any> =
    if (authentication == null || !authentication.isAuthenticated) ResponseEntity.status(401).build()
    else if (!authentication.hasAnyRole(setOf(RoleCode.CUSTOMER))) ResponseEntity.status(403).build()
    else platformSafe { block(authentication.name) }

private fun platformAuthenticated(userId: String?, block: (String) -> Any): ResponseEntity<Any> {
    if (userId == null) return ResponseEntity.status(401).build()
    return platformSafe { block(userId) }
}

private fun CustomerProfileRecord.toDto(): CustomerProfileDto =
    CustomerProfileDto(userId, displayName, phoneNumber, receiptEmail, defaultAddressId, marketingOptIn)

private fun CustomerAddressRecord.toDto(): CustomerAddressDto =
    CustomerAddressDto(id, kind, label, recipientName, phoneNumber, city, area, streetHint, latitude, longitude)

private fun SubscriptionPlanRecord.toDto(): SubscriptionPlanDto =
    SubscriptionPlanDto(id, name, monthlyPriceCfa, deliveryDiscountPercent, monthlyDiscountCapCfa, loyaltyMonthsThreshold, loyaltyDiscountPercent)

private fun CustomerSubscriptionRecord.toDto(): CustomerSubscriptionDto =
    CustomerSubscriptionDto(id, userId, planId, status, startedAt, currentPeriodEndsAt, cancelAtPeriodEnd)

private fun ReferralDeliveryCreditRecord.toDto(): ReferralCreditDto =
    ReferralCreditDto(id, referrerUserId, referredUserId, creditCfa, status, sourceCode, createdAt, expiresAt, remainingCfa)

private fun PaymentProviderConfigurationRecord.toDto(): PaymentProviderConfigurationDto =
    PaymentProviderConfigurationDto(
        providerId,
        displayName,
        countryCode,
        readiness,
        customerCheckoutEnabled,
        refundEnabled,
        courierPayoutEnabled,
        notes,
    )

private fun ProductCustomizationGroupRecord.toDto(options: List<ProductCustomizationOptionRecord>): ProductCustomizationGroupDto =
    ProductCustomizationGroupDto(
        id,
        productId,
        name,
        minChoices,
        maxChoices,
        sortOrder,
        active,
        options.map { it.toDto() },
    )

private fun ProductCustomizationOptionRecord.toDto(): ProductCustomizationOptionDto =
    ProductCustomizationOptionDto(id, name, priceDeltaCfa, sortOrder, active)

private fun MerchantCooperativeRecord.toDto(members: List<MerchantCooperativeMemberRecord>): CooperativeDto =
    CooperativeDto(id, code, name, city, area, status, members.map { it.toDto() }, reviewedByUserId, reviewedAt, reviewReason, dev.orestegabo.sequo_api.domain.delivery.readHistory(reviewHistoryJson))

private fun MerchantCooperativeMemberRecord.toDto(): CooperativeMemberDto =
    CooperativeMemberDto(id, merchantId, role)
