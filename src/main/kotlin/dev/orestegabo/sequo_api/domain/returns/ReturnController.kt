package dev.orestegabo.sequo_api.domain.returns

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasRole
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
@RequestMapping("/api/returns")
class ReturnController(
    private val service: ReturnPersistenceService,
) {
    data class CreateReturnRequest(
        val returnId: String,
        val orderId: String,
        val customerId: String,
        val reason: String,
        val requestedRefundCfa: Int,
        val rawReturnPin: String,
        val requestedAt: Instant = Instant.now(),
        val productReturnable: Boolean = true,
        val merchantAllowsReturn: Boolean = true,
        val adminOverride: Boolean = false,
    )
    data class RelayDropoffRequest(
        val relayPointId: String,
        val rawReturnPin: String,
        val droppedAt: Instant = Instant.now(),
    )
    data class PhysicalReceiptRequest(
        val receivedAt: Instant = Instant.now(),
        val conditionAssessment: String,
        val responsibility: RefundResponsibility,
        val idempotencyKey: String,
    )
    data class RefundRequest(val amountCfa: Int, val idempotencyKey: String)
    data class ErrorResponse(val code: String, val message: String)

    @PostMapping
    fun create(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @RequestBody request: CreateReturnRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, setOf(RoleCode.CUSTOMER) + RoleGroups.AdminOperations) { authenticated ->
        val actorId = userId ?: authenticated.name
        if (authenticated.hasRole(RoleCode.CUSTOMER) && request.customerId != actorId) {
            return@roleRequired ResponseEntity.status(403).build()
        }
        service.requestReturn(
            PersistedReturnRequestCommand(
                returnId = request.returnId,
                orderId = request.orderId,
                customerId = request.customerId,
                reason = request.reason,
                requestedRefundCfa = request.requestedRefundCfa,
                rawReturnPin = request.rawReturnPin,
                requestedAt = request.requestedAt,
                productReturnable = request.productReturnable,
                merchantAllowsReturn = request.merchantAllowsReturn,
                adminOverride = request.adminOverride && authenticated.hasAnyRole(RoleGroups.AdminOperations),
            )
        ).toResponse()
    }

    @GetMapping
    fun list(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @RequestParam(required = false) customerId: String?,
        @RequestParam(required = false) status: ReturnStatus?,
    ): ResponseEntity<Any> = roleRequired(authentication, setOf(RoleCode.CUSTOMER) + RoleGroups.AdminOperations) { authenticated ->
        val returns = when {
            authenticated.hasRole(RoleCode.CUSTOMER) -> service.listForCustomer(userId ?: authenticated.name)
            status != null -> service.listByStatus(status)
            !customerId.isNullOrBlank() -> service.listForCustomer(customerId)
            else -> service.listByStatus(ReturnStatus.ReceivedBySequo)
        }
        ResponseEntity.ok(returns)
    }

    @GetMapping("/{returnId}")
    fun get(
        authentication: Authentication?,
        @AuthenticationPrincipal userId: String?,
        @PathVariable returnId: String,
    ): ResponseEntity<Any> = roleRequired(authentication, setOf(RoleCode.CUSTOMER) + RoleGroups.AdminOperations + RoleGroups.RelayOperators) { authenticated ->
        val item = service.get(returnId) ?: return@roleRequired ResponseEntity.notFound().build()
        val actorId = userId ?: authenticated.name
        if (authenticated.hasRole(RoleCode.CUSTOMER) && item.customerId != actorId) {
            return@roleRequired ResponseEntity.status(403).build()
        }
        ResponseEntity.ok(item)
    }

    @GetMapping("/orders/{orderId}")
    fun listForOrder(authentication: Authentication?, @PathVariable orderId: String): ResponseEntity<Any> =
        roleRequired(authentication, RoleGroups.AdminOperations) {
            ResponseEntity.ok(service.listForOrder(orderId))
        }

    @PostMapping("/{returnId}/relay-dropoff")
    fun relayDropoff(
        authentication: Authentication?,
        @PathVariable returnId: String,
        @RequestBody request: RelayDropoffRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.RelayOperators) {
        service.recordRelayDropoff(
            PersistedRelayDropoffCommand(
                returnId = returnId,
                relayPointId = request.relayPointId,
                rawReturnPin = request.rawReturnPin,
                droppedAt = request.droppedAt,
            )
        ).toResponse()
    }

    @PostMapping("/{returnId}/physical-receipt")
    fun physicalReceipt(
        authentication: Authentication?,
        @PathVariable returnId: String,
        @RequestBody request: PhysicalReceiptRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOperations) { authenticated ->
        service.recordPhysicalReceipt(
            PersistedPhysicalReceiptCommand(
                returnId = returnId,
                operatorId = authenticated.name,
                receivedAt = request.receivedAt,
                conditionAssessment = request.conditionAssessment,
                responsibility = request.responsibility,
                idempotencyKey = request.idempotencyKey,
            )
        ).toResponse()
    }

    @PostMapping("/{returnId}/refund")
    fun refund(
        authentication: Authentication?,
        @PathVariable returnId: String,
        @RequestBody request: RefundRequest,
    ): ResponseEntity<Any> = roleRequired(authentication, RoleGroups.AdminOnly) {
        service.triggerRefund(
            PersistedRefundTriggerCommand(
                returnId = returnId,
                amountCfa = request.amountCfa,
                idempotencyKey = request.idempotencyKey,
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
        else try {
            operation(authentication)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ErrorResponse("invalid_return_request", e.message ?: "Invalid return request."))
        }
}

private fun PersistedReturnResult.toResponse(): ResponseEntity<Any> = when (this) {
    is PersistedReturnResult.Accepted -> ResponseEntity.ok(returnRequest)
    is PersistedReturnResult.Rejected -> ResponseEntity.badRequest().body(ReturnController.ErrorResponse(rejection.code, rejection.message))
}
