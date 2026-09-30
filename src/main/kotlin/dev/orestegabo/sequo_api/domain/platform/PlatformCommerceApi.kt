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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
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
}

interface PaymentProviderConfigurationRepository : JpaRepository<PaymentProviderConfigurationRecord, String> {
    fun findAllByOrderByDisplayNameAsc(): List<PaymentProviderConfigurationRecord>
}

interface ProductCustomizationGroupRepository : JpaRepository<ProductCustomizationGroupRecord, String> {
    fun findByProductIdOrderBySortOrderAsc(productId: String): List<ProductCustomizationGroupRecord>
    fun deleteByProductId(productId: String): Long
}

interface ProductCustomizationOptionRepository : JpaRepository<ProductCustomizationOptionRecord, String> {
    fun findByGroupIdInOrderBySortOrderAsc(groupIds: Collection<String>): List<ProductCustomizationOptionRecord>
    fun deleteByGroupIdIn(groupIds: Collection<String>): Long
}

interface MerchantCooperativeRepository : JpaRepository<MerchantCooperativeRecord, String> {
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
data class ProductCustomizationOptionRequest(val name: String, val priceDeltaCfa: Int = 0, val sortOrder: Int = 0, val active: Boolean = true)
data class ProductCustomizationGroupRequest(
    val name: String,
    val minChoices: Int = 0,
    val maxChoices: Int = 1,
    val sortOrder: Int = 0,
    val active: Boolean = true,
    val options: List<ProductCustomizationOptionRequest> = emptyList(),
)
data class ReplaceProductCustomizationsRequest(val groups: List<ProductCustomizationGroupRequest>)
data class CreateCooperativeRequest(val code: String, val name: String, val city: String, val area: String? = null, val ownerMerchantId: String)
data class AddCooperativeMemberRequest(val merchantId: String, val role: CooperativeMemberRole = CooperativeMemberRole.MEMBER)
data class ReviewCooperativeRequest(val status: CooperativeApprovalStatus)

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
    private val products: CommerceProductRepository,
    private val customizationGroups: ProductCustomizationGroupRepository,
    private val customizationOptions: ProductCustomizationOptionRepository,
    private val cooperatives: MerchantCooperativeRepository,
    private val cooperativeMembers: MerchantCooperativeMemberRepository,
    private val merchants: CommerceMerchantRepository,
) {
    @Transactional(readOnly = true)
    fun profile(userId: String): CustomerProfileDto? = profiles.findByUserId(userId)?.toDto()

    @Transactional
    fun upsertProfile(userId: String, request: UpsertCustomerProfileRequest): CustomerProfileDto {
        val now = Instant.now()
        val receiptEmail = request.receiptEmail?.let { ApiInputPolicy.normalizedEmail(it, "receiptEmail") }
        val profile = profiles.findByUserId(userId)?.also {
            it.displayName = ApiInputPolicy.requiredShortText(request.displayName, "displayName", 180)
            it.phoneNumber = ApiInputPolicy.optionalShortText(request.phoneNumber, "phoneNumber", 64)
            it.receiptEmail = receiptEmail
            it.defaultAddressId = ApiInputPolicy.optionalIdentifier(request.defaultAddressId, "defaultAddressId")
            it.marketingOptIn = request.marketingOptIn
            it.updatedAt = now
        } ?: CustomerProfileRecord(
            userId = userId,
            displayName = ApiInputPolicy.requiredShortText(request.displayName, "displayName", 180),
            phoneNumber = ApiInputPolicy.optionalShortText(request.phoneNumber, "phoneNumber", 64),
            receiptEmail = receiptEmail,
            defaultAddressId = ApiInputPolicy.optionalIdentifier(request.defaultAddressId, "defaultAddressId"),
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
        val record = addressId?.let { addresses.findByIdAndUserId(ApiInputPolicy.requiredIdentifier(it, "addressId"), userId) }?.also {
            applyAddress(it, request, now)
        } ?: CustomerAddressRecord(
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
        plans.findByActiveTrueOrderByMonthlyPriceCfaAsc().map { it.toDto() }

    @Transactional(readOnly = true)
    fun currentSubscription(userId: String): CustomerSubscriptionDto? =
        subscriptions.findTopByUserIdOrderByCreatedAtDesc(userId)?.toDto()

    @Transactional
    fun startSubscription(userId: String, request: StartSubscriptionRequest): CustomerSubscriptionDto {
        val planId = ApiInputPolicy.requiredIdentifier(request.planId, "planId")
        require(plans.existsById(planId)) { "planId does not reference an active subscription plan." }
        val now = Instant.now()
        return subscriptions.save(
            CustomerSubscriptionRecord(
                id = UUID.randomUUID().toString(),
                userId = userId,
                planId = planId,
                status = CustomerSubscriptionStatus.ACTIVE,
                startedAt = now,
                currentPeriodEndsAt = now.plusSeconds(30L * 24L * 60L * 60L),
                createdAt = now,
                updatedAt = now,
            )
        ).toDto()
    }

    @Transactional(readOnly = true)
    fun referralCredits(userId: String): List<ReferralCreditDto> =
        referralCredits.findByReferrerUserIdOrReferredUserIdOrderByCreatedAtDesc(userId, userId).map { it.toDto() }

    @Transactional
    fun createReferralCredit(userId: String, request: CreateReferralCreditRequest): ReferralCreditDto {
        val referredUserId = ApiInputPolicy.requiredIdentifier(request.referredUserId, "referredUserId")
        require(referredUserId != userId) { "A user cannot refer themselves." }
        val now = Instant.now()
        return referralCredits.save(
            ReferralDeliveryCreditRecord(
                id = UUID.randomUUID().toString(),
                referrerUserId = userId,
                referredUserId = referredUserId,
                creditCfa = 500,
                status = ReferralCreditStatus.RESERVED,
                sourceCode = ApiInputPolicy.optionalShortText(request.sourceCode, "sourceCode", 64),
                createdAt = now,
                expiresAt = now.plusSeconds(90L * 24L * 60L * 60L),
            )
        ).toDto()
    }

    @Transactional(readOnly = true)
    fun paymentProviders(): List<PaymentProviderConfigurationDto> =
        paymentProviders.findAllByOrderByDisplayNameAsc().map { it.toDto() }

    @Transactional(readOnly = true)
    fun customizations(productId: String): List<ProductCustomizationGroupDto> {
        val safeProductId = ApiInputPolicy.requiredIdentifier(productId, "productId")
        val groups = customizationGroups.findByProductIdOrderBySortOrderAsc(safeProductId)
        val options = customizationOptions.findByGroupIdInOrderBySortOrderAsc(groups.map { it.id }).groupBy { it.groupId }
        return groups.map { it.toDto(options[it.id].orEmpty()) }
    }

    @Transactional
    fun replaceCustomizations(productId: String, request: ReplaceProductCustomizationsRequest): List<ProductCustomizationGroupDto> {
        val safeProductId = ApiInputPolicy.requiredIdentifier(productId, "productId")
        require(products.existsById(safeProductId)) { "productId does not reference an existing product." }
        val existingGroups = customizationGroups.findByProductIdOrderBySortOrderAsc(safeProductId)
        if (existingGroups.isNotEmpty()) {
            customizationOptions.deleteByGroupIdIn(existingGroups.map { it.id })
            customizationGroups.deleteByProductId(safeProductId)
        }
        request.groups.forEach { group ->
            require(group.minChoices >= 0) { "minChoices must be non-negative." }
            require(group.maxChoices >= group.minChoices) { "maxChoices must be greater than or equal to minChoices." }
            val groupId = UUID.randomUUID().toString()
            customizationGroups.save(
                ProductCustomizationGroupRecord(
                    id = groupId,
                    productId = safeProductId,
                    name = ApiInputPolicy.requiredShortText(group.name, "group.name", 120),
                    minChoices = group.minChoices,
                    maxChoices = group.maxChoices,
                    sortOrder = group.sortOrder,
                    active = group.active,
                )
            )
            group.options.forEach { option ->
                customizationOptions.save(
                    ProductCustomizationOptionRecord(
                        id = UUID.randomUUID().toString(),
                        groupId = groupId,
                        name = ApiInputPolicy.requiredShortText(option.name, "option.name", 140),
                        priceDeltaCfa = option.priceDeltaCfa,
                        sortOrder = option.sortOrder,
                        active = option.active,
                    )
                )
            }
        }
        return customizations(safeProductId)
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
        val cooperative = cooperatives.findById(safeCooperativeId).orElseThrow {
            IllegalArgumentException("cooperativeId does not reference an existing cooperative.")
        }
        require(merchants.existsById(merchantId)) { "merchantId does not reference an existing merchant." }
        require(!cooperativeMembers.existsByCooperativeIdAndMerchantId(safeCooperativeId, merchantId)) {
            "merchantId is already a cooperative member."
        }
        cooperativeMembers.save(
            MerchantCooperativeMemberRecord(
                id = UUID.randomUUID().toString(),
                cooperativeId = safeCooperativeId,
                merchantId = merchantId,
                role = request.role,
            )
        )
        return cooperative.toDto(cooperativeMembers.findByCooperativeIdOrderByCreatedAtAsc(safeCooperativeId))
    }

    @Transactional
    fun reviewCooperative(cooperativeId: String, reviewerUserId: String, request: ReviewCooperativeRequest): CooperativeDto {
        val safeCooperativeId = ApiInputPolicy.requiredIdentifier(cooperativeId, "cooperativeId")
        require(request.status != CooperativeApprovalStatus.DRAFT) { "DRAFT is not a review outcome." }
        val cooperative = cooperatives.findById(safeCooperativeId).orElseThrow {
            IllegalArgumentException("cooperativeId does not reference an existing cooperative.")
        }
        cooperative.status = request.status
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
    fun start(@AuthenticationPrincipal userId: String?, @RequestBody request: StartSubscriptionRequest): ResponseEntity<Any> =
        platformAuthenticated(userId) { service.startSubscription(it, request) }
}

@RestController
@RequestMapping("/api/referrals")
class ReferralController(private val service: PlatformCommerceService) {
    @GetMapping("/credits")
    fun credits(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> =
        platformAuthenticated(userId) { service.referralCredits(it) }

    @PostMapping
    fun create(@AuthenticationPrincipal userId: String?, @RequestBody request: CreateReferralCreditRequest): ResponseEntity<Any> =
        platformAuthenticated(userId) { service.createReferralCredit(it, request) }
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
        platformSafe { service.customizations(productId) }

    @PutMapping
    fun replace(@PathVariable productId: String, @RequestBody request: ReplaceProductCustomizationsRequest): ResponseEntity<Any> =
        platformSafe { service.replaceCustomizations(productId, request) }
}

@RestController
@RequestMapping("/api/cooperatives")
class CooperativeController(private val service: PlatformCommerceService) {
    @GetMapping
    fun list(): ResponseEntity<Any> = platformSafe { service.cooperatives() }

    @PostMapping
    fun create(@RequestBody request: CreateCooperativeRequest): ResponseEntity<Any> =
        platformSafe { service.createCooperative(request) }

    @PostMapping("/{cooperativeId}/members")
    fun addMember(@PathVariable cooperativeId: String, @RequestBody request: AddCooperativeMemberRequest): ResponseEntity<Any> =
        platformSafe { service.addCooperativeMember(cooperativeId, request) }

    @PostMapping("/{cooperativeId}/review")
    fun review(
        @AuthenticationPrincipal reviewerUserId: String?,
        @PathVariable cooperativeId: String,
        @RequestBody request: ReviewCooperativeRequest,
    ): ResponseEntity<Any> =
        platformAuthenticated(reviewerUserId) { service.reviewCooperative(cooperativeId, it, request) }
}

private fun platformSafe(block: () -> Any): ResponseEntity<Any> = ResponseEntity.ok(block())

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
    ReferralCreditDto(id, referrerUserId, referredUserId, creditCfa, status, sourceCode, createdAt, expiresAt)

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
    CooperativeDto(id, code, name, city, area, status, members.map { it.toDto() })

private fun MerchantCooperativeMemberRecord.toDto(): CooperativeMemberDto =
    CooperativeMemberDto(id, merchantId, role)
