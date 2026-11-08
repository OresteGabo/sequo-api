package dev.orestegabo.sequo_api.domain.commission

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/commissions/merchant-overrides")
class MerchantCommissionController(
    private val service: MerchantCommissionConfigurationService,
) {
    data class UpsertMerchantCommissionRequest(
        val commissionRateBps: Int,
        val reason: String? = null,
        val expectedRevision: String? = null,
        val idempotencyKey: String? = null,
    )

    data class ClearMerchantCommissionRequest(val reason: String? = null, val expectedRevision: String? = null, val idempotencyKey: String? = null)

    data class MerchantCommissionRateResponse(
        val merchantId: String,
        val commissionRateBps: Int,
        val override: MerchantCommissionOverrideSnapshot?,
        val defaultRateBps: Int = 1500,
        val minRateBps: Int = 500,
        val maxRateBps: Int = 1500,
        val revision: String? = null,
        val operationId: String? = null,
        val history: List<MerchantCommissionAuditSnapshot> = emptyList(),
    )

    @GetMapping("/{merchantId}")
    fun get(
        authentication: Authentication?,
        @PathVariable merchantId: String,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        val safeMerchantId = ApiInputPolicy.requiredIdentifier(merchantId, "merchantId")
        val state = service.policyState(safeMerchantId)
        ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(
            MerchantCommissionRateResponse(
                merchantId = safeMerchantId,
                commissionRateBps = state.commissionRateBps,
                override = state.override,
                defaultRateBps = state.defaultRateBps, minRateBps = state.minRateBps, maxRateBps = state.maxRateBps,
                revision = state.revision, operationId = state.operationId, history = state.history,
            )
        )
    }

    @PutMapping("/{merchantId}")
    fun upsert(
        authentication: Authentication?,
        @PathVariable merchantId: String,
        @RequestBody request: UpsertMerchantCommissionRequest,
    ): ResponseEntity<Any> = adminOnly(authentication) { authenticated ->
        ResponseEntity.ok(
            service.upsertOverride(
                merchantId = ApiInputPolicy.requiredIdentifier(merchantId, "merchantId"),
                commissionRateBps = request.commissionRateBps,
                updatedByUserId = authenticated.name,
                reason = ApiInputPolicy.optionalShortText(request.reason, "reason", 500),
                expectedRevision = request.expectedRevision,
                idempotencyKey = request.idempotencyKey?.let { ApiInputPolicy.requiredIdempotencyKey(it) },
            )
        )
    }

    @DeleteMapping("/{merchantId}")
    fun clear(
        authentication: Authentication?,
        @PathVariable merchantId: String,
        @RequestBody(required = false) request: ClearMerchantCommissionRequest? = null,
    ): ResponseEntity<Any> = adminOnly(authentication) { actor ->
        val operation = service.clearOverride(ApiInputPolicy.requiredIdentifier(merchantId, "merchantId"),
            updatedByUserId = actor.name, reason = ApiInputPolicy.optionalShortText(request?.reason, "reason", 500),
            expectedRevision = request?.expectedRevision, idempotencyKey = request?.idempotencyKey?.let { ApiInputPolicy.requiredIdempotencyKey(it) })
        ResponseEntity.noContent().header("X-Sequo-Commission-Operation-Id", operation.id).build()
    }

    private fun adminOnly(
        authentication: Authentication?,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(RoleGroups.AdminOnly)) {
            ResponseEntity.status(403).build()
        } else try {
            operation(authentication)
        } catch (conflict: MerchantCommissionConflict) {
            ResponseEntity.status(409).body(dev.orestegabo.sequo_api.api.ApiErrorResponse(conflict.code, requireNotNull(conflict.message)))
        }
}
