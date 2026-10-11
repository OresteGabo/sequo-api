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
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookEventRecord
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookEventRepository
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookProviderStatus
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingSettingsRecord
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingSettingsRepository
import dev.orestegabo.sequo_api.domain.relay.RelayParcelRecord
import dev.orestegabo.sequo_api.domain.relay.RelayParcelRecordRepository
import dev.orestegabo.sequo_api.domain.relay.RelayParcelStatus
import dev.orestegabo.sequo_api.domain.relay.RelayStorageFeeAssessmentRecordRepository
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutAccrualRecord
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutAccrualRecordRepository
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutStatus
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
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
    fun commissions(limit: Int): AdminCommissionsDto
    fun deliveryPricing(): AdminDeliveryPricingDto
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
)

data class AdminPaymentsDto(
    val merchantPayouts: List<AdminMerchantPayoutDto>,
    val webhookEvents: List<AdminPaymentWebhookDto>,
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
)

@Service
class AdminWebReadService(
    private val users: UserRepository,
    private val merchants: CommerceMerchantRepository,
    private val orders: CustomerOrderRecordRepository,
    private val deliveryMissions: DeliveryMissionRepository,
    private val merchantSubOrders: MerchantSubOrderRepository,
    private val merchantPayouts: MerchantPayoutAccrualRecordRepository,
    private val commissionOverrides: MerchantCommissionOverrideRepository,
    private val pricingSettings: DeliveryPricingSettingsRepository,
    private val relayParcels: RelayParcelRecordRepository,
    private val relayStorageFees: RelayStorageFeeAssessmentRecordRepository,
    private val paymentWebhooks: PaymentWebhookEventRepository,
) : AdminWebReadPort {
    private val defaultCommissionRateBps = 1500

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
                "relay" to "/api/admin/web/relay",
                "businessParameters" to "/api/admin/web/business-parameters",
            ),
        )

    @Transactional(readOnly = true)
    override fun users(limit: Int): List<AdminUserDto> =
        users.findAll(page(limit)).content.map(User::toAdminDto)

    @Transactional(readOnly = true)
    override fun merchants(limit: Int): List<AdminMerchantDto> =
        merchants.findAll(page(limit)).content.map(MerchantRecord::toAdminDto)

    @Transactional(readOnly = true)
    override fun orders(limit: Int): List<AdminOrderDto> =
        orders.findAll(page(limit)).content.map(CustomerOrderRecord::toAdminDto)

    @Transactional(readOnly = true)
    override fun deliveries(limit: Int): AdminDeliveryDto =
        AdminDeliveryDto(
            missions = deliveryMissions.findAll(page(limit)).content.map(DeliveryMission::toAdminDto),
            merchantSubOrderCounts = MerchantSubOrderStatus.entries.associateWith { status ->
                merchantSubOrders.countByStatusIn(setOf(status))
            },
        )

    @Transactional(readOnly = true)
    override fun payments(limit: Int): AdminPaymentsDto =
        AdminPaymentsDto(
            merchantPayouts = merchantPayouts.findAll(page(limit)).content.map(MerchantPayoutAccrualRecord::toAdminDto),
            webhookEvents = paymentWebhooks.findAll(page(limit)).content.map(PaymentWebhookEventRecord::toAdminDto),
        )

    @Transactional(readOnly = true)
    override fun commissions(limit: Int): AdminCommissionsDto =
        AdminCommissionsDto(
            defaultRateBps = defaultCommissionRateBps,
            overrides = commissionOverrides.findAll(page(limit)).content.map(MerchantCommissionOverrideRecord::toAdminDto),
        )

    @Transactional(readOnly = true)
    override fun deliveryPricing(): AdminDeliveryPricingDto =
        AdminDeliveryPricingDto(
            active = pricingSettings.findFirstByActiveTrueOrderByEffectiveFromDescCreatedAtDesc()?.toAdminDto()
                ?: DeliveryPricingService.DefaultSettings.toAdminDto(),
        )

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
            returnWindowPolicySource = "ReturnProcessing",
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
        adminRequired(authentication) { ResponseEntity.ok(service.payments(limit)) }

    @GetMapping("/commissions")
    fun commissions(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.commissions(limit)) }

    @GetMapping("/delivery-pricing")
    fun deliveryPricing(authentication: Authentication?): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.deliveryPricing()) }

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
