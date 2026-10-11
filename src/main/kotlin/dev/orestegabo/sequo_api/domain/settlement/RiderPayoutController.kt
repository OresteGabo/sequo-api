package dev.orestegabo.sequo_api.domain.settlement

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.hasRole
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/rider/payouts")
class RiderPayoutController(
    private val service: SettlementPersistenceService,
) {
    @GetMapping("/summary")
    fun summary(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
    ): ResponseEntity<Any> = riderOnly(authentication, userId) { riderUserId ->
        ResponseEntity.ok(service.riderPayoutSummary(riderUserId))
    }

    @GetMapping("/history")
    fun history(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
    ): ResponseEntity<Any> = riderOnly(authentication, userId) { riderUserId ->
        ResponseEntity.ok(service.riderPayoutHistory(riderUserId))
    }

    private fun riderOnly(
        authentication: Authentication?,
        userId: String?,
        operation: (String) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasRole(RoleCode.COURIER)) {
            ResponseEntity.status(403).build()
        } else {
            operation(userId ?: authentication.name)
        }
}
