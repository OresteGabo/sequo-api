package dev.orestegabo.sequo_api.domain.reference

import dev.orestegabo.sequo.reference.HumanReference
import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasRole
import dev.orestegabo.sequo_api.domain.relay.RelayOperatorAccess
import dev.orestegabo.sequo_api.domain.returns.ReturnPersistenceService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Exact operator lookup, not a public existence oracle. Domain actions keep their existing guards. */
@RestController
@RequestMapping("/api/references")
class PublicReferenceController(
    private val registry: PublicReferenceRegistry,
    private val relayAccess: RelayOperatorAccess,
    private val returns: ReturnPersistenceService,
) {
    data class ResolveRequest(
        val kind: PublicReferenceKind,
        val reference: String,
        val relayPointId: String? = null,
        val legacyId: Boolean? = null,
    )
    data class ResolvedReference(val kind: PublicReferenceKind, val id: String, val publicReference: String)
    data class ReferenceError(val code: String, val message: String)

    @PostMapping("/resolve")
    fun resolve(authentication: Authentication?, @RequestBody request: ResolveRequest): ResponseEntity<Any> {
        if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken)
            return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).build()
        val admin = authentication.hasAnyRole(RoleGroups.AdminOnly)
        if (!admin && (!authentication.hasRole(RoleCode.RELAY_PARTNER) || request.kind != PublicReferenceKind.RETURN))
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build()
        val relay = if (admin) null else {
            val point = request.relayPointId?.let { ApiInputPolicy.requiredIdentifier(it, "relayPointId") }
                ?: return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(ReferenceError("relay_required", "Select your authorized relay before looking up a return."))
            if (!relayAccess.permits(authentication, point))
                return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build()
            point
        }

        // A malformed reference is rejected before any registry/entity lookup. Legacy IDs are an explicit opt-in.
        val legacy = request.legacyId == true
        val id = if (legacy) ApiInputPolicy.requiredIdentifier(request.reference, "reference") else {
            if (!HumanReference.isValid(request.reference, request.kind))
                return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(ReferenceError("reference_checksum_invalid", "Check the reference: it contains a typing error or a different record type."))
            registry.resolve(request.kind, request.reference)
                ?: return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
        }
        if (relay != null && !returns.canResolveForRelay(id, relay))
            return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
        // Admin legacy mode does not allocate references for arbitrary guessed IDs. It resolves only previously
        // projected records; the subsequent domain read/action must still succeed under its normal permissions.
        val reference = if (legacy && admin) registry.findReference(request.kind, id)
            ?: return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
        else registry.reference(request.kind, id)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ResolvedReference(request.kind, id, reference))
    }
}
