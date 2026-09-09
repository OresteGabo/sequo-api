package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/consolidations")
class ConsolidationController(
    private val service: ConsolidationPersistenceService,
) {
    data class CreateRequest(
        val manifestId: String,
        val orderId: String,
        val customerId: String,
        val sellerPackages: List<ConsolidationSellerPackage>,
    )

    data class TransitionRequest(
        val event: ConsolidationEvent,
        val at: Instant = Instant.now(),
        val finalPackageId: String? = null,
    )

    @PostMapping
    fun create(authentication: Authentication?, @RequestBody request: CreateRequest): ResponseEntity<Any> =
        authenticated(authentication, RoleGroups.AdminOperations) {
            ResponseEntity.ok(service.create(
                SequoConsolidationManifest(
                    manifestId = request.manifestId,
                    orderId = request.orderId,
                    customerId = request.customerId,
                    sellerPackages = request.sellerPackages,
                )
            ))
        }

    @GetMapping("/{manifestId}")
    fun get(authentication: Authentication?, @PathVariable manifestId: String): ResponseEntity<Any> =
        authenticated(authentication, RoleGroups.CustomerDeliveryTrackingReaders) { auth ->
            val manifest = service.get(manifestId) ?: return@authenticated ResponseEntity.notFound().build()
            if (!auth.hasAnyRole(RoleGroups.AdminOperations) && auth.name != manifest.customerId) {
                return@authenticated ResponseEntity.status(403).build()
            }
            ResponseEntity.ok(manifest)
        }

    @GetMapping("/order/{orderId}")
    fun getByOrder(authentication: Authentication?, @PathVariable orderId: String): ResponseEntity<Any> =
        authenticated(authentication, RoleGroups.CustomerDeliveryTrackingReaders) { auth ->
            val manifest = service.findByOrderId(orderId) ?: return@authenticated ResponseEntity.notFound().build()
            if (!auth.hasAnyRole(RoleGroups.AdminOperations) && auth.name != manifest.customerId) {
                return@authenticated ResponseEntity.status(403).build()
            }
            ResponseEntity.ok(manifest)
        }

    @PostMapping("/{manifestId}/seller-packages/{subOrderId}/ready")
    fun markSellerPackageReady(
        authentication: Authentication?,
        @PathVariable manifestId: String,
        @PathVariable subOrderId: String,
        @RequestBody request: ReadyRequest,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.MerchantOperators) { auth ->
        if (!auth.hasAnyRole(RoleGroups.AdminOperations) && auth.name != request.merchantId) {
            return@authenticated ResponseEntity.status(403).build()
        }
        ResponseEntity.ok(service.markSellerPackageReady(manifestId, subOrderId, request.merchantId, request.at))
    }

    @PostMapping("/{manifestId}/seller-packages/{subOrderId}/collected")
    fun markSellerPackageCollected(
        authentication: Authentication?,
        @PathVariable manifestId: String,
        @PathVariable subOrderId: String,
        @RequestBody request: CollectedRequest,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOperations) {
        ResponseEntity.ok(service.markSellerPackageCollected(manifestId, subOrderId, request.at))
    }

    @PostMapping("/{manifestId}/transitions")
    fun transition(
        authentication: Authentication?,
        @PathVariable manifestId: String,
        @RequestBody request: TransitionRequest,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOperations) {
        val current = service.get(manifestId) ?: return@authenticated ResponseEntity.notFound().build()
        val result = service.transition(
            ConsolidationTransitionRequest(
                manifest = current,
                event = request.event,
                at = request.at,
                finalPackageId = request.finalPackageId,
            )
        )
        if (result.accepted) ResponseEntity.ok(result) else ResponseEntity.badRequest().body(result)
    }

    private fun authenticated(
        authentication: Authentication?,
        roles: Set<RoleCode>,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> = when {
        authentication == null -> ResponseEntity.status(401).build()
        !authentication.hasAnyRole(roles) -> ResponseEntity.status(403).build()
        else -> try { operation(authentication) } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ErrorResponse("invalid_consolidation_request", e.message ?: "Invalid consolidation request."))
        }
    }

    data class ErrorResponse(val code: String, val message: String)
    data class ReadyRequest(val merchantId: String, val at: Instant = Instant.now())
    data class CollectedRequest(val at: Instant = Instant.now())
}
