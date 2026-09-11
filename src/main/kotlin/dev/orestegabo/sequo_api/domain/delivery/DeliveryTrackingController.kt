package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasRole
import dev.orestegabo.sequo_api.domain.order.OrderFulfillmentPersistenceService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/delivery/tracking")
class DeliveryTrackingController(
    private val service: DeliveryMissionService,
    private val orders: OrderFulfillmentPersistenceService,
) {
    data class ErrorResponse(val code: String, val message: String)

    @GetMapping("/{deliveryCode}")
    fun track(
        authentication: Authentication?,
        @PathVariable deliveryCode: String,
        @RequestParam orderId: String,
    ): ResponseEntity<Any> {
        val auth = authentication ?: return ResponseEntity.status(401).build()
        return roleRequired(auth, RoleGroups.CustomerDeliveryTrackingReaders) {
            if (requiresCustomerOwnership(auth) && orders.getForCustomer(orderId, auth.name) == null) {
                return@roleRequired ResponseEntity.notFound().build()
            }
            service.track(deliveryCode, orderId)?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.notFound().build()
        }
    }

    private fun requiresCustomerOwnership(authentication: Authentication): Boolean =
        authentication.hasRole(RoleCode.CUSTOMER) && !authentication.hasAnyRole(RoleGroups.AdminOperations)

    private fun roleRequired(
        authentication: Authentication?,
        roles: Set<RoleCode>,
        operation: () -> ResponseEntity<Any>,
    ): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(roles)) {
            ResponseEntity.status(403).build()
        } else try {
            operation()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ErrorResponse(
                    code = "invalid_delivery_tracking_request",
                    message = e.message ?: "Invalid delivery tracking request.",
                )
            )
        }
}
