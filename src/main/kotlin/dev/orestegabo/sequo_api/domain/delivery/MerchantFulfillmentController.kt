package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasMerchantScope
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/merchant/sub-orders")
class MerchantFulfillmentController(
    private val service: MerchantFulfillmentService,
) {
    data class MerchantActionRequest(val merchantId: String)
    data class MarkPackedRequest(val merchantId: String, val packageCount: Int)
    data class RejectRequest(val merchantId: String, val reason: String)
    data class PublishSlaWarningsRequest(val evaluatedAt: Instant = Instant.now(), val limit: Int = 100)
    data class EscalateRequest(val reason: MerchantFulfillmentEscalationReason, val note: String)
    data class ErrorResponse(val code: String, val message: String)

    @PostMapping
    fun create(
        authentication: Authentication?,
        @RequestBody request: CreateMerchantSubOrderCommand,
    ): ResponseEntity<Any> = adminOnly(authentication) {
        ResponseEntity.ok(service.create(request))
    }

    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam merchantId: String,
        @RequestParam(required = false) status: MerchantSubOrderStatus?,
    ): ResponseEntity<Any> = merchantRequired(authentication) { authenticated ->
        if (!merchantScopeAllowed(authenticated, merchantId)) {
            return@merchantRequired ResponseEntity.status(403).build()
        }
        val statuses = status?.let { setOf(it) } ?: MerchantFulfillmentService.activeMerchantStatuses
        ResponseEntity.ok(service.listForMerchant(merchantId, statuses))
    }

    @PostMapping("/sla/publish-overdue")
    fun publishOverdueSlaWarnings(
        authentication: Authentication?,
        @RequestBody request: PublishSlaWarningsRequest,
    ): ResponseEntity<Any> = operationsOnly(authentication) {
        ResponseEntity.ok(service.publishOverdueSlaWarnings(request.evaluatedAt, request.limit))
    }

    @GetMapping("/{subOrderId}/escalations")
    fun escalations(
        authentication: Authentication?,
        @PathVariable subOrderId: String,
    ): ResponseEntity<Any> = operationsOnly(authentication) {
        ResponseEntity.ok(service.listEscalations(subOrderId))
    }

    @PostMapping("/{subOrderId}/escalations")
    fun escalate(
        authentication: Authentication?,
        @PathVariable subOrderId: String,
        @RequestBody request: EscalateRequest,
    ): ResponseEntity<Any> = operationsOnly(authentication) { authenticated ->
        ResponseEntity.ok(service.escalate(subOrderId, authenticated.name, request.reason, request.note))
    }

    @GetMapping("/{subOrderId}")
    fun get(
        authentication: Authentication?,
        @PathVariable subOrderId: String,
        @RequestParam(required = false) merchantId: String?,
    ): ResponseEntity<Any> = merchantRequired(authentication) { authenticated ->
        val subOrder = service.get(subOrderId) ?: return@merchantRequired ResponseEntity.notFound().build()
        if (!authenticated.hasAnyRole(RoleGroups.AdminOnly) && !merchantCanSee(authenticated, subOrder.merchantId, merchantId)) {
            return@merchantRequired ResponseEntity.status(403).build()
        }
        ResponseEntity.ok(subOrder)
    }

    @GetMapping("/{subOrderId}/sla")
    fun sla(
        authentication: Authentication?,
        @PathVariable subOrderId: String,
        @RequestParam(required = false) merchantId: String?,
    ): ResponseEntity<Any> = merchantRequired(authentication) { authenticated ->
        val subOrder = service.get(subOrderId) ?: return@merchantRequired ResponseEntity.notFound().build()
        if (!authenticated.hasAnyRole(RoleGroups.AdminOnly) && !merchantCanSee(authenticated, subOrder.merchantId, merchantId)) {
            return@merchantRequired ResponseEntity.status(403).build()
        }
        service.sla(subOrderId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    @PostMapping("/{subOrderId}/accept")
    fun accept(
        authentication: Authentication?,
        @PathVariable subOrderId: String,
        @RequestBody request: MerchantActionRequest,
    ): ResponseEntity<Any> = merchantRequired(authentication) { authenticated ->
        if (!merchantScopeAllowed(authenticated, request.merchantId)) {
            return@merchantRequired ResponseEntity.status(403).build()
        }
        service.accept(subOrderId, request.merchantId).toResponse()
    }

    @PostMapping("/{subOrderId}/start-preparation")
    fun startPreparation(
        authentication: Authentication?,
        @PathVariable subOrderId: String,
        @RequestBody request: MerchantActionRequest,
    ): ResponseEntity<Any> = merchantRequired(authentication) { authenticated ->
        if (!merchantScopeAllowed(authenticated, request.merchantId)) {
            return@merchantRequired ResponseEntity.status(403).build()
        }
        service.startPreparation(subOrderId, request.merchantId).toResponse()
    }

    @PostMapping("/{subOrderId}/mark-packed")
    fun markPacked(
        authentication: Authentication?,
        @PathVariable subOrderId: String,
        @RequestBody request: MarkPackedRequest,
    ): ResponseEntity<Any> = merchantRequired(authentication) { authenticated ->
        if (!merchantScopeAllowed(authenticated, request.merchantId)) {
            return@merchantRequired ResponseEntity.status(403).build()
        }
        service.markPacked(subOrderId, request.merchantId, request.packageCount).toResponse()
    }

    @PostMapping("/{subOrderId}/handoff")
    fun handoff(
        authentication: Authentication?,
        @PathVariable subOrderId: String,
        @RequestBody request: MerchantActionRequest,
    ): ResponseEntity<Any> = merchantRequired(authentication) { authenticated ->
        if (!merchantScopeAllowed(authenticated, request.merchantId)) {
            return@merchantRequired ResponseEntity.status(403).build()
        }
        service.confirmCourierHandoff(subOrderId, request.merchantId).toResponse()
    }

    @PostMapping("/{subOrderId}/reject")
    fun reject(
        authentication: Authentication?,
        @PathVariable subOrderId: String,
        @RequestBody request: RejectRequest,
    ): ResponseEntity<Any> = merchantRequired(authentication) { authenticated ->
        if (!merchantScopeAllowed(authenticated, request.merchantId)) {
            return@merchantRequired ResponseEntity.status(403).build()
        }
        service.reject(subOrderId, request.merchantId, request.reason).toResponse()
    }

    private fun adminOnly(
        authentication: Authentication?,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        authenticated(authentication, RoleGroups.AdminOnly, operation)

    private fun operationsOnly(
        authentication: Authentication?,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        authenticated(authentication, RoleGroups.AdminOperations, operation)

    private fun merchantRequired(
        authentication: Authentication?,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        authenticated(authentication, RoleGroups.MerchantOperators, operation)

    private fun merchantScopeAllowed(authentication: Authentication, merchantId: String): Boolean =
        authentication.hasAnyRole(RoleGroups.AdminOnly) || authentication.hasMerchantScope(merchantId)

    private fun merchantCanSee(
        authentication: Authentication,
        subOrderMerchantId: String,
        requestedMerchantId: String?,
    ): Boolean {
        val visibleMerchantId = requestedMerchantId?.takeIf { it.isNotBlank() }
        return if (visibleMerchantId == null) {
            merchantScopeAllowed(authentication, subOrderMerchantId)
        } else {
            subOrderMerchantId == visibleMerchantId && merchantScopeAllowed(authentication, visibleMerchantId)
        }
    }

    private fun authenticated(
        authentication: Authentication?,
        roles: Set<RoleCode>,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(roles)) {
            ResponseEntity.status(403).build()
        } else try {
            operation(authentication)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ErrorResponse(
                    code = "invalid_merchant_fulfillment_request",
                    message = e.message ?: "Invalid merchant request.",
                )
            )
        }
}

private fun MerchantFulfillmentServiceResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is MerchantFulfillmentServiceResult.Success -> ResponseEntity.ok(subOrder)
        is MerchantFulfillmentServiceResult.Rejected -> ResponseEntity.badRequest().body(
            MerchantFulfillmentController.ErrorResponse(code, message)
        )
    }
