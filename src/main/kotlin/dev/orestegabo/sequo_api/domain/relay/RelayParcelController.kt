package dev.orestegabo.sequo_api.domain.relay

import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/relay/parcels")
class RelayParcelController(
    private val service: RelayParcelApplicationService,
) {
    data class PickupCodeRequest(
        val codeId: String,
        val rawNumericCode: String,
        val rawQrNonce: String? = null,
        val identityCheckRequired: Boolean = true,
        val expiresAt: Instant,
    )

    @PostMapping
    fun create(authentication: Authentication?, @RequestBody command: RelayParcelCreateCommand): ResponseEntity<Any> =
        roleRequired(authentication, setOf("ROLE_RELAY_PARTNER", "ROLE_COURIER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN")) {
            service.createParcel(command).toResponse()
        }

    @PostMapping("/{parcelId}/pickup-code")
    fun createPickupCode(
        authentication: Authentication?,
        @PathVariable parcelId: String,
        @RequestBody request: PickupCodeRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, setOf("ROLE_RELAY_PARTNER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN")) {
        service.createPickupCode(
            parcelId = parcelId,
            codeId = request.codeId,
            rawNumericCode = request.rawNumericCode,
            rawQrNonce = request.rawQrNonce,
            identityCheckRequired = request.identityCheckRequired,
            expiresAt = request.expiresAt,
        ).toResponse()
    }

    private fun roleRequired(authentication: Authentication?, roles: Set<String>, operation: () -> ResponseEntity<Any>): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (authentication.authorities.none { it.authority in roles }) ResponseEntity.status(403).build()
        else try { operation() } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ErrorResponse("invalid_relay_request", e.message ?: "Invalid relay request."))
        }

    data class ErrorResponse(val code: String, val message: String)
}

private fun RelayParcelServiceResult.toResponse(): ResponseEntity<Any> = when (this) {
    is RelayParcelServiceResult.Accepted -> ResponseEntity.ok(value)
    is RelayParcelServiceResult.Rejected -> ResponseEntity.badRequest().body(RelayParcelController.ErrorResponse(rejection.code, rejection.message))
}
