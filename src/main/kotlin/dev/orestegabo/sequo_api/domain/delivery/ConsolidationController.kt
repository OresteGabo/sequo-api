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
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/consolidations")
class ConsolidationController(
    private val service: ConsolidationPersistenceService,
) {
    data class CreateRequest(
        val manifestId: String,
        val orderId: String,
        val customerId: String? = null,
        val sellerPackages: List<ConsolidationSellerPackage>? = null,
        val cooperativeId: String? = null,
    )

    data class TransitionRequest(
        val event: ConsolidationEvent,
        val at: Instant? = null,
        val finalPackageId: String? = null,
    )

    @PostMapping
    fun create(authentication: Authentication?, @RequestBody request: CreateRequest): ResponseEntity<Any> =
        authenticated(authentication, RoleGroups.AdminOperations) { actor ->
            ResponseEntity.ok(service.createForOrder(
                dev.orestegabo.sequo_api.api.ApiInputPolicy.requiredIdentifier(request.manifestId, "manifestId"),
                dev.orestegabo.sequo_api.api.ApiInputPolicy.requiredIdentifier(request.orderId, "orderId"), actor.name, request.cooperativeId))
        }

    @GetMapping
    fun list(authentication: Authentication?): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOperations) { ResponseEntity.ok(service.list()) }

    @GetMapping("/{manifestId}")
    fun get(authentication: Authentication?, @PathVariable manifestId: String): ResponseEntity<Any> =
        authenticated(authentication, RoleGroups.CustomerDeliveryTrackingReaders) { auth ->
            val manifest = service.get(manifestId) ?: return@authenticated ResponseEntity.notFound().build()
            if (!auth.hasAnyRole(RoleGroups.AdminOperations) && auth.name != manifest.customerId) {
                return@authenticated ResponseEntity.status(403).build()
            }
            ResponseEntity.ok(if (auth.hasAnyRole(RoleGroups.AdminOperations)) manifest else manifest.customerView())
        }

    @GetMapping("/order/{orderId}")
    fun getByOrder(authentication: Authentication?, @PathVariable orderId: String): ResponseEntity<Any> =
        authenticated(authentication, RoleGroups.CustomerDeliveryTrackingReaders) { auth ->
            val manifest = service.findByOrderId(orderId) ?: return@authenticated ResponseEntity.notFound().build()
            if (!auth.hasAnyRole(RoleGroups.AdminOperations) && auth.name != manifest.customerId) {
                return@authenticated ResponseEntity.status(403).build()
            }
            ResponseEntity.ok(if (auth.hasAnyRole(RoleGroups.AdminOperations)) manifest else manifest.customerView())
        }

    @PostMapping("/{manifestId}/seller-packages/{subOrderId}/ready")
    fun markSellerPackageReady(
        authentication: Authentication?,
        @PathVariable manifestId: String,
        @PathVariable subOrderId: String,
        @RequestBody request: ReadyRequest,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.MerchantOperators) { auth ->
        if (!auth.hasAnyRole(RoleGroups.AdminOperations) && !auth.hasMerchantScope(request.merchantId)) {
            return@authenticated ResponseEntity.status(403).build()
        }
        val manifest = service.markSellerPackageReady(manifestId, subOrderId, request.merchantId, Instant.now())
        ResponseEntity.ok(if (auth.hasAnyRole(RoleGroups.AdminOperations)) manifest else mapOf(
            "manifestId" to manifest.manifestId,
            "orderId" to manifest.orderId,
            "status" to manifest.status,
            "sellerPackages" to manifest.sellerPackages.filter { it.merchantId == request.merchantId },
        ))
    }

    @GetMapping("/{manifestId}/tracking")
    fun trackFinalPackage(
        authentication: Authentication?,
        @PathVariable manifestId: String,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.CustomerDeliveryTrackingReaders) { auth ->
        val tracking = service.trackFinalPackage(manifestId) ?: return@authenticated ResponseEntity.notFound().build()
        if (!auth.hasAnyRole(RoleGroups.AdminOperations) && auth.name != tracking.customerId) {
            return@authenticated ResponseEntity.status(403).build()
        }
        if (tracking.tracking == null) return@authenticated ResponseEntity.notFound().build()
        ResponseEntity.ok(tracking)
    }

    @PostMapping("/{manifestId}/seller-packages/{subOrderId}/collected")
    fun markSellerPackageCollected(
        authentication: Authentication?,
        @PathVariable manifestId: String,
        @PathVariable subOrderId: String,
        @RequestBody request: CollectedRequest,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOperations) { actor ->
        ResponseEntity.ok(service.markSellerPackageCollected(manifestId, subOrderId, Instant.now(), actor.name, dev.orestegabo.sequo_api.api.ApiInputPolicy.requiredLongText(request.metadata, "metadata")))
    }

    @PostMapping("/{manifestId}/transitions")
    fun transition(
        authentication: Authentication?,
        @PathVariable manifestId: String,
        @RequestBody request: TransitionRequest,
    ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOperations) { actor ->
        val current = service.get(manifestId) ?: return@authenticated ResponseEntity.notFound().build()
        val result = service.transition(
            ConsolidationTransitionRequest(
                manifest = current,
                event = request.event,
                at = Instant.now(),
                finalPackageId = request.finalPackageId,
            ), actorUserId = actor.name
        )
        if (result.accepted) ResponseEntity.ok(result) else ResponseEntity.badRequest().body(result)
    }

    @PostMapping("/{manifestId}/dispatch-final-package")
    fun dispatchFinalPackage(
        authentication: Authentication?,
        @PathVariable manifestId: String,
        @RequestBody request: FinalPackageDispatchRequest,
     ): ResponseEntity<Any> = authenticated(authentication, RoleGroups.AdminOperations) { actor ->
        ResponseEntity.ok(
            service.dispatchFinalPackage(
                manifestId = manifestId,
                customerDeliveryFeeCfa = request.customerDeliveryFeeCfa ?: 0,
                courierFeeCfa = request.courierFeeCfa ?: 0,
                actorUserId = actor.name,
                at = Instant.now(),
            )
        )
    }

    private fun authenticated(
        authentication: Authentication?,
        roles: Set<RoleCode>,
        operation: (Authentication) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> = when {
        authentication == null -> ResponseEntity.status(401).build()
        !authentication.hasAnyRole(roles) -> ResponseEntity.status(403).build()
        else -> operation(authentication)
    }

    private fun SequoConsolidationManifest.customerView() = copy(approvedByUserId = null, history = emptyList())

    data class ReadyRequest(val merchantId: String, val at: Instant? = null)
    data class CollectedRequest(val at: Instant? = null, val metadata: String = "")
    data class FinalPackageDispatchRequest(
        val customerDeliveryFeeCfa: Int? = null,
        val courierFeeCfa: Int? = null,
        val at: Instant? = null,
    )
}
