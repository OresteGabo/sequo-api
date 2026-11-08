package dev.orestegabo.sequo_api.domain.returns

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasRole
import dev.orestegabo.sequo_api.domain.auth.merchantScopeIds
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.http.CacheControl
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
@RequestMapping("/api/returns")
class ReturnController(
    private val service: ReturnPersistenceService,
    private val relayAccess: dev.orestegabo.sequo_api.domain.relay.RelayOperatorAccess,
) {
    data class CreateReturnRequest(
        val returnId: String,
        val orderId: String,
        val customerId: String? = null,
        val reason: String,
        val requestedRefundCfa: Int? = null,
        val rawReturnPin: String? = null,
        val requestedAt: Instant? = null,
        val productReturnable: Boolean? = null,
        val merchantAllowsReturn: Boolean? = null,
        val adminOverride: Boolean? = null,
    )
    data class RelayDropoffRequest(
        val relayPointId: String,
        val rawReturnPin: String,
        val droppedAt: Instant? = null,
        val idempotencyKey: String? = null,
        val metadata: String? = null,
    )
    data class PhysicalReceiptRequest(
        val receivedAt: Instant? = null,
        val conditionAssessment: String,
        val responsibility: RefundResponsibility,
        val idempotencyKey: String,
    )
    data class CollectionRequest(val idempotencyKey: String, val metadata: String? = null)
    data class InspectionRequest(val decision: ReturnInspectionDecision, val conditionAssessment: String,
        val responsibility: RefundResponsibility, val idempotencyKey: String)
    data class RefundRequest(val amountCfa: Int, val idempotencyKey: String)
    data class ErrorResponse(val code: String, val message: String)

    @PostMapping
    fun create(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @RequestBody request: CreateReturnRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, setOf(RoleCode.CUSTOMER) + RoleGroups.AdminOperations) { authenticated ->
        val actorId = userId ?: authenticated.name
        val returnId = ApiInputPolicy.requiredIdentifier(request.returnId, "returnId")
        val orderId = ApiInputPolicy.requiredIdentifier(request.orderId, "orderId")
        val reason = ApiInputPolicy.requiredLongText(request.reason, "reason", 1000)
        val staff = authenticated.hasAnyRole(RoleGroups.AdminOperations)
        if (!staff && request.customerId != null && request.customerId != actorId) return@roleRequired ResponseEntity.status(403).build()
        val result = if (staff) service.requestForStaff(
            ApiInputPolicy.requiredIdentifier(request.customerId ?: actorId, "customerId"), returnId, orderId, reason,
            request.requestedRefundCfa, request.rawReturnPin, request.adminOverride == true,
        ) else service.requestForCustomer(actorId, returnId, orderId, reason)
        when (result) {
            is PersistedReturnResult.Accepted -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                if (staff) result.returnRequest else service.customerView(actorId, result.returnRequest))
            is PersistedReturnResult.Rejected -> result.toResponse()
        }
    }

    @GetMapping
    fun list(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @RequestParam(required = false) customerId: String?,
        @RequestParam(required = false) status: ReturnStatus?,
    ): ResponseEntity<Any> = roleRequired(authentication, setOf(RoleCode.CUSTOMER) + RoleGroups.AdminOperations) { authenticated ->
        val staff = authenticated.hasAnyRole(RoleGroups.AdminOperations)
        val returns = when {
            !staff && authenticated.hasRole(RoleCode.CUSTOMER) -> service.listForCustomer(userId ?: authenticated.name)
            status != null -> service.listByStatus(status)
            !customerId.isNullOrBlank() -> service.listForCustomer(ApiInputPolicy.requiredIdentifier(customerId, "customerId"))
            else -> service.listAll()
        }
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            if (!staff && authenticated.hasRole(RoleCode.CUSTOMER)) returns.map { service.customerView(userId ?: authenticated.name, it) } else returns)
    }

    @GetMapping("/merchant")
    fun merchantList(authentication: Authentication?, @RequestParam(required = false) merchantId: String?): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.MerchantOperators) { actor ->
            val scopes = actor.merchantScopeIds()
            val merchant = merchantId?.let { ApiInputPolicy.requiredIdentifier(it, "merchantId") } ?: scopes.singleOrNull()
            if (merchant == null || (!actor.hasAnyRole(RoleGroups.AdminOnly) && merchant !in scopes))
                return@roleRequired ResponseEntity.status(403).build()
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.listForMerchant(merchant))
        }

    @GetMapping("/relay")
    fun relayList(authentication: Authentication?, @RequestParam relayPointId: String): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.RelayOperators) { actor ->
            val point = ApiInputPolicy.requiredIdentifier(relayPointId, "relayPointId")
            if (!relayAccess.permits(actor, point)) return@roleRequired ResponseEntity.status(403).build()
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.listForRelay(point))
        }

    @GetMapping("/{returnId}")
    fun get(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @PathVariable returnId: String,
    ): ResponseEntity<Any> = roleRequired(authentication, setOf(RoleCode.CUSTOMER) + RoleGroups.AdminOperations + RoleGroups.RelayOperators) { authenticated ->
        val actorId = userId ?: authenticated.name
        val staff = authenticated.hasAnyRole(RoleGroups.AdminOperations)
        val item = service.get(ApiInputPolicy.requiredIdentifier(returnId, "returnId")) { customerId, relayPointId ->
            staff || (authenticated.hasRole(RoleCode.CUSTOMER) && customerId == actorId) ||
                (relayPointId?.let { relayAccess.permits(authenticated, it) } == true)
        } ?: return@roleRequired ResponseEntity.notFound().build()
        val customerOwner = authenticated.hasRole(RoleCode.CUSTOMER) && item.customerId == actorId
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            if (staff) item else if (customerOwner) service.customerView(actorId, item) else service.relayView(item))
    }

    @GetMapping("/orders/{orderId}/eligibility")
    fun eligibility(authentication: Authentication?, @PathVariable orderId: String): ResponseEntity<Any> =
        roleRequired(authentication, setOf(RoleCode.CUSTOMER)) { actor ->
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.customerEligibility(actor.name, ApiInputPolicy.requiredIdentifier(orderId, "orderId")))
        }

    @GetMapping("/orders/{orderId}")
    fun listForOrder(authentication: Authentication?, @PathVariable orderId: String): ResponseEntity<Any> =
        roleRequired(authentication, setOf(RoleCode.CUSTOMER) + RoleGroups.AdminOperations) { actor ->
            val id = ApiInputPolicy.requiredIdentifier(orderId, "orderId")
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                if (actor.hasAnyRole(RoleGroups.AdminOperations)) service.listForOrder(id) else service.customerReturnsForOrder(actor.name, id))
        }

    @PostMapping("/{returnId}/relay-dropoff")
    fun relayDropoff(
        authentication: Authentication?,
        @PathVariable returnId: String,
        @RequestBody request: RelayDropoffRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.RelayOperators) { actor ->
        if (!relayAccess.permits(actor, request.relayPointId)) return@roleRequired ResponseEntity.status(403).build()
        val result = service.recordRelayDropoff(
            PersistedRelayDropoffCommand(
                returnId = ApiInputPolicy.requiredIdentifier(returnId, "returnId"),
                relayPointId = ApiInputPolicy.requiredIdentifier(request.relayPointId, "relayPointId"),
                rawReturnPin = ApiInputPolicy.requiredShortText(request.rawReturnPin, "rawReturnPin", 64),
                droppedAt = service.currentTime(),
                operatorId = actor.name,
                idempotencyKey = ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey ?: "$returnId:relay-dropoff"),
                metadata = request.metadata?.let { ApiInputPolicy.requiredLongText(it, "metadata", 1000) },
            )
        )
        if (result is PersistedReturnResult.Accepted && !actor.hasAnyRole(RoleGroups.AdminOperations))
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.relayView(result.returnRequest))
        else result.toResponse()
    }

    @PostMapping("/{returnId}/collection")
    fun collection(authentication: Authentication?, @PathVariable returnId: String,
        @RequestBody request: CollectionRequest): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOperations) { actor ->
        service.recordCollection(PersistedReturnCollectionCommand(ApiInputPolicy.requiredIdentifier(returnId, "returnId"),
            actor.name, ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey),
            request.metadata?.let { ApiInputPolicy.requiredLongText(it, "metadata", 1000) })).toResponse()
    }

    @PostMapping("/{returnId}/inspection")
    fun inspection(authentication: Authentication?, @PathVariable returnId: String,
        @RequestBody request: InspectionRequest): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOperations) { actor ->
        service.recordInspection(PersistedReturnInspectionCommand(ApiInputPolicy.requiredIdentifier(returnId, "returnId"),
            actor.name, request.decision, ApiInputPolicy.requiredLongText(request.conditionAssessment, "conditionAssessment", 1000),
            request.responsibility, ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey))).toResponse()
    }

    @PostMapping("/{returnId}/physical-receipt")
    fun physicalReceipt(
        authentication: Authentication?,
        @PathVariable returnId: String,
        @RequestBody request: PhysicalReceiptRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOperations) { authenticated ->
        service.recordPhysicalReceipt(
            PersistedPhysicalReceiptCommand(
                returnId = ApiInputPolicy.requiredIdentifier(returnId, "returnId"),
                operatorId = authenticated.name,
                receivedAt = service.currentTime(),
                conditionAssessment = ApiInputPolicy.requiredLongText(request.conditionAssessment, "conditionAssessment", 1000),
                responsibility = request.responsibility,
                idempotencyKey = ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey),
            )
        ).toResponse()
    }

    @PostMapping("/{returnId}/refund")
    fun refund(
        authentication: Authentication?,
        @PathVariable returnId: String,
        @RequestBody request: RefundRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOnly) { actor ->
        service.triggerRefund(
            PersistedRefundTriggerCommand(
                returnId = ApiInputPolicy.requiredIdentifier(returnId, "returnId"),
                amountCfa = request.amountCfa,
                operatorId = actor.name,
                idempotencyKey = ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey),
            )
        ).toResponse()
    }

    private fun roleRequired(
        authentication: Authentication?,
        roles: Set<RoleCode>,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (!authentication.hasAnyRole(roles)) ResponseEntity.status(403).build()
        else operation(authentication)
}

private fun PersistedReturnResult.toResponse(): ResponseEntity<Any> = when (this) {
    is PersistedReturnResult.Accepted -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(returnRequest)
    is PersistedReturnResult.Rejected -> ResponseEntity.badRequest().body(ReturnController.ErrorResponse(rejection.code, rejection.message))
}
