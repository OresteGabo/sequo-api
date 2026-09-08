package dev.orestegabo.sequo_api.domain.delivery

import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/delivery/missions")
class DeliveryMissionController(
    private val service: DeliveryMissionService,
) {
    data class AssignCourierRequest(val courierId: String)
    data class ProofRequest(val proofMetadata: String? = null)
    data class RelayReleaseRequest(val pickupCodeValidated: Boolean, val identityValidated: Boolean, val proofMetadata: String? = null)
    data class ProblemRequest(val reason: String)
    data class ErrorResponse(val code: String, val message: String)

    @PostMapping
    fun create(
        authentication: Authentication?,
        @RequestBody request: CreateDeliveryMissionCommand,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        ResponseEntity.ok(service.create(request))
    }

    @PostMapping("/{missionId}/assign")
    fun assign(
        authentication: Authentication?,
        @PathVariable missionId: String,
        @RequestBody request: AssignCourierRequest,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        service.assignCourier(missionId, request.courierId).toResponse()
    }

    @PostMapping("/{missionId}/offer")
    fun offer(
        authentication: Authentication?,
        @PathVariable missionId: String,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        service.transition(missionId, requireNotNull(authentication).name, DeliveryMissionEvent.OfferToCourier).toResponse()
    }

    @PostMapping("/{missionId}/accept")
    fun accept(
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
    ): ResponseEntity<Any> = actorRequired(userId) {
        service.transition(missionId, it, DeliveryMissionEvent.CourierAccepts).toResponse()
    }

    @PostMapping("/{missionId}/pickup")
    fun pickup(
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: ProofRequest,
    ): ResponseEntity<Any> = actorRequired(userId) {
        service.transition(missionId, it, DeliveryMissionEvent.CourierPicksUpFromSeller, proof = request.proofMetadata).toResponse()
    }

    @PostMapping("/{missionId}/deliver")
    fun deliver(
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: ProofRequest,
    ): ResponseEntity<Any> = actorRequired(userId) {
        service.transition(missionId, it, DeliveryMissionEvent.CourierDeliversToCustomer, proof = request.proofMetadata).toResponse()
    }

    @PostMapping("/{missionId}/relay-deposit")
    fun relayDeposit(
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: ProofRequest,
    ): ResponseEntity<Any> = actorRequired(userId) {
        service.transition(missionId, it, DeliveryMissionEvent.CourierDepositsAtRelay, proof = request.proofMetadata).toResponse()
    }

    @PostMapping("/{missionId}/relay-release")
    fun relayRelease(
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: RelayReleaseRequest,
    ): ResponseEntity<Any> = actorRequired(userId) {
        service.transition(
            missionId = missionId,
            actorId = it,
            event = DeliveryMissionEvent.RelayReleasesToCustomer,
            proof = request.proofMetadata,
            relayPickupValidated = request.pickupCodeValidated,
            identityValidated = request.identityValidated,
        ).toResponse()
    }

    @PostMapping("/{missionId}/problem")
    fun reportProblem(
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: ProblemRequest,
    ): ResponseEntity<Any> = actorRequired(userId) {
        service.transition(missionId, it, DeliveryMissionEvent.ReportProblem, problemReason = request.reason).toResponse()
    }

    private fun adminOnly(authentication: Authentication?, operation: () -> ResponseEntity<Any>): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (authentication.authorities.none { it.authority in setOf("ROLE_ADMIN", "ROLE_SUPER_ADMIN") }) ResponseEntity.status(403).build()
        else try { operation() } catch (e: IllegalArgumentException) { badRequest(e) }

    private fun actorRequired(userId: String?, operation: (String) -> ResponseEntity<Any>): ResponseEntity<Any> =
        if (userId == null) ResponseEntity.status(401).build()
        else try { operation(userId) } catch (e: IllegalArgumentException) { badRequest(e) }

    private fun badRequest(error: IllegalArgumentException): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErrorResponse("invalid_delivery_request", error.message ?: "Invalid delivery request."))
}

private fun DeliveryMissionServiceResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is DeliveryMissionServiceResult.Success -> ResponseEntity.ok(mission)
        is DeliveryMissionServiceResult.Rejected -> ResponseEntity.badRequest().body(DeliveryMissionController.ErrorResponse(code, message))
    }
