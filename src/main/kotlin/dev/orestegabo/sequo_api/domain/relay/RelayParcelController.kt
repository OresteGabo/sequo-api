package dev.orestegabo.sequo_api.domain.relay

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasRole
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
    data class ReleaseRequest(
        val relayPointId: String,
        val rawNumericCode: String? = null,
        val rawQrNonce: String? = null,
        val identityDocumentMatched: Boolean,
        val eventId: String,
        val idempotencyKey: String,
    )
    data class ProblemRequest(val eventId: String, val idempotencyKey: String, val metadata: String)
    data class StorageFeeAssessmentRequest(val relayPointId: String, val dailyFeeCfa: Int, val evaluatedAt: Instant = Instant.now())
    data class ParcelResponse(
        val id: String,
        val relayPointId: String,
        val lockerId: String?,
        val orderId: String?,
        val deliveryMissionId: String?,
        val returnId: String?,
        val category: RelayParcelCategory,
        val status: RelayParcelStatus,
        val depositedAt: Instant?,
        val pickedUpAt: Instant?,
        val collectedAt: Instant?,
        val createdAt: Instant,
        val updatedAt: Instant,
    )
    data class PickupCredentialResponse(val id: String, val parcelId: String, val identityCheckRequired: Boolean, val expiresAt: Instant, val usedAt: Instant?, val attemptCount: Int)
    data class OperationResponse(val parcel: ParcelResponse, val pickupCode: PickupCredentialResponse?, val eventId: String?)

    @PostMapping
    fun create(authentication: Authentication?, @RequestBody command: RelayParcelCreateCommand): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.RelayParcelCreators) {
            service.createParcel(command).toResponse()
        }

    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam relayPointId: String,
        @RequestParam(required = false) status: RelayParcelStatus?,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.RelayOperators) {
        ResponseEntity.ok(service.listParcels(relayPointId, status).map { toResponse(it) })
    }

    @GetMapping("/{parcelId}")
    fun get(authentication: Authentication?, @PathVariable parcelId: String, @RequestParam(required = false) relayPointId: String?): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.RelayOperators) { authenticated ->
            val parcel = service.getParcel(parcelId)
                ?: return@roleRequired ResponseEntity.notFound().build()
            if (authenticated.hasRole(RoleCode.RELAY_PARTNER) && parcel.relayPointId != relayPointId) {
                return@roleRequired ResponseEntity.status(403).build()
            }
            ResponseEntity.ok(toResponse(parcel))
        }

    @PostMapping("/{parcelId}/pickup-code")
    fun createPickupCode(
        authentication: Authentication?,
        @PathVariable parcelId: String,
        @RequestBody request: PickupCodeRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.RelayOperators) {
        service.createPickupCode(
            parcelId = parcelId,
            codeId = request.codeId,
            rawNumericCode = request.rawNumericCode,
            rawQrNonce = request.rawQrNonce,
            identityCheckRequired = request.identityCheckRequired,
            expiresAt = request.expiresAt,
        ).toResponse()
    }

    @PostMapping("/{parcelId}/release")
    fun release(
        authentication: Authentication?,
        @PathVariable parcelId: String,
        @RequestBody request: ReleaseRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.RelayOperators) { authenticated ->
        service.verifyPickup(
            parcelId = parcelId,
            relayPointId = request.relayPointId,
            actorUserId = authenticated.name,
            rawNumericCode = request.rawNumericCode,
            rawQrNonce = request.rawQrNonce,
            identityDocumentMatched = request.identityDocumentMatched,
            eventId = request.eventId,
            idempotencyKey = request.idempotencyKey,
        ).toResponse()
    }

    @PostMapping("/{parcelId}/problem")
    fun reportProblem(
        authentication: Authentication?,
        @PathVariable parcelId: String,
        @RequestBody request: ProblemRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.RelayOperators) { authenticated ->
        service.reportProblem(parcelId, authenticated.name, request.eventId, request.idempotencyKey, request.metadata).toResponse()
    }

    @PostMapping("/storage-fees/assess")
    fun assessStorageFees(
        authentication: Authentication?,
        @RequestBody request: StorageFeeAssessmentRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOperations) {
        ResponseEntity.ok(service.assessStorageFees(request.relayPointId, request.dailyFeeCfa, request.evaluatedAt))
    }

    @GetMapping("/storage-fees")
    fun listStorageFees(
        authentication: Authentication?,
        @RequestParam relayPointId: String,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOperations) {
        ResponseEntity.ok(service.listStorageFeeAssessments(relayPointId))
    }

    private fun roleRequired(authentication: Authentication?, roles: Set<RoleCode>, operation: (Authentication) -> ResponseEntity<Any>): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (!authentication.hasAnyRole(roles)) ResponseEntity.status(403).build()
        else try { operation(authentication) } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ErrorResponse("invalid_relay_request", e.message ?: "Invalid relay request."))
        }

    data class ErrorResponse(val code: String, val message: String)
}

private fun RelayParcelServiceResult.toResponse(): ResponseEntity<Any> = when (this) {
    is RelayParcelServiceResult.Accepted -> ResponseEntity.ok(
        RelayParcelController.OperationResponse(
            parcel = toResponse(value.parcel),
            pickupCode = value.pickupCode?.let { RelayParcelController.PickupCredentialResponse(it.id, it.relayParcelId, it.identityCheckRequired, it.expiresAt, it.usedAt, it.attemptCount) },
            eventId = value.event?.id,
        )
    )
    is RelayParcelServiceResult.Rejected -> ResponseEntity.badRequest().body(RelayParcelController.ErrorResponse(rejection.code, rejection.message))
}

private fun toResponse(parcel: RelayParcel) = RelayParcelController.ParcelResponse(
    id = parcel.id,
    relayPointId = parcel.relayPointId,
    lockerId = parcel.lockerId,
    orderId = parcel.orderId,
    deliveryMissionId = parcel.deliveryMissionId,
    returnId = parcel.returnId,
    category = parcel.category,
    status = parcel.status,
    depositedAt = parcel.depositedAt,
    pickedUpAt = parcel.pickedUpAt,
    collectedAt = parcel.collectedAt,
    createdAt = parcel.createdAt,
    updatedAt = parcel.updatedAt,
)
