package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.pricing.*
import jakarta.persistence.EntityManager
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AdminDeliveryPriceValues(val minimumDeliveryFeeCfa: Int, val extraKmFeeCfa: Int, val includedKm: Int)
data class AdminDeliveryPriceProfile(val id: String, val profile: String, val minimumDeliveryFeeCfa: Int,
    val extraKmFeeCfa: Int, val includedKm: Int, val active: Boolean, val effectiveFrom: Instant)
data class AdminDeliveryPriceState(val revision: String, val effective: AdminDeliveryPriceValues,
    val profiles: List<AdminDeliveryPriceProfile>, val history: List<AdminPartyChangeDto>)
data class AdminDeliveryPriceRequest(val minimumDeliveryFeeCfa: Int?, val extraKmFeeCfa: Int?, val includedKm: Int?,
    val approvalReference: String?, val reason: String?, val expectedRevision: String?, val idempotencyKey: String?)

/** Append a reviewed tariff, preserving old order prices and every historical profile. */
@Service
class AdminDeliveryPricingService(private val prices: DeliveryPricingSettingsRepository,
    private val pricing: DeliveryPricingService, private val changes: AdminPartyChangeRepository,
    private val users: UserRepository, private val scopes: AdminAccessScopeRepository, private val entities: EntityManager) {
    @Transactional(readOnly = true)
    fun state(): AdminDeliveryPriceState {
        val records = prices.findAll().sortedBy { it.id }
        val effective = pricing.effectiveSettings()
        return AdminDeliveryPriceState(revision(records), AdminDeliveryPriceValues(effective.minimumDeliveryFeeCfa,
            effective.extraKmFeeCfa, effective.includedKm), records.sortedByDescending { it.effectiveFrom }.map {
            AdminDeliveryPriceProfile(it.id, it.profile, it.minimumDeliveryFeeCfa, it.extraKmFeeCfa, it.includedKm, it.active, it.effectiveFrom)
        }, changes.findTop50ByDomainAndEntityIdOrderByCreatedAtDesc("DELIVERY_PRICING", "effective").map(::operation))
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun update(actor: String, request: AdminDeliveryPriceRequest): AdminPartyMutationDto<AdminDeliveryPriceState> {
        // Same durable mutex as account administration: revocation and concurrent price saves serialize.
        scopes.lockScope("GLOBAL") ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Administration migration is required.")
        val user = users.lockCheckoutOwner(actor) ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        entities.refresh(user)
        if (!user.active || !user.status.canAuthenticate() || user.roles.none { it in RoleGroups.AdminOnly })
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Current administrator authority is required.")
        val minimum = requireNotNull(request.minimumDeliveryFeeCfa) { "Minimum delivery price is required." }
        val extra = requireNotNull(request.extraKmFeeCfa) { "Additional kilometer price is required." }
        val included = requireNotNull(request.includedKm) { "Included kilometers are required." }
        require(minimum in 0..1_000_000 && extra in 0..1_000_000 && included in 0..1000) { "Enter bounded non-negative whole-number delivery prices and kilometers." }
        val approval = ApiInputPolicy.requiredShortText(request.approvalReference.orEmpty(), "approvalReference", 300)
        val reason = ApiInputPolicy.requiredShortText(request.reason.orEmpty(), "reason", 600)
        val expected = ApiInputPolicy.requiredShortText(request.expectedRevision.orEmpty(), "expectedRevision", 64)
        val key = ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey.orEmpty())
        val intent = hash(listOf(actor, minimum, extra, included, approval, reason, expected).joinToString("\u0000"))
        changes.findByIdempotencyKey(key)?.let {
            if (it.domain != "DELIVERY_PRICING" || it.actorUserId != actor || it.intentHash != intent)
                throw ResponseStatusException(HttpStatus.CONFLICT, "The retry key belongs to a different reviewed change.")
            return AdminPartyMutationDto(state(), operation(it))
        }
        val before = state()
        if (expected != before.revision) throw ResponseStatusException(HttpStatus.CONFLICT, "Delivery settings changed. Refresh and review the current prices.")
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        prices.saveAndFlush(DeliveryPricingSettingsRecord("admin-price-${UUID.randomUUID()}", "SEQUO_APPROVED",
            minimum, extra, included, true, now, now))
        val event = changes.saveAndFlush(AdminPartyChangeRecord(UUID.randomUUID().toString(), "DELIVERY_PRICING", "effective",
            "SET_PRICE", actor, values(before.effective), values(AdminDeliveryPriceValues(minimum, extra, included)),
            "$reason | Approval: $approval", key, intent, now))
        return AdminPartyMutationDto(state(), operation(event))
    }

    private fun values(v: AdminDeliveryPriceValues) = "minimum=${v.minimumDeliveryFeeCfa};extraKm=${v.extraKmFeeCfa};includedKm=${v.includedKm}"
    private fun revision(records: List<DeliveryPricingSettingsRecord>) = hash(records.joinToString("\n") {
        listOf(it.id, it.profile, it.minimumDeliveryFeeCfa, it.extraKmFeeCfa, it.includedKm, it.active, it.effectiveFrom, it.createdAt, it.version).joinToString("\u0000")
    })
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString()
    private fun operation(it: AdminPartyChangeRecord) = AdminPartyChangeDto(it.id, it.action, it.actorUserId,
        it.beforeValue, it.afterValue, it.reason, it.idempotencyKey, it.createdAt)
}

@RestController
@RequestMapping("/api/admin/web/delivery-pricing/manage")
class AdminDeliveryPricingController(private val service: AdminDeliveryPricingService) {
    @GetMapping
    fun read(authentication: Authentication?) = adminControlRead(authentication) { ok(service.state()) }
    @PutMapping
    fun update(authentication: Authentication?, @RequestBody request: AdminDeliveryPriceRequest) =
        adminControlRead(authentication) { ok(service.update(requireNotNull(authentication).name, request)) }
    @ExceptionHandler(ResponseStatusException::class)
    fun rejected(error: ResponseStatusException): ResponseEntity<Any> = ResponseEntity.status(error.statusCode)
        .cacheControl(CacheControl.noStore()).body(dev.orestegabo.sequo_api.api.ApiErrorResponse("delivery_pricing_rejected", error.reason ?: "Delivery price change was refused."))
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun concurrent(): ResponseEntity<Any> = ResponseEntity.status(409).cacheControl(CacheControl.noStore())
        .body(dev.orestegabo.sequo_api.api.ApiErrorResponse("delivery_pricing_conflict", "A concurrent change won. Refresh the current prices and history before retrying."))
    private fun ok(value: Any): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}
