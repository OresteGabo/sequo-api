package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.auth.UserStatus
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionOverrideRecord
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionOverrideRepository
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionPolicy
import dev.orestegabo.sequo_api.domain.platform.SubscriptionPlanRepository
import dev.orestegabo.sequo_api.domain.relay.RelayParcelCategory
import dev.orestegabo.sequo_api.domain.relay.RelayParcelPolicy
import dev.orestegabo.sequo_api.domain.relay.RelayParcelPolicyConfig
import dev.orestegabo.sequo_api.domain.returns.CustomerReturnPolicy
import dev.orestegabo.sequo_api.domain.subscription.ReferralAwardPolicy
import org.springframework.core.env.Environment
import java.time.Duration
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMission
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordDestination
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordMode
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordStatus
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderStatus
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.order.FulfillmentPriority
import dev.orestegabo.sequo_api.domain.order.OrderRoute
import dev.orestegabo.sequo_api.domain.order.OrderServiceLevel
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRecord
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRepository
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutStatus
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookEventRecord
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookEventRepository
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookProviderStatus
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingSettingsRecord
import dev.orestegabo.sequo_api.domain.relay.RelayParcelRecord
import dev.orestegabo.sequo_api.domain.relay.RelayParcelRecordRepository
import dev.orestegabo.sequo_api.domain.relay.RelayParcelStatus
import dev.orestegabo.sequo_api.domain.relay.RelayStorageFeeAssessmentRecordRepository
import dev.orestegabo.sequo_api.domain.returns.ReturnRequestRecordRepository
import dev.orestegabo.sequo_api.domain.returns.ReturnStatus
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutAccrualRecord
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutAccrualRecordRepository
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutStatus
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.http.CacheControl
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

interface AdminWebReadPort {
    fun overview(limit: Int): AdminWebOverviewDto
    fun users(limit: Int): List<AdminUserDto>
    fun merchants(limit: Int): List<AdminMerchantDto>
    fun orders(limit: Int): List<AdminOrderDto>
    fun deliveries(limit: Int): AdminDeliveryDto
    fun payments(limit: Int): AdminPaymentsDto
    fun paymentCheckout(checkoutId: String): AdminPaymentCheckoutDetailDto? = null
    fun commissions(limit: Int): AdminCommissionsDto
    fun deliveryPricing(): AdminDeliveryPricingDto
    fun returns(limit: Int): List<AdminReturnDto>
    fun relay(limit: Int): AdminRelayDto
    fun businessParameters(): AdminBusinessParametersDto
}

data class AdminWebOverviewDto(
    val generatedAt: Instant,
    val totals: AdminWebTotalsDto,
    val operationalLinks: Map<String, String>,
)

data class AdminWebTotalsDto(
    val users: Long,
    val merchants: Long,
    val orders: Long,
    val deliveryMissions: Long,
    val merchantSubOrders: Long,
    val relayParcels: Long,
    val merchantPayouts: Long,
    val commissionOverrides: Long,
    val paymentWebhookEvents: Long,
)

data class AdminUserDto(
    val id: String,
    val email: String?,
    val phoneNumber: String?,
    val displayName: String?,
    val provider: AuthProvider,
    val status: UserStatus,
    val active: Boolean,
    val roles: Set<RoleCode>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminMerchantDto(
    val id: String,
    val ownerUserId: String?,
    val name: String,
    val status: String,
    val commissionRateBps: Int,
    val walletProvider: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminOrderDto(
    val id: String,
    val customerId: String,
    val serviceLevel: OrderServiceLevel,
    val route: OrderRoute,
    val fulfillmentPriority: FulfillmentPriority,
    val customerFacingStatus: String,
    val itemSubtotalCfa: Int,
    val deliveryFeeCfa: Int,
    val totalCfa: Int,
    val paymentProvider: String,
    val paymentStatus: String,
    val orderStatus: CustomerOrderStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminDeliveryDto(
    val missions: List<AdminDeliveryMissionDto>,
    val merchantSubOrderCounts: Map<MerchantSubOrderStatus, Long>,
)

data class AdminDeliveryMissionDto(
    val id: String,
    val deliveryCode: String,
    val orderId: String,
    val merchantSubOrderId: String?,
    val courierId: String?,
    val deliveryMode: DeliveryMissionRecordMode,
    val destinationType: DeliveryMissionRecordDestination,
    val status: DeliveryMissionRecordStatus,
    val customerDeliveryFeeCfa: Int,
    val courierFeeCfa: Int,
    val shortfallCfa: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deliveryOption: dev.orestegabo.sequo_api.domain.delivery.DeliveryModeOffer? = null,
    val subscriberPriority: Boolean = false,
    val assignmentWorkforceType: dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType? = null,
    val assignmentReason: String? = null,
)

data class AdminPaymentsDto(
    val merchantPayouts: List<AdminMerchantPayoutDto>,
    val webhookEvents: List<AdminPaymentWebhookDto>,
    val checkouts: List<AdminPaymentCheckoutDto> = emptyList(),
)

data class AdminPaymentCheckoutDto(
    val checkoutId: String,
    val paymentProvider: String,
    val paymentReference: String,
    val providerReference: String?,
    val amountCfa: Int,
    val status: PendingPaymentCheckoutStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val orderId: String?,
    val orderStatus: CustomerOrderStatus?,
    val currency: String = "XOF",
)

data class AdminPaymentCheckoutDetailDto(
    val checkout: AdminPaymentCheckoutDto,
    val events: List<AdminPaymentWebhookDto>,
)

data class AdminMerchantPayoutDto(
    val id: String,
    val merchantId: String,
    val orderId: String,
    val sourceOrderItemId: String,
    val merchantNetCfa: Int,
    val commissionCfa: Int,
    val platformMarginCfa: Int,
    val status: MerchantPayoutStatus,
    val activeReturnHold: Boolean,
    val activeDisputeHold: Boolean,
    val payoutEligibleAt: Instant,
    val payoutDueBy: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminPaymentWebhookDto(
    val id: String,
    val provider: String,
    val eventId: String,
    val checkoutId: String,
    val paymentReference: String,
    val providerReference: String?,
    val amountCfa: Int,
    val status: PaymentWebhookProviderStatus,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val reconciliationStatus: String? = null,
    val reconciliationReason: String? = null,
    val reconciledAt: Instant? = null,
)

data class AdminCommissionsDto(
    val defaultRateBps: Int,
    val overrides: List<AdminCommissionOverrideDto>,
)

data class AdminCommissionOverrideDto(
    val merchantId: String,
    val commissionRateBps: Int,
    val reason: String?,
    val updatedByUserId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminDeliveryPricingDto(
    val active: AdminDeliveryPricingSettingsDto,
)

data class AdminDeliveryPricingSettingsDto(
    val profile: String,
    val minimumDeliveryFeeCfa: Int,
    val extraKmFeeCfa: Int,
    val includedKm: Int,
    val effectiveFrom: Instant?,
)

data class AdminReturnDto(
    val id: String,
    val orderId: String,
    val status: ReturnStatus,
    val requestedRefundCfa: Int,
    val relayPointId: String?,
    val updatedAt: Instant,
)

data class AdminRelayDto(
    val parcels: List<AdminRelayParcelDto>,
    val storageFeeAssessmentCount: Long,
)

data class AdminRelayParcelDto(
    val id: String,
    val relayPointId: String,
    val lockerId: String?,
    val orderId: String?,
    val deliveryMissionId: String?,
    val returnId: String?,
    val status: RelayParcelStatus,
    val depositedAt: Instant?,
    val pickedUpAt: Instant?,
    val collectedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AdminBusinessParametersDto(
    val minimumDeliveryPriceCfa: Int,
    val extraKmPriceCfa: Int,
    val includedKm: Int,
    val defaultCommissionRateBps: Int,
    val commissionOverrideCount: Long,
    val relayStorageFeeAssessmentCount: Long,
    val relayExcludedProductPolicySource: String,
    val returnWindowPolicySource: String,
    val generatedAt: Instant = Instant.now(),
    val parameters: List<AdminBusinessParameterDto> = emptyList(),
)

enum class AdminParameterState { CONTRACT_DEFAULT, CONFIGURED, PENDING_APPROVAL, PENDING_DECISION, PREPARED_ONLY }

data class AdminBusinessParameterDto(
    val reference: Int,
    val key: String,
    val label: String,
    val state: AdminParameterState,
    val effectiveValues: Map<String, String>,
    val source: String,
    val changeAuthority: String,
    val changeProcedure: String,
    val verification: String,
    val pendingDecision: String? = null,
)

/** Named, non-secret reads only: this is an inventory of live policy, not an environment dump. */
@Service
class AdminBusinessParameterInventory(
    private val plans: SubscriptionPlanRepository,
    private val referralPolicy: ReferralAwardPolicy,
    private val returnPolicy: CustomerReturnPolicy,
    private val relayPolicy: RelayParcelPolicy,
    private val environment: Environment,
) {
    fun parameters(pricing: AdminDeliveryPricingSettingsDto, overrideCount: Long): List<AdminBusinessParameterDto> {
        val commission = MerchantCommissionPolicy()
        val customerAttempts = environment.getProperty("sequo.bargaining.max-customer-attempts", Int::class.java, 3)
        val merchantAttempts = environment.getProperty("sequo.bargaining.max-merchant-attempts", Int::class.java, 3)
        val lockHours = environment.getProperty("sequo.bargaining.accepted-price-lock-hours", Long::class.java, 24L)
        val feeAfter = Duration.between(Instant.EPOCH, relayPolicy.storageFeeStartsAt(Instant.EPOCH))
        val reviewAfter = environment.getProperty("sequo.relay.review-after")?.takeIf { it.isNotBlank() }?.let(Duration::parse)
            ?: RelayParcelPolicyConfig().returnToSellerReviewAfter
        val configuredPlans = plans.findAll().sortedBy { it.id }
        val approvedPlans = configuredPlans.filter { it.active && !it.approvalReference.isNullOrBlank() }
        val unapprovedPlans = configuredPlans.count { it.active && it.approvalReference.isNullOrBlank() }
        val subscriptionValues = linkedMapOf(
            "Approved active plans" to approvedPlans.size.toString(),
            "Active plans awaiting approval" to unapprovedPlans.toString(),
            "Customer eligibility" to "Verified paid activation, active approved plan and current subscription period; legacy ACTIVE alone grants no discount.",
        )
        configuredPlans.forEach { plan ->
            val state = when { !plan.active -> "INACTIVE"; plan.approvalReference.isNullOrBlank() -> "PENDING_APPROVAL"; else -> "APPROVED_ACTIVE" }
            subscriptionValues["Plan ${plan.id}"] = "$state; configured discount ${plan.deliveryDiscountPercent}%; monthly price ${plan.monthlyPriceCfa} CFA; monthly discount cap ${plan.monthlyDiscountCapCfa?.let { "$it CFA" } ?: "none"}; loyalty ${plan.loyaltyDiscountPercent}% after ${plan.loyaltyMonthsThreshold} months (verified continuous paid tenure required; startedAt alone is insufficient)."
        }
        if (configuredPlans.isEmpty()) subscriptionValues["Plan configuration"] = "No plans configured; no subscription discount applies."
        val deployment = "SEQUO policy owner approves; authorized deployment operator applies the server configuration."
        val pricingSource = "DeliveryPricingService.effectiveRecord(): active delivery_pricing_settings effective at server time; otherwise DeliveryPricingService.DefaultSettings."
        val pricingChange = "SEQUO authorizes a controlled delivery_pricing_settings change (profile, minimum_delivery_fee_cfa, extra_km_fee_cfa, included_km, effective_from, active). Future profiles do not apply early. Refresh the effective read and verify quotes before rollout."
        return listOf(
            AdminBusinessParameterDto(131, "minimum-delivery-price", "Minimum delivery price",
                if (pricing.minimumDeliveryFeeCfa == 400 && pricing.includedKm == 5) AdminParameterState.CONTRACT_DEFAULT else AdminParameterState.CONFIGURED,
                linkedMapOf("Minimum delivery charge" to "${pricing.minimumDeliveryFeeCfa} CFA", "Included distance" to "${pricing.includedKm} km", "Active profile" to pricing.profile,
                    "Effective from" to (pricing.effectiveFrom?.toString() ?: "Built-in fallback")), pricingSource, deployment, pricingChange,
                "GET /api/admin/web/delivery-pricing and authenticated /api/cart/quote; test below, at and above the included distance."),
            AdminBusinessParameterDto(132, "extra-kilometer-price", "Price per additional kilometer",
                if (pricing.extraKmFeeCfa == 100 && pricing.includedKm == 5) AdminParameterState.CONTRACT_DEFAULT else AdminParameterState.CONFIGURED,
                linkedMapOf("Additional kilometer charge" to "${pricing.extraKmFeeCfa} CFA", "Rounding" to "Distance rounds up to the next whole kilometer; extra charge starts above ${pricing.includedKm} km."),
                pricingSource, deployment, pricingChange, "Compare authoritative quotes immediately below, at and above the distance boundary; client totals and distance are not policy inputs."),
            AdminBusinessParameterDto(133, "merchant-commissions", "Merchant commission percentages", AdminParameterState.CONFIGURED,
                linkedMapOf("Runtime default" to "${commission.defaultRateBps} basis points", "Allowed range" to "${commission.minRateBps}–${commission.maxRateBps} basis points", "Persisted overrides" to overrideCount.toString(), "Precedence" to "Merchant override, otherwise MerchantCommissionPolicy default; order snapshots retain their agreed rate."),
                "MerchantCommissionConfigurationService.resolveRateBps; merchant_commission_overrides; MerchantCommissionPolicy.",
                "SEQUO ADMIN or SUPER_ADMIN for merchant overrides; reviewed deployment for the default/range.",
                "PUT /api/commissions/merchant-overrides/{merchantId} with commissionRateBps and reason; DELETE restores the runtime default. Change the global policy only through an approved deployment.",
                "GET /api/commissions/merchant-overrides/{merchantId}; verify a new merchant sub-order snapshot and preserve existing order history."),
            AdminBusinessParameterDto(134, "subscriber-discounts", "Subscriber discount rates",
                if (approvedPlans.isNotEmpty()) AdminParameterState.CONFIGURED else if (configuredPlans.isEmpty()) AdminParameterState.PREPARED_ONLY else AdminParameterState.PENDING_APPROVAL,
                subscriptionValues, "subscription_plans approval/active/rate/cap/loyalty fields and DeliveryBenefitsService; paid customer activation is independently verified.", deployment,
                "SEQUO approves the complete plan; authorized operations updates the persisted plan and approval reference. Activate a customer only after verified payment evidence. Zero loyalty rate or threshold disables loyalty. Loyalty requires an approved written rule and verified continuous paid tenure; startedAt alone does not enable it.",
                "GET /api/subscriptions/plans, authenticated /api/subscriptions/benefits and /api/cart/quote; verify unpaid/unapproved records give no benefit, then cap and paid-checkout consumption.",
                if (unapprovedPlans > 0 || configuredPlans.isEmpty()) "No plan or legacy seed is approved merely because it is active. Obtain SEQUO written approval for pending rates/caps/loyalty before activation." else null),
            AdminBusinessParameterDto(135, "referral-credits", "Referral credits",
                if (referralPolicy.enabled) AdminParameterState.CONFIGURED else AdminParameterState.PENDING_APPROVAL,
                linkedMapOf("Configured new award" to "${referralPolicy.creditCfa} CFA", "New award policy enabled" to referralPolicy.enabled.toString(),
                    "Effective new award" to "${if (referralPolicy.enabled) referralPolicy.creditCfa else 0} CFA", "Award expiry" to if (referralPolicy.expiryDays == 0L) "No policy expiry configured" else "${referralPolicy.expiryDays} days",
                    "Existing credits" to "Persisted amount, approval, beneficiary, remaining balance and expiry apply; RESERVED or unapproved credits cannot reduce delivery fees.",
                    "Usage" to "Delivery fee only; reserve at checkout and consume once after verified payment."),
                "ReferralAwardPolicy; referral_delivery_credits; DeliveryBenefitsService and persisted delivery_benefit_applications.", deployment,
                "Set sequo.referrals.credit-cfa, approval-reference and expiry-days only after SEQUO approval. Issued RESERVED credits require operator-verified approval before becoming usable; no automatic cash transfer is implied.",
                "Refresh this inventory and authenticated delivery benefits/quote; verify pending credits, exhausted balance, expiry and repeated paid checkout.",
                if (!referralPolicy.enabled) "A positive award and written approval reference are required. No tariff or credit amount is inferred from seed data." else null),
            AdminBusinessParameterDto(136, "bargaining-attempts", "Bargaining attempt limits",
                if (customerAttempts == 3 && merchantAttempts == 3) AdminParameterState.CONTRACT_DEFAULT else AdminParameterState.CONFIGURED,
                linkedMapOf("Customer attempts" to customerAttempts.toString(), "Merchant counteroffers" to merchantAttempts.toString(), "Scope" to "Persisted same-customer, same-merchant and same-product history; opening another thread does not reset the budget."),
                "PersistedBargainingService BargainingPolicy; sequo.bargaining.max-customer-attempts and sequo.bargaining.max-merchant-attempts.", deployment,
                "Change BARGAINING_MAX_CUSTOMER_ATTEMPTS and BARGAINING_MAX_MERCHANT_ATTEMPTS in authorized deployment configuration and restart. Review existing-party budgets before changing limits.",
                "GET an authorized /api/bargaining/threads/{id}; compare maxCustomerAttempts/maxMerchantAttempts and test the final allowed and first rejected attempts."),
            AdminBusinessParameterDto(137, "bargaining-price-lock", "Negotiated price lock duration",
                if (lockHours == 24L) AdminParameterState.CONTRACT_DEFAULT else AdminParameterState.CONFIGURED,
                linkedMapOf("New accepted-price lock" to "$lockHours hours", "Validity boundary" to "Usable strictly before the persisted expiresAt; unavailable at expiry.", "Existing agreements" to "Keep their stored acceptance and expiry; a deployment change does not rewrite history."),
                "PersistedBargainingService; sequo.bargaining.accepted-price-lock-hours; bargaining_threads accepted price/expiry.", deployment,
                "Change BARGAINING_ACCEPTED_PRICE_LOCK_HOURS in authorized deployment configuration and restart; approve any separate migration of existing agreements.",
                "Read acceptedAt/expiresAt from an authorized bargaining thread and verify basket/checkout immediately before and at expiry."),
            AdminBusinessParameterDto(138, "return-window", "Return request window",
                if (returnPolicy.window == Duration.ofHours(72)) AdminParameterState.CONTRACT_DEFAULT else AdminParameterState.CONFIGURED,
                linkedMapOf("Current request window" to "${returnPolicy.window.toHours()} hours", "Boundary" to "From authoritative persisted handoff; a request at the deadline is accepted, one after is rejected.", "Clock" to "Server returnClock; client requestedAt is ignored."),
                "CustomerReturnPolicy.window; sequo.returns.window-hours; ReturnPersistenceService.customerEligibility/requestForCustomer.", deployment,
                "Change RETURN_WINDOW_HOURS in authorized deployment configuration and restart. SEQUO must decide how an altered current eligibility window applies to already delivered orders; do not rewrite handoff timestamps.",
                "GET /api/returns/orders/{orderId}/eligibility as owner; verify immediately before, at and after the returned deadline."),
            AdminBusinessParameterDto(139, "relay-custody", "Relay custody deadlines and storage fees", AdminParameterState.PENDING_APPROVAL,
                linkedMapOf("New deposit fee threshold" to "Strictly after $feeAfter from deposit", "Review threshold" to "At $reviewAfter from deposit", "Daily tariff" to "No global tariff; authorized manual assessment supplies an approved dailyFeeCfa. Existing assessment rate is retained.",
                    "Review scheduler enabled" to environment.getProperty("sequo.relay.delayed-parcel-scheduler.enabled", "false").equals("true", ignoreCase = true).toString(), "Disposition" to "Explicit authorized operator decision; review never automatically disposes of a parcel.", "Legacy fee starts" to "Missing feeStartsAt is not backfilled or charged without an approved active-parcel policy."),
                "DomainConfig relayParcelPolicy; sequo.relay.storage-fee-after/review-after; sequo.relay.delayed-parcel-scheduler.enabled; persisted parcel feeStartsAt and storage-fee assessments.",
                "SEQUO approves any tariff/active-parcel policy; authorized deployment operator changes deadlines and AdminOperations assesses approved fees.",
                "Deploy approved ISO-8601 duration properties for new policy. Use POST /api/relay/parcels/storage-fees/assess only with an approved dailyFeeCfa; preserve old assessments. RELAY_DELAYED_PARCEL_SCHEDULER_ENABLED controls review scheduling only. No automatic tariff is configured by this inventory.",
                "Read parcel deadlines and /api/relay/parcels/storage-fees; test before/at/after fee and review boundaries and repeat assessment without duplicate ledger increments.",
                "SEQUO tariff and legacy active-parcel migration decisions remain explicit; no fee amount is invented."),
            AdminBusinessParameterDto(140, "excluded-product-categories", "Products excluded from relay or return handling", AdminParameterState.CONFIGURED,
                linkedMapOf("Relay exclusions" to RelayParcelCategory.entries.filterNot(relayPolicy::relayPickupAllowed).joinToString { it.name },
                    "Additional configured return exclusions" to returnPolicy.excludedCategories.sortedBy { it.name }.joinToString { it.name }.ifBlank { "None configured; no blanket food-return ban inferred." },
                    "Click and collect return routing" to returnPolicy.clickAndCollectMode.name,
                    "Category resolution" to "Persisted checkout relay category, then supported legacy catalog/order fallback; eligibility is checked server-side."),
                "RelayParcelPolicy, RelayOrderEligibilityService/catalogRelayCategory and CustomerReturnPolicy.excludedCategories.", deployment,
                "Change RETURN_EXCLUDED_CATEGORIES only under approved SEQUO policy. Relay exclusions are centralized typed server rules prepared for a reviewed policy/config extension; verify checkout, reroute and returns together.",
                "Verify food/grocery, perishable, sensitive/pharmacy and general goods against cart relay points, checkout/reroute and owner return eligibility; keep supported legacy records.",
                if (returnPolicy.clickAndCollectMode.name == "PENDING") "Click and collect routing awaits SEQUO confirmation; it does not authorize an invented drop-off location." else null),
        )
    }
}

@Service
class AdminWebReadService(
    private val users: UserRepository,
    private val merchants: CommerceMerchantRepository,
    private val orders: CustomerOrderRecordRepository,
    private val deliveryMissions: DeliveryMissionRepository,
    private val merchantSubOrders: MerchantSubOrderRepository,
    private val merchantPayouts: MerchantPayoutAccrualRecordRepository,
    private val commissionOverrides: MerchantCommissionOverrideRepository,
    private val effectiveCommissions: dev.orestegabo.sequo_api.domain.commission.MerchantCommissionConfigurationService,
    private val pricing: DeliveryPricingService,
    private val parameterInventory: AdminBusinessParameterInventory,
    private val relayParcels: RelayParcelRecordRepository,
    private val relayStorageFees: RelayStorageFeeAssessmentRecordRepository,
    private val paymentWebhooks: PaymentWebhookEventRepository,
    private val pendingCheckouts: PendingPaymentCheckoutRepository,
    private val returns: ReturnRequestRecordRepository,
) : AdminWebReadPort {
    private val defaultCommissionRateBps = MerchantCommissionPolicy().defaultRateBps

    @Transactional(readOnly = true)
    override fun overview(limit: Int): AdminWebOverviewDto =
        AdminWebOverviewDto(
            generatedAt = Instant.now(),
            totals = AdminWebTotalsDto(
                users = users.count(),
                merchants = merchants.count(),
                orders = orders.count(),
                deliveryMissions = deliveryMissions.count(),
                merchantSubOrders = merchantSubOrders.count(),
                relayParcels = relayParcels.count(),
                merchantPayouts = merchantPayouts.count(),
                commissionOverrides = commissionOverrides.count(),
                paymentWebhookEvents = paymentWebhooks.count(),
            ),
            operationalLinks = mapOf(
                "monitoring" to "/api/admin/monitoring/operations",
                "users" to "/api/admin/web/users",
                "merchants" to "/api/admin/web/merchants",
                "orders" to "/api/admin/web/orders",
                "deliveries" to "/api/admin/web/deliveries",
                "payments" to "/api/admin/web/payments",
                "commissions" to "/api/admin/web/commissions",
                "deliveryPricing" to "/api/admin/web/delivery-pricing",
                "returns" to "/api/admin/web/returns",
                "relay" to "/api/admin/web/relay",
                "businessParameters" to "/api/admin/web/business-parameters",
            ),
        )

    @Transactional(readOnly = true)
    override fun users(limit: Int): List<AdminUserDto> =
        users.findAll(page(limit)).content.map(User::toAdminDto)

    @Transactional(readOnly = true)
    override fun merchants(limit: Int): List<AdminMerchantDto> =
        merchants.findAll(page(limit)).content.map { merchant ->
            merchant.toAdminDto().copy(commissionRateBps = effectiveCommissions.resolveRateBps(merchant.id))
        }

    @Transactional(readOnly = true)
    override fun orders(limit: Int): List<AdminOrderDto> =
        orders.findAll(page(limit)).content.map(CustomerOrderRecord::toAdminDto)

    @Transactional(readOnly = true)
    override fun deliveries(limit: Int): AdminDeliveryDto =
        AdminDeliveryDto(
            missions = deliveryMissions.findAll(page(limit).withSort(Sort.by(Sort.Direction.DESC, "updatedAt"))).content.map(DeliveryMission::toAdminDto),
            merchantSubOrderCounts = MerchantSubOrderStatus.entries.associateWith { status ->
                merchantSubOrders.countByStatusIn(setOf(status))
            },
        )

    @Transactional(readOnly = true)
    override fun payments(limit: Int): AdminPaymentsDto =
        AdminPaymentsDto(
            merchantPayouts = merchantPayouts.findAll(page(limit)).content.map(MerchantPayoutAccrualRecord::toAdminDto),
            webhookEvents = paymentWebhooks.findAll(page(limit)).content.map(PaymentWebhookEventRecord::toAdminDto),
            checkouts = pendingCheckouts.findAll(page(limit).withSort(Sort.by(Sort.Direction.DESC, "updatedAt")))
                .content.map(::paymentCheckoutSummary),
        )

    @Transactional(readOnly = true)
    override fun paymentCheckout(checkoutId: String): AdminPaymentCheckoutDetailDto? {
        val record = pendingCheckouts.findById(checkoutId).orElse(null) ?: return null
        return AdminPaymentCheckoutDetailDto(paymentCheckoutSummary(record),
            paymentWebhooks.findTop100ByCheckoutIdOrderByReceivedAtDesc(checkoutId).map(PaymentWebhookEventRecord::toAdminDto))
    }

    private fun paymentCheckoutSummary(checkout: PendingPaymentCheckoutRecord): AdminPaymentCheckoutDto {
        val order = orders.findByCheckoutId(checkout.checkoutId)
        return AdminPaymentCheckoutDto(checkout.checkoutId, checkout.paymentProvider, checkout.paymentReference,
            checkout.providerReference, checkout.amountCfa, checkout.status, checkout.createdAt, checkout.updatedAt,
            checkout.completedAt, order?.id, order?.orderStatus)
    }

    @Transactional(readOnly = true)
    override fun commissions(limit: Int): AdminCommissionsDto =
        AdminCommissionsDto(
            defaultRateBps = defaultCommissionRateBps,
            overrides = commissionOverrides.findAll(page(limit)).content.map(MerchantCommissionOverrideRecord::toAdminDto),
        )

    @Transactional(readOnly = true)
    override fun deliveryPricing(): AdminDeliveryPricingDto =
        AdminDeliveryPricingDto(
            active = pricing.effectiveRecord()?.toAdminDto()
                ?: DeliveryPricingService.DefaultSettings.toAdminDto(),
        )

    @Transactional(readOnly = true)
    override fun returns(limit: Int): List<AdminReturnDto> =
        returns.findAll(page(limit).withSort(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id")))).content.map {
            AdminReturnDto(it.id, it.orderId, it.status, it.requestedRefundCfa, it.relayPointId, it.updatedAt)
        }

    @Transactional(readOnly = true)
    override fun relay(limit: Int): AdminRelayDto =
        AdminRelayDto(
            parcels = relayParcels.findAll(page(limit)).content.map(RelayParcelRecord::toAdminDto),
            storageFeeAssessmentCount = relayStorageFees.count(),
        )

    @Transactional(readOnly = true)
    override fun businessParameters(): AdminBusinessParametersDto {
        val pricing = deliveryPricing().active
        return AdminBusinessParametersDto(
            minimumDeliveryPriceCfa = pricing.minimumDeliveryFeeCfa,
            extraKmPriceCfa = pricing.extraKmFeeCfa,
            includedKm = pricing.includedKm,
            defaultCommissionRateBps = defaultCommissionRateBps,
            commissionOverrideCount = commissionOverrides.count(),
            relayStorageFeeAssessmentCount = relayStorageFees.count(),
            relayExcludedProductPolicySource = "RelayParcelPolicy",
            returnWindowPolicySource = "CustomerReturnPolicy",
            parameters = parameterInventory.parameters(pricing, commissionOverrides.count()),
        )
    }

    private fun page(limit: Int): PageRequest =
        PageRequest.of(0, limit.coerceIn(1, 100))
}

@RestController
@RequestMapping("/api/admin/web")
class AdminWebController(
    private val service: AdminWebReadPort,
) {
    @GetMapping("/overview")
    fun overview(authentication: Authentication?, @RequestParam(defaultValue = "25") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.overview(limit)) }

    @GetMapping("/users")
    fun users(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.users(limit)) }

    @GetMapping("/merchants")
    fun merchants(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.merchants(limit)) }

    @GetMapping("/orders")
    fun orders(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.orders(limit)) }

    @GetMapping("/deliveries")
    fun deliveries(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.deliveries(limit)) }

    @GetMapping("/payments")
    fun payments(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.payments(limit)) }

    @GetMapping("/payments/checkouts/{checkoutId}")
    fun paymentCheckout(authentication: Authentication?, @PathVariable checkoutId: String): ResponseEntity<Any> =
        adminRequired(authentication) {
            service.paymentCheckout(dev.orestegabo.sequo_api.api.ApiInputPolicy.requiredIdentifier(checkoutId, "checkoutId"))
                ?.let { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(it) }
                ?: ResponseEntity.notFound().build()
        }

    @GetMapping("/commissions")
    fun commissions(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.commissions(limit)) }

    @GetMapping("/delivery-pricing")
    fun deliveryPricing(authentication: Authentication?): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.deliveryPricing()) }

    @GetMapping("/returns")
    fun returns(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.returns(limit)) }

    @GetMapping("/relay")
    fun relay(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.relay(limit)) }

    @GetMapping("/business-parameters")
    fun businessParameters(authentication: Authentication?): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.businessParameters()) }

    private fun adminRequired(authentication: Authentication?, operation: () -> ResponseEntity<Any>): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(RoleGroups.AdminOnly)) {
            ResponseEntity.status(403).build()
        } else {
            operation()
        }
}

private fun User.toAdminDto(): AdminUserDto =
    AdminUserDto(
        id = requireNotNull(id),
        email = email,
        phoneNumber = phoneNumber,
        displayName = name,
        provider = provider,
        status = status,
        active = active,
        roles = roles.toSet(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun MerchantRecord.toAdminDto(): AdminMerchantDto =
    AdminMerchantDto(
        id = id,
        ownerUserId = ownerUserId,
        name = name,
        status = status,
        commissionRateBps = commissionRateBps,
        walletProvider = walletProvider,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun CustomerOrderRecord.toAdminDto(): AdminOrderDto =
    AdminOrderDto(
        id = id,
        customerId = customerId,
        serviceLevel = serviceLevel,
        route = route,
        fulfillmentPriority = fulfillmentPriority,
        customerFacingStatus = customerFacingStatus,
        itemSubtotalCfa = itemSubtotalCfa,
        deliveryFeeCfa = deliveryFeeCfa,
        totalCfa = totalCfa,
        paymentProvider = paymentProvider,
        paymentStatus = paymentStatus.name,
        orderStatus = orderStatus,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun DeliveryMission.toAdminDto(): AdminDeliveryMissionDto =
    AdminDeliveryMissionDto(
        id = requireNotNull(id),
        deliveryCode = deliveryCode,
        orderId = orderId,
        merchantSubOrderId = merchantSubOrderId,
        courierId = courierId,
        deliveryMode = deliveryMode,
        destinationType = destinationType,
        status = status,
        customerDeliveryFeeCfa = customerDeliveryFeeCfa,
        courierFeeCfa = courierFeeCfa,
        shortfallCfa = shortfallCfa,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deliveryOption = deliveryOptionPayload?.let { dev.orestegabo.sequo_api.domain.delivery.readDeliveryModeOffer(it) },
        subscriberPriority = subscriberPriority,
        assignmentWorkforceType = assignmentWorkforceType,
        assignmentReason = assignmentReason,
    )

private fun MerchantPayoutAccrualRecord.toAdminDto(): AdminMerchantPayoutDto =
    AdminMerchantPayoutDto(
        id = id,
        merchantId = merchantId,
        orderId = orderId,
        sourceOrderItemId = sourceOrderItemId,
        merchantNetCfa = merchantNetCfa,
        commissionCfa = commissionCfa,
        platformMarginCfa = platformMarginCfa,
        status = status,
        activeReturnHold = activeReturnHold,
        activeDisputeHold = activeDisputeHold,
        payoutEligibleAt = payoutEligibleAt,
        payoutDueBy = payoutDueBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun PaymentWebhookEventRecord.toAdminDto(): AdminPaymentWebhookDto =
    AdminPaymentWebhookDto(
        id = id,
        provider = provider,
        eventId = eventId,
        checkoutId = checkoutId,
        paymentReference = paymentReference,
        providerReference = providerReference,
        amountCfa = amountCfa,
        status = status,
        occurredAt = occurredAt,
        receivedAt = receivedAt,
        reconciliationStatus = reconciliationStatus.name,
        reconciliationReason = reconciliationReason,
        reconciledAt = reconciledAt,
    )

private fun MerchantCommissionOverrideRecord.toAdminDto(): AdminCommissionOverrideDto =
    AdminCommissionOverrideDto(
        merchantId = merchantId,
        commissionRateBps = commissionRateBps,
        reason = reason,
        updatedByUserId = updatedByUserId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun DeliveryPricingSettingsRecord.toAdminDto(): AdminDeliveryPricingSettingsDto =
    AdminDeliveryPricingSettingsDto(
        profile = profile,
        minimumDeliveryFeeCfa = minimumDeliveryFeeCfa,
        extraKmFeeCfa = extraKmFeeCfa,
        includedKm = includedKm,
        effectiveFrom = effectiveFrom,
    )

private fun dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingSettings.toAdminDto(): AdminDeliveryPricingSettingsDto =
    AdminDeliveryPricingSettingsDto(
        profile = "default",
        minimumDeliveryFeeCfa = minimumDeliveryFeeCfa,
        extraKmFeeCfa = extraKmFeeCfa,
        includedKm = includedKm,
        effectiveFrom = null,
    )

private fun RelayParcelRecord.toAdminDto(): AdminRelayParcelDto =
    AdminRelayParcelDto(
        id = id,
        relayPointId = relayPointId,
        lockerId = lockerId,
        orderId = orderId,
        deliveryMissionId = deliveryMissionId,
        returnId = returnId,
        status = status,
        depositedAt = depositedAt,
        pickedUpAt = pickedUpAt,
        collectedAt = collectedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
