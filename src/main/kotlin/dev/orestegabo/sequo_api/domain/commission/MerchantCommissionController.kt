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
    )

    data class MerchantCommissionRateResponse(
        val merchantId: String,
        val commissionRateBps: Int,
        val override: MerchantCommissionOverrideSnapshot?,
    )

    @GetMapping("/{merchantId}")
    fun get(
        authentication: Authentication?,
        @PathVariable merchantId: String,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        val safeMerchantId = ApiInputPolicy.requiredIdentifier(merchantId, "merchantId")
        ResponseEntity.ok(
            MerchantCommissionRateResponse(
                merchantId = safeMerchantId,
                commissionRateBps = service.resolveRateBps(safeMerchantId),
                override = service.getOverride(safeMerchantId),
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
                reason = ApiInputPolicy.optionalShortText(request.reason, "reason"),
            )
        )
    }

    @DeleteMapping("/{merchantId}")
    fun clear(
        authentication: Authentication?,
        @PathVariable merchantId: String,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        service.clearOverride(ApiInputPolicy.requiredIdentifier(merchantId, "merchantId"))
        ResponseEntity.noContent().build()
    }

    private fun adminOnly(
        authentication: Authentication?,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(RoleGroups.AdminOnly)) {
            ResponseEntity.status(403).build()
        } else operation(authentication)
}
