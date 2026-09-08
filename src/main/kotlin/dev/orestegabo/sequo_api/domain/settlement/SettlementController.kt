package dev.orestegabo.sequo_api.domain.settlement

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
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
) {
    @GetMapping("/merchant-payouts")
    fun merchantPayouts(
        authentication: Authentication?,
        @RequestParam merchantId: String,
        @RequestParam(required = false) status: MerchantPayoutStatus?,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.MerchantOperators) { auth ->
        if (!auth.canReadMerchantPayouts(merchantId)) return@authenticated ResponseEntity.status(403).build()
        ResponseEntity.ok(service.listMerchantPayouts(merchantId, status).map { it.toResponse() })
    }

    @GetMapping("/ledger")
    fun ledgerEntries(
        authentication: Authentication?,
        @RequestParam sourceType: SettlementSourceType,
        @RequestParam sourceId: String,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOnly) {
        ResponseEntity.ok(service.listLedgerEntries(sourceType, sourceId).map { it.toResponse() })
    }

    @PostMapping("/merchant-payouts/evaluate-eligible")
    fun evaluateEligible(
        authentication: Authentication?,
        @RequestParam(required = false) evaluatedAt: Instant?,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOnly) {
        ResponseEntity.ok(service.evaluateEligible(evaluatedAt ?: Instant.now()).map { it.toResponse() })
    }

    private fun Authentication.canReadMerchantPayouts(merchantId: String): Boolean =
        hasAnyRole(RoleGroups.AdminOnly) || (
            hasAnyRole(setOf(RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF)) && name == merchantId
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
        } else try {
            operation(authentication)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ErrorResponse("invalid_settlement_request", e.message ?: "Invalid settlement request.")
            )
        }

    data class ErrorResponse(val code: String, val message: String)
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
)

private fun MerchantPayoutAccrual.toResponse() = MerchantPayoutResponse(
    id = id,
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
    ledgerEntries = ledgerEntries.map { it.toResponse() },
)

private fun SettlementLedgerEntry.toResponse() = SettlementLedgerEntryResponse(
    id = id,
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
)
