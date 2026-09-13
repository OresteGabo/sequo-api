package dev.orestegabo.sequo_api.domain.hub

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasRole
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/hub")
class HubMobileController(
    private val service: HubMobileService,
) {
    data class ScanResolveRequest(
        val hubId: String,
        val credential: String,
        val credentialType: HubScanCredentialType = HubScanCredentialType.AUTO,
        val idempotencyKey: String? = null,
    )

    data class LockerAvailabilityRequest(
        val relayPointId: String,
        val status: HubLockerStatus,
        val reason: HubLockerAvailabilityReason? = null,
        val expectedAvailableAt: Instant? = null,
        val idempotencyKey: String? = null,
    )

    data class WeeklyOpeningHourRequest(
        val dayOfWeek: DayOfWeek,
        val isOpen: Boolean,
        val opensAt: LocalTime? = null,
        val closesAt: LocalTime? = null,
    )

    data class OpeningHourExceptionRequest(
        val date: LocalDate,
        val isClosed: Boolean,
        val opensAt: LocalTime? = null,
        val closesAt: LocalTime? = null,
        val reason: String? = null,
        val effectiveUntil: LocalDate? = null,
    )

    data class SaveOpeningHoursRequest(
        val relayPointId: String,
        val timezone: String,
        val weeklyHours: List<WeeklyOpeningHourRequest>,
        val exceptions: List<OpeningHourExceptionRequest> = emptyList(),
        val idempotencyKey: String? = null,
    )

    data class ApplyControlStateRequest(
        val relayPointId: String,
        val target: HubControlTarget,
        val status: HubControlStatus,
        val reasonCode: HubControlReasonCode,
        val staffMessage: String,
        val customerMessage: String? = null,
        val effectiveUntil: Instant? = null,
        val actorType: HubControlActorType,
        val source: String? = null,
        val incidentReferenceId: String? = null,
        val idempotencyKey: String,
    )

    data class ErrorResponse(val code: String, val message: String)

    @PostMapping("/scan/resolve")
    fun resolveScan(
        authentication: Authentication?,
        @RequestBody request: ScanResolveRequest,
    ): ResponseEntity<Any> =
        hubAccessRequired(authentication, request.hubId) {
            ResponseEntity.ok(
                service.resolveScan(
                    HubScanResolveCommand(
                        hubId = request.hubId,
                        credential = request.credential,
                        credentialType = request.credentialType,
                        idempotencyKey = request.idempotencyKey,
                    )
                )
            )
        }

    @GetMapping("/summary")
    fun summary(
        authentication: Authentication?,
        @RequestParam hubId: String,
    ): ResponseEntity<Any> =
        hubAccessRequired(authentication, hubId) {
            ResponseEntity.ok(service.summary(hubId))
        }

    @PostMapping("/lockers/{lockerId}/availability")
    fun updateLockerAvailability(
        authentication: Authentication?,
        @PathVariable lockerId: String,
        @RequestBody request: LockerAvailabilityRequest,
    ): ResponseEntity<Any> =
        hubAccessRequired(authentication, request.relayPointId) { authenticated ->
            ResponseEntity.ok(
                service.updateLockerAvailability(
                    LockerAvailabilityCommand(
                        lockerId = lockerId,
                        relayPointId = request.relayPointId,
                        status = request.status,
                        reason = request.reason,
                        expectedAvailableAt = request.expectedAvailableAt,
                        updatedByUserId = authenticated.name,
                    )
                )
            )
        }

    @GetMapping("/opening-hours")
    fun openingHours(
        authentication: Authentication?,
        @RequestParam relayPointId: String,
    ): ResponseEntity<Any> =
        hubAccessRequired(authentication, relayPointId) {
            ResponseEntity.ok(service.getOpeningHours(relayPointId))
        }

    @PutMapping("/opening-hours")
    fun saveOpeningHours(
        authentication: Authentication?,
        @RequestBody request: SaveOpeningHoursRequest,
    ): ResponseEntity<Any> =
        hubAccessRequired(authentication, request.relayPointId) {
            ResponseEntity.ok(
                service.saveOpeningHours(
                    SaveHubOpeningHoursCommand(
                        relayPointId = request.relayPointId,
                        timezone = request.timezone,
                        weeklyHours = request.weeklyHours.map {
                            WeeklyOpeningHourCommand(
                                dayOfWeek = it.dayOfWeek,
                                isOpen = it.isOpen,
                                opensAt = it.opensAt,
                                closesAt = it.closesAt,
                            )
                        },
                        exceptions = request.exceptions.map {
                            OpeningHourExceptionCommand(
                                date = it.date,
                                isClosed = it.isClosed,
                                opensAt = it.opensAt,
                                closesAt = it.closesAt,
                                reason = it.reason,
                                effectiveUntil = it.effectiveUntil,
                            )
                        },
                        idempotencyKey = request.idempotencyKey,
                    )
                )
            )
        }

    @GetMapping("/control-state")
    fun controlState(
        authentication: Authentication?,
        @RequestParam relayPointId: String,
    ): ResponseEntity<Any> =
        hubControlReadAccessRequired(authentication, relayPointId) {
            ResponseEntity.ok(service.getControlState(relayPointId))
        }

    @PostMapping("/control-state")
    fun applyControlState(
        authentication: Authentication?,
        @RequestBody request: ApplyControlStateRequest,
    ): ResponseEntity<Any> =
        controlMutationRequired(authentication, request.actorType) { authenticated ->
            try {
                ResponseEntity.ok(
                    service.applyControlDecision(
                        HubControlDecisionCommand(
                            relayPointId = request.relayPointId,
                            target = request.target,
                            status = request.status,
                            reasonCode = request.reasonCode,
                            staffMessage = request.staffMessage,
                            customerMessage = request.customerMessage,
                            effectiveUntil = request.effectiveUntil,
                            actorType = request.actorType,
                            actorId = authenticated.name,
                            source = request.source ?: "hub-control-api",
                            incidentReferenceId = request.incidentReferenceId,
                            idempotencyKey = request.idempotencyKey,
                        )
                    )
                )
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(
                    ErrorResponse("invalid_hub_control_request", e.message ?: "Invalid hub control request.")
                )
            }
        }

    @GetMapping("/control-state/history")
    fun controlHistory(
        authentication: Authentication?,
        @RequestParam relayPointId: String,
        @RequestParam(required = false) target: HubControlTarget?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<Any> =
        hubControlReadAccessRequired(authentication, relayPointId) {
            ResponseEntity.ok(
                service.controlHistory(
                    relayPointId = relayPointId,
                    target = target,
                    limit = limit ?: 50,
                )
            )
        }

    private fun hubControlReadAccessRequired(
        authentication: Authentication?,
        hubId: String,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(RoleGroups.RelayOperators) && !authentication.hasAnyRole(RoleGroups.AdminOperations)) {
            ResponseEntity.status(403).build()
        } else if (authentication.hasRole(RoleCode.RELAY_PARTNER) && !authentication.hasAnyRole(RoleGroups.AdminOperations) && authentication.name != hubId) {
            ResponseEntity.status(403).build()
        } else {
            try {
                operation(authentication)
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(
                    ErrorResponse("invalid_hub_request", e.message ?: "Invalid hub request.")
                )
            }
        }

    private fun hubAccessRequired(
        authentication: Authentication?,
        hubId: String,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(RoleGroups.RelayOperators)) {
            ResponseEntity.status(403).build()
        } else if (authentication.hasRole(RoleCode.RELAY_PARTNER) && !authentication.hasAnyRole(RoleGroups.AdminOperations) && authentication.name != hubId) {
            ResponseEntity.status(403).build()
        } else {
            try {
                operation(authentication)
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(
                    ErrorResponse("invalid_hub_request", e.message ?: "Invalid hub request.")
                )
            }
        }

    private fun controlMutationRequired(
        authentication: Authentication?,
        actorType: HubControlActorType,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(RoleGroups.AdminOperations)) {
            ResponseEntity.status(403).build()
        } else if (actorType != HubControlActorType.SEQUO_OPERATOR && !authentication.hasAnyRole(RoleGroups.AdminOnly)) {
            ResponseEntity.status(403).build()
        } else {
            operation(authentication)
        }
}
