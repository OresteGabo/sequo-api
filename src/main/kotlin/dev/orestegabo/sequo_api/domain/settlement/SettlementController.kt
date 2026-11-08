package dev.orestegabo.sequo_api.domain.settlement

import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry
import dev.orestegabo.sequo_api.domain.reference.referenceOrNull

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasMerchantScope
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/settlements")
class SettlementController(
    private val service: SettlementPersistenceService,
    private val references: PublicReferenceRegistry? = null,
) {
    @GetMapping("/merchant-payouts")
    fun merchantPayouts(
        authentication: Authentication?,
        @RequestParam merchantId: String,
        @RequestParam(required = false) status: MerchantPayoutStatus?,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.MerchantOperators) { auth ->
        val safeMerchantId = ApiInputPolicy.requiredIdentifier(merchantId, "merchantId")
        if (!auth.canReadMerchantPayouts(safeMerchantId)) return@authenticated ResponseEntity.status(403).build()
        ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
            service.listMerchantPayouts(safeMerchantId, status).map { payout ->
                val view = payout.toResponse(references)
                if (auth.hasAnyRole(RoleGroups.AdminOnly)) view else view.copy(
                    ledgerEntries = view.ledgerEntries.map { it.copy(courierId = null, courierReference = null, relayPointId = null, relayReference = null) })
            })
    }

    @GetMapping("/ledger")
    fun ledgerEntries(
        authentication: Authentication?,
        @RequestParam sourceType: SettlementSourceType,
        @RequestParam sourceId: String,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOnly) {
        ResponseEntity.ok(service.listLedgerEntries(sourceType, ApiInputPolicy.requiredIdentifier(sourceId, "sourceId")).map { it.toResponse(references) })
    }

    @PostMapping("/merchant-payouts/evaluate-eligible")
    fun evaluateEligible(
        authentication: Authentication?,
        @RequestParam(required = false) evaluatedAt: Instant?,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOnly) {
        ResponseEntity.ok(service.evaluateEligible(evaluatedAt ?: Instant.now()).map { it.toResponse(references) })
    }

    private fun Authentication.canReadMerchantPayouts(merchantId: String): Boolean =
        hasAnyRole(RoleGroups.AdminOnly) || (
            hasAnyRole(setOf(RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF)) && hasMerchantScope(merchantId)
        )

    private fun authenticated(
        authentication: Authentication?,
        roles: Set<RoleCode>,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(roles)) {
            ResponseEntity.status(403).build()
        } else operation(authentication)
}

data class MerchantPayoutResponse(
    val id: String,
    val merchantId: String,
    val orderId: String,
    val sourceOrderItemId: String,
    val merchantNetCfa: Int,
    val commissionCfa: Int,
    val platformMarginCfa: Int,
    val packageReceivedAt: Instant,
    val payoutEligibleAt: Instant,
    val payoutDueBy: Instant,
    val status: MerchantPayoutStatus,
    val workflowType: SettlementWorkflowType,
    val activeReturnHold: Boolean,
    val activeDisputeHold: Boolean,
    val ledgerEntries: List<SettlementLedgerEntryResponse>,
    val publicReference: String? = null,
    val merchantReference: String? = null,
    val orderReference: String? = null,
    val sourceOrderItemReference: String? = null,
)

data class SettlementLedgerEntryResponse(
    val id: String,
    val account: SettlementLedgerAccount,
    val direction: SettlementLedgerDirection,
    val amountCfa: Int,
    val merchantId: String?,
    val courierId: String?,
    val relayPointId: String?,
    val sourceType: SettlementSourceType,
    val sourceId: String,
    val description: String,
    val createdAt: Instant,
    val workforceType: dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType? = null,
    val publicReference: String? = null,
    val merchantReference: String? = null,
    val courierReference: String? = null,
    val relayReference: String? = null,
    val missionReference: String? = null,
)

private fun MerchantPayoutAccrual.toResponse(references: PublicReferenceRegistry? = null) = MerchantPayoutResponse(
    id = id,
    publicReference = references.referenceOrNull(PublicReferenceKind.FINANCIAL, id),
    merchantReference = references.referenceOrNull(PublicReferenceKind.MERCHANT, merchantId),
    orderReference = references.referenceOrNull(PublicReferenceKind.ORDER, orderId),
    sourceOrderItemReference = references.referenceOrNull(PublicReferenceKind.MERCHANT_ORDER, sourceOrderItemId),
    merchantId = merchantId,
    orderId = orderId,
    sourceOrderItemId = sourceOrderItemId,
    merchantNetCfa = merchantNetCfa,
    commissionCfa = commissionCfa,
    platformMarginCfa = platformMarginCfa,
    packageReceivedAt = packageReceivedAt,
    payoutEligibleAt = payoutEligibleAt,
    payoutDueBy = payoutDueBy,
    status = status,
    workflowType = workflowType,
    activeReturnHold = activeReturnHold,
    activeDisputeHold = activeDisputeHold,
    ledgerEntries = ledgerEntries.map { it.toResponse(references) },
)

private fun SettlementLedgerEntry.toResponse(references: PublicReferenceRegistry? = null) = SettlementLedgerEntryResponse(
    id = id,
    publicReference = references.referenceOrNull(PublicReferenceKind.LEDGER, id),
    merchantReference = references.referenceOrNull(PublicReferenceKind.MERCHANT, merchantId),
    courierReference = references.referenceOrNull(PublicReferenceKind.COURIER, courierId),
    relayReference = references.referenceOrNull(PublicReferenceKind.RELAY, relayPointId),
    missionReference = if (sourceType == SettlementSourceType.DeliveryMission) references.referenceOrNull(PublicReferenceKind.DELIVERY, sourceId) else null,
    account = account,
    direction = direction,
    amountCfa = amountCfa,
    merchantId = merchantId,
    courierId = courierId,
    relayPointId = relayPointId,
    sourceType = sourceType,
    sourceId = sourceId,
    description = description,
    createdAt = createdAt,
    workforceType = workforceType,
)
