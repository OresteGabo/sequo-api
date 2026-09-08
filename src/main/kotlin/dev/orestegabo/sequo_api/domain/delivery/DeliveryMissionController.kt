package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasRole
import dev.orestegabo.sequo_api.domain.order.OrderDeliveryLifecycleService
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/delivery/missions")
class DeliveryMissionController(
    private val service: DeliveryMissionService,
    private val pinService: DeliveryPinService,
    private val dispatchService: DeliveryReadinessDispatchService,
    private val orderLifecycle: OrderDeliveryLifecycleService,
) {
    data class AssignCourierRequest(val courierId: String)
    data class ProofRequest(val proofMetadata: String? = null, val deliveryPin: String? = null)
    data class RelayReleaseRequest(val pickupCodeValidated: Boolean, val identityValidated: Boolean, val proofMetadata: String? = null)
    data class ProblemRequest(val reason: String)
    data class CreateDeliveryPinRequest(val rawPin: String, val expiresAt: Instant)
    data class ErrorResponse(val code: String, val message: String)

    @PostMapping
    fun create(
        authentication: Authentication?,
        @RequestBody request: CreateDeliveryMissionCommand,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        ResponseEntity.ok(service.create(request))
    }

    @PostMapping("/dispatch-ready")
    fun dispatchReady(
        authentication: Authentication?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        ResponseEntity.ok(dispatchService.dispatchReadySubOrders(limit))
    }

    @GetMapping
    fun list(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @RequestParam(required = false) courierId: String?,
        @RequestParam(required = false) status: DeliveryMissionRecordStatus?,
    ): ResponseEntity<Any> = missionReadRequired(authentication, userId, courierId) { visibleCourierId ->
        val statuses = status?.let { setOf(it) } ?: DeliveryMissionService.activeOperationalStatuses
        val missions = if (visibleCourierId == null) {
            service.listOperational(statuses)
        } else {
            service.listForCourier(visibleCourierId, statuses)
        }
        ResponseEntity.ok(missions)
    }

    @GetMapping("/{missionId}")
    fun get(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
    ): ResponseEntity<Any> = missionReadRequired(authentication, userId) { actorId ->
        val mission = service.get(missionId) ?: return@missionReadRequired ResponseEntity.notFound().build()
        if (!canReadMission(requireNotNull(authentication), actorId, mission)) {
            return@missionReadRequired ResponseEntity.status(403).build()
        }
        ResponseEntity.ok(mission)
    }

    @PostMapping("/{missionId}/assign")
    fun assign(
        authentication: Authentication?,
        @PathVariable missionId: String,
        @RequestBody request: AssignCourierRequest,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        service.assignCourier(missionId, request.courierId).toResponse()
    }

    @PostMapping("/{missionId}/reassign")
    fun reassign(
        authentication: Authentication?,
        @PathVariable missionId: String,
        @RequestBody request: AssignCourierRequest,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        service.reassignCourier(missionId, request.courierId).toResponse()
    }

    @PostMapping("/{missionId}/offer")
    fun offer(
        authentication: Authentication?,
        @PathVariable missionId: String,
    ): ResponseEntity<Any> = adminOnly(authentication) { authenticated ->
        service.transition(missionId, authenticated.name, DeliveryMissionEvent.OfferToCourier).toResponse()
    }

    @PostMapping("/{missionId}/delivery-pin")
    fun createDeliveryPin(
        authentication: Authentication?,
        @PathVariable missionId: String,
        @RequestBody request: CreateDeliveryPinRequest,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        val mission = service.get(missionId) ?: return@adminOnly ResponseEntity.notFound().build()
        if (mission.destinationType != DeliveryMissionRecordDestination.CUSTOMER_ADDRESS) {
            return@adminOnly ResponseEntity.badRequest().body(
                ErrorResponse(
                    code = "pin_not_allowed_for_destination",
                    message = "Delivery PINs are only created for direct customer-address missions.",
                )
            )
        }
        ResponseEntity.ok(
            pinService.create(
                CreateDeliveryPinCommand(
                    deliveryMissionId = missionId,
                    rawPin = request.rawPin,
                    expiresAt = request.expiresAt,
                )
            )
        )
    }

    @PostMapping("/{missionId}/cancel")
    fun cancel(
        authentication: Authentication?,
        @PathVariable missionId: String,
        @RequestBody request: ProblemRequest,
    ): ResponseEntity<Any> = adminOnly(authentication) { authenticated ->
        service.cancel(missionId, authenticated.name, request.reason).toResponse()
    }

    @PostMapping("/{missionId}/force-problem")
    fun forceProblem(
        authentication: Authentication?,
        @PathVariable missionId: String,
        @RequestBody request: ProblemRequest,
    ): ResponseEntity<Any> = adminOnly(authentication) { authenticated ->
        service.forceProblem(missionId, authenticated.name, request.reason).toResponse()
    }

    @PostMapping("/{missionId}/accept")
    fun accept(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
    ): ResponseEntity<Any> = roleActorRequired(authentication, userId, setOf(RoleCode.COURIER)) {
        service.transition(missionId, it, DeliveryMissionEvent.CourierAccepts).toResponse()
    }

    @PostMapping("/{missionId}/pickup")
    fun pickup(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: ProofRequest,
    ): ResponseEntity<Any> = roleActorRequired(authentication, userId, setOf(RoleCode.COURIER)) {
        service.transition(missionId, it, DeliveryMissionEvent.CourierPicksUpFromSeller, proof = request.proofMetadata).toResponse()
    }

    @PostMapping("/{missionId}/deliver")
    fun deliver(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: ProofRequest,
    ): ResponseEntity<Any> = roleActorRequired(authentication, userId, setOf(RoleCode.COURIER)) {
        if (!request.deliveryPin.isNullOrBlank()) {
            when (val validation = service.validateDirectDeliveryAttempt(missionId, it)) {
                is DeliveryMissionServiceResult.Success -> Unit
                is DeliveryMissionServiceResult.Rejected -> return@roleActorRequired validation.toResponse()
            }
        }
        val pinValidated = request.deliveryPin?.takeIf(String::isNotBlank)?.let { rawPin ->
            when (val result = pinService.verify(missionId, rawPin)) {
                is DeliveryPinResult.Accepted -> true
                is DeliveryPinResult.Rejected -> return@roleActorRequired result.toResponse()
            }
        } ?: false
        val result = service.transition(
            missionId,
            it,
            DeliveryMissionEvent.CourierDeliversToCustomer,
            proof = request.proofMetadata,
            deliveryPinValidated = pinValidated,
        )
        result.markOrderDelivered(actorUserId = it).toResponse()
    }

    @PostMapping("/{missionId}/relay-deposit")
    fun relayDeposit(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: ProofRequest,
    ): ResponseEntity<Any> = roleActorRequired(authentication, userId, setOf(RoleCode.COURIER)) {
        service.transition(missionId, it, DeliveryMissionEvent.CourierDepositsAtRelay, proof = request.proofMetadata).toResponse()
    }

    @PostMapping("/{missionId}/relay-release")
    fun relayRelease(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: RelayReleaseRequest,
    ): ResponseEntity<Any> = roleActorRequired(authentication, userId, RoleGroups.RelayOperators) {
        val result = service.transition(
            missionId = missionId,
            actorId = it,
            event = DeliveryMissionEvent.RelayReleasesToCustomer,
            proof = request.proofMetadata,
            relayPickupValidated = request.pickupCodeValidated,
            identityValidated = request.identityValidated,
        )
        result.markOrderDelivered(actorUserId = it).toResponse()
    }

    @PostMapping("/{missionId}/problem")
    fun reportProblem(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @PathVariable missionId: String,
        @RequestBody request: ProblemRequest,
    ): ResponseEntity<Any> = roleActorRequired(authentication, userId, RoleGroups.DeliveryProblemReporters) {
        service.transition(missionId, it, DeliveryMissionEvent.ReportProblem, problemReason = request.reason).toResponse()
    }

    private fun adminOnly(authentication: Authentication?, operation: (Authentication) -> ResponseEntity<Any>): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (!authentication.hasAnyRole(RoleGroups.AdminOnly)) ResponseEntity.status(403).build()
        else try { operation(authentication) } catch (e: IllegalArgumentException) { badRequest(e) }

    private fun roleActorRequired(
        authentication: Authentication?,
        userId: String?,
        roles: Set<RoleCode>,
        operation: (String) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (!authentication.hasAnyRole(roles)) ResponseEntity.status(403).build()
        else try { operation(userId ?: authentication.name) } catch (e: IllegalArgumentException) { badRequest(e) }

    private fun missionReadRequired(
        authentication: Authentication?,
        userId: String?,
        requestedCourierId: String? = null,
        operation: (String?) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> {
        if (authentication == null) return ResponseEntity.status(401).build()
        if (!authentication.hasAnyRole(RoleGroups.DeliveryMissionReaders)) return ResponseEntity.status(403).build()
        return try {
            val actorId = userId ?: authentication.name
            if (authentication.hasRole(RoleCode.COURIER) && !authentication.hasAnyRole(RoleGroups.AdminOperations)) {
                val visibleCourierId = requestedCourierId ?: actorId
                if (visibleCourierId != actorId) {
                    ResponseEntity.status(403).build()
                } else {
                    operation(visibleCourierId)
                }
            } else {
                operation(requestedCourierId)
            }
        } catch (e: IllegalArgumentException) {
            badRequest(e)
        }
    }

    private fun canReadMission(
        authentication: Authentication,
        actorId: String?,
        mission: DeliveryMissionSnapshot,
    ): Boolean =
        authentication.hasAnyRole(RoleGroups.AdminOperations) ||
            (authentication.hasRole(RoleCode.COURIER) && mission.courierId == actorId)

    private fun badRequest(error: IllegalArgumentException): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErrorResponse("invalid_delivery_request", error.message ?: "Invalid delivery request."))

    private fun DeliveryMissionServiceResult.markOrderDelivered(actorUserId: String): DeliveryMissionServiceResult {
        if (this is DeliveryMissionServiceResult.Success) {
            orderLifecycle.markDeliveredFromMission(mission, actorUserId)
        }
        return this
    }
}

private fun DeliveryMissionServiceResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is DeliveryMissionServiceResult.Success -> ResponseEntity.ok(mission)
        is DeliveryMissionServiceResult.Rejected -> ResponseEntity.badRequest().body(DeliveryMissionController.ErrorResponse(code, message))
    }

private fun DeliveryPinResult.Rejected.toResponse(): ResponseEntity<Any> =
    ResponseEntity.badRequest().body(DeliveryMissionController.ErrorResponse(code, message))
