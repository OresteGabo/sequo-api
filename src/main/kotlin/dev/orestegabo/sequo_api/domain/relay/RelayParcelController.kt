package dev.orestegabo.sequo_api.domain.relay

import dev.orestegabo.sequo_api.api.ApiInputPolicy
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
    private val access: RelayOperatorAccess,
    private val operations: RelayPickupOperations,
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
    data class ReturnToSellerRequest(val eventId: String, val idempotencyKey: String, val metadata: String, val returnedAt: Instant? = null)
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
        val history: List<CustodyHistoryEntry> = emptyList(),
        val custody: RelayDeadlines? = null,
    )
    data class CustodyHistoryEntry(val type: RelayCustodyEventType, val createdAt: Instant, val message: String? = null,
        val id: String? = null, val actorUserId: String? = null)
    data class PickupCredentialResponse(val id: String, val parcelId: String, val identityCheckRequired: Boolean, val expiresAt: Instant, val usedAt: Instant?, val attemptCount: Int)
    data class OperationResponse(val parcel: ParcelResponse, val pickupCode: PickupCredentialResponse?, val eventId: String?)

    @PostMapping
    fun create(authentication: Authentication?, @RequestBody command: RelayParcelCreateCommand): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.RelayParcelCreators) { actor ->
            // Linked delivery custody must be created atomically by the mission deposit action.
            require(command.deliveryMissionId == null) { "Use the mission relay-deposit action with its persisted destination and storage slot." }
            if (!access.permits(actor, command.relayPointId)) return@roleRequired ResponseEntity.status(403).build()
            // Raw client locker lists, timestamps and source references are not evidence of custody.
            // Supported legacy records remain readable; new custody uses an authorized mission.
            ResponseEntity.badRequest().body(dev.orestegabo.sequo_api.api.ApiErrorResponse(
                "relay_guided_deposit_required", "Use the assigned rider relay-deposit action or the SEQUO physical receipt workflow after recorded pickup."))
        }

    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam relayPointId: String,
        @RequestParam(required = false) status: RelayParcelStatus?,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.RelayOperators) { actor ->
        if (!access.permits(actor, relayPointId)) return@roleRequired ResponseEntity.status(403).build()
        ResponseEntity.ok(
            service.listParcels(ApiInputPolicy.requiredIdentifier(relayPointId, "relayPointId"), status)
                .map { toResponse(it) }
        )
    }

    @GetMapping("/{parcelId}")
    fun get(authentication: Authentication?, @PathVariable parcelId: String, @RequestParam(required = false) relayPointId: String?): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.RelayOperators) { authenticated ->
            val safeParcelId = ApiInputPolicy.requiredIdentifier(parcelId, "parcelId")
            val safeRelayPointId = ApiInputPolicy.optionalIdentifier(relayPointId, "relayPointId")
            val parcel = service.getParcel(safeParcelId)
                ?: return@roleRequired ResponseEntity.notFound().build()
            if (!access.permits(authenticated, parcel.relayPointId) || (safeRelayPointId != null && parcel.relayPointId != safeRelayPointId)) {
                return@roleRequired ResponseEntity.status(403).build()
            }
            ResponseEntity.ok(toResponse(parcel))
        }

    @PostMapping("/{parcelId}/pickup-code")
    fun createPickupCode(
        authentication: Authentication?,
        @PathVariable parcelId: String,
        @RequestBody request: PickupCodeRequest,
    ): ResponseEntity<Any> = parcelRequired(authentication, parcelId) {
        require(request.identityCheckRequired) { "Identity validation is required for relay pickup." }
        service.createPickupCode(
            parcelId = ApiInputPolicy.requiredIdentifier(parcelId, "parcelId"),
            codeId = ApiInputPolicy.requiredIdentifier(request.codeId, "codeId"),
            rawNumericCode = ApiInputPolicy.requiredShortText(request.rawNumericCode, "rawNumericCode", 32),
            rawQrNonce = ApiInputPolicy.optionalShortText(request.rawQrNonce, "rawQrNonce", 256),
            identityCheckRequired = request.identityCheckRequired,
            expiresAt = request.expiresAt,
        ).toResponse()
    }

    @PostMapping("/{parcelId}/release")
    fun release(
        authentication: Authentication?,
        @PathVariable parcelId: String,
        @RequestBody request: ReleaseRequest,
    ): ResponseEntity<Any> = parcelRequired(authentication, parcelId) { authenticated ->
        if (!access.permits(authenticated, request.relayPointId) || service.getParcel(parcelId)?.relayPointId != request.relayPointId) return@parcelRequired ResponseEntity.status(403).build()
        operations.release(
            parcelId = ApiInputPolicy.requiredIdentifier(parcelId, "parcelId"),
            relayPointId = ApiInputPolicy.requiredIdentifier(request.relayPointId, "relayPointId"),
            actorUserId = authenticated.name,
            rawNumericCode = ApiInputPolicy.optionalShortText(request.rawNumericCode, "rawNumericCode", 32),
            rawQrNonce = ApiInputPolicy.optionalShortText(request.rawQrNonce, "rawQrNonce", 256),
            identityDocumentMatched = request.identityDocumentMatched,
            eventId = ApiInputPolicy.requiredIdentifier(request.eventId, "eventId"),
            idempotencyKey = ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey),
        ).toResponse()
    }

    @PostMapping("/{parcelId}/problem")
    fun reportProblem(
        authentication: Authentication?,
        @PathVariable parcelId: String,
        @RequestBody request: ProblemRequest,
    ): ResponseEntity<Any> = parcelRequired(authentication, parcelId) { authenticated ->
        service.reportProblem(
            ApiInputPolicy.requiredIdentifier(parcelId, "parcelId"),
            authenticated.name,
            ApiInputPolicy.requiredIdentifier(request.eventId, "eventId"),
            ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey),
            ApiInputPolicy.requiredLongText(request.metadata, "metadata"),
        ).toResponse()
    }

    @PostMapping("/{parcelId}/return-to-seller")
    fun returnToSeller(
        authentication: Authentication?,
        @PathVariable parcelId: String,
        @RequestBody request: ReturnToSellerRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOperations) { authenticated ->
        val returnedAt = request.returnedAt ?: Instant.now()
        require(!returnedAt.isAfter(Instant.now())) { "returnedAt cannot be in the future." }
        operations.returnToSeller(
            parcelId = ApiInputPolicy.requiredIdentifier(parcelId, "parcelId"),
            actorUserId = authenticated.name,
            eventId = ApiInputPolicy.requiredIdentifier(request.eventId, "eventId"),
            idempotencyKey = ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey),
            metadata = ApiInputPolicy.requiredLongText(request.metadata, "metadata"),
            returnedAt = returnedAt,
        ).toResponse()
    }

    @PostMapping("/storage-fees/assess")
    fun assessStorageFees(
        authentication: Authentication?,
        @RequestBody request: StorageFeeAssessmentRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOperations) {
        require(!request.evaluatedAt.isAfter(Instant.now())) { "evaluatedAt cannot be in the future." }
        require(request.dailyFeeCfa in 0..100_000) { "dailyFeeCfa must be between 0 and 100000." }
        ResponseEntity.ok(service.assessStorageFees(ApiInputPolicy.requiredIdentifier(request.relayPointId, "relayPointId"), request.dailyFeeCfa, request.evaluatedAt))
    }

    @GetMapping("/storage-fees")
    fun listStorageFees(
        authentication: Authentication?,
        @RequestParam relayPointId: String,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOperations) {
        ResponseEntity.ok(service.listStorageFeeAssessments(ApiInputPolicy.requiredIdentifier(relayPointId, "relayPointId")))
    }

    data class AssignedRelay(val id: String, val name: String, val canReturnToSeller: Boolean)

    @GetMapping("/sites")
    fun sites(authentication: Authentication?): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.RelayOperators) { actor ->
            ResponseEntity.ok(access.assignedPoints(actor).map { AssignedRelay(it.id, it.name, actor.hasAnyRole(RoleGroups.AdminOperations)) })
        }

    @GetMapping("/overdue")
    fun overdue(authentication: Authentication?, @RequestParam relayPointId: String): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.RelayOperators) { actor ->
            if (!access.permits(actor, relayPointId)) return@roleRequired ResponseEntity.status(403).build()
            ResponseEntity.ok(service.listParcels(relayPointId).filter { parcel -> parcel.deadlines()?.let { it.storageFeeEligible || it.reviewDue } == true }.map(::toResponse))
        }

    @PostMapping("/deadlines/evaluate")
    fun evaluate(authentication: Authentication?, @RequestBody request: Map<String, String>): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.RelayOperators) { actor ->
            val site = ApiInputPolicy.requiredIdentifier(request["relayPointId"].orEmpty(), "relayPointId")
            if (!access.permits(actor, site)) return@roleRequired ResponseEntity.status(403).build()
            ResponseEntity.ok(service.evaluateDelayed(site, Instant.now()).map(::toResponse))
        }

    @PostMapping("/{parcelId}/review")
    fun review(authentication: Authentication?, @PathVariable parcelId: String, @RequestBody request: ProblemRequest): ResponseEntity<Any> =
        parcelRequired(authentication, parcelId) { actor ->
            operations.review(parcelId, actor.name, ApiInputPolicy.requiredIdentifier(request.eventId, "eventId"),
                ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey), ApiInputPolicy.requiredLongText(request.metadata, "metadata")).toResponse()
        }

    private fun parcelRequired(authentication: Authentication?, parcelId: String, operation: (Authentication) -> ResponseEntity<Any>): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.RelayOperators) { actor ->
            val parcel = service.getParcel(ApiInputPolicy.requiredIdentifier(parcelId, "parcelId"))
                ?: return@roleRequired ResponseEntity.notFound().build()
            if (!access.permits(actor, parcel.relayPointId)) ResponseEntity.status(403).build()
            else operation(actor)
        }

    private fun roleRequired(authentication: Authentication?, roles: Set<RoleCode>, operation: (Authentication) -> ResponseEntity<Any>): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (!authentication.hasAnyRole(roles)) ResponseEntity.status(403).build()
        else operation(authentication)

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
    custody = parcel.deadlines(),
    history = parcel.custodyEvents.map { RelayParcelController.CustodyHistoryEntry(it.type, it.createdAt,
        it.metadata.takeIf { _ -> it.type in setOf(RelayCustodyEventType.Review, RelayCustodyEventType.ReturnDropoff, RelayCustodyEventType.Delay) },
        it.id, it.actorUserId) },
)
