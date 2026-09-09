package dev.orestegabo.sequo_api.domain.commission

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

    data class ErrorResponse(val code: String, val message: String)

    @GetMapping("/{merchantId}")
    fun get(
        authentication: Authentication?,
        @PathVariable merchantId: String,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        ResponseEntity.ok(
            MerchantCommissionRateResponse(
                merchantId = merchantId,
                commissionRateBps = service.resolveRateBps(merchantId),
                override = service.getOverride(merchantId),
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
                merchantId = merchantId,
                commissionRateBps = request.commissionRateBps,
                updatedByUserId = authenticated.name,
                reason = request.reason,
            )
        )
    }

    @DeleteMapping("/{merchantId}")
    fun clear(
        authentication: Authentication?,
        @PathVariable merchantId: String,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        service.clearOverride(merchantId)
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
        } else try {
            operation(authentication)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ErrorResponse(
                    code = "invalid_merchant_commission_request",
                    message = e.message ?: "Invalid merchant commission request.",
                )
            )
        }
}
