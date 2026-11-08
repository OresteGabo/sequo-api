package dev.orestegabo.sequo_api.domain.store

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.api.ApiErrorResponse
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.admin.AdminPartyChangeDto
import dev.orestegabo.sequo_api.domain.admin.AdminPartyMutationDto
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import jakarta.persistence.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID

data class StoreOpeningHoursDay(val dayOfWeek: Int, val opensAt: String, val closesAt: String, val closed: Boolean = false)
data class StoreOpeningHoursException(val id: String? = null, val date: String, val closed: Boolean,
    val opensAt: String? = null, val closesAt: String? = null, val reason: String? = null)
data class StoreTemporaryClosure(val id: String? = null, val startsAt: String, val endsAt: String, val reason: String? = null)
data class MerchantStoreSettings(val open: Boolean, val acceptingOrders: Boolean, val timezone: String,
    val openingHours: List<StoreOpeningHoursDay>, val temporaryExceptions: List<StoreOpeningHoursException>,
    val temporaryClosure: StoreTemporaryClosure? = null, val closureReason: String? = null)
data class MerchantStoreUpdateRequest(val open: Boolean? = null, val acceptingOrders: Boolean? = null, val timezone: String? = null,
    val openingHours: List<StoreOpeningHoursDay>? = null, val temporaryExceptions: List<StoreOpeningHoursException>? = null,
    val temporaryClosure: StoreTemporaryClosure? = null, val closureReason: String? = null,
    val reason: String? = null, val expectedUpdatedAt: Instant? = null, val idempotencyKey: String? = null)
data class MerchantStoreState(val merchantId: String, val configured: Boolean, val open: Boolean, val acceptingOrders: Boolean,
    val openStatus: String, val weekdayHours: String, val weekendHours: String,
    val openingHours: List<StoreOpeningHoursDay>, val temporaryExceptions: List<StoreOpeningHoursException>,
    val temporaryClosure: StoreTemporaryClosure?, val closureReason: String?, val timezone: String?,
    val updatedAt: Instant, val canAcceptOrders: Boolean, val history: List<AdminPartyChangeDto> = emptyList())
data class MerchantStoreQuoteRevision(val merchantId: String, val updatedAt: String?, val canAcceptOrders: Boolean)

@Entity @Table(name = "merchant_store_settings")
class MerchantStoreRecord(
    @Id @Column(name = "merchant_id") val merchantId: String,
    @Column(name = "settings_json", nullable = false, columnDefinition = "text") var settingsJson: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
interface MerchantStoreRepository : JpaRepository<MerchantStoreRecord, String>

@Entity @Table(name = "merchant_store_changes")
class MerchantStoreChangeRecord(
    @Id val id: String,
    @Column(name = "merchant_id", nullable = false) val merchantId: String,
    @Column(name = "actor_user_id", nullable = false) val actorUserId: String,
    @Column(name = "before_value", columnDefinition = "text") val beforeValue: String?,
    @Column(name = "after_value", nullable = false, columnDefinition = "text") val afterValue: String,
    @Column(nullable = false, length = 1000) val reason: String,
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128) val idempotencyKey: String,
    @Column(name = "intent_hash", nullable = false, length = 64) val intentHash: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)
interface MerchantStoreChangeRepository : JpaRepository<MerchantStoreChangeRecord, String> {
    fun findByIdempotencyKey(idempotencyKey: String): MerchantStoreChangeRecord?
    fun findTop50ByMerchantIdOrderByCreatedAtDesc(merchantId: String): List<MerchantStoreChangeRecord>
}

@Configuration(proxyBeanMethods = false)
class MerchantStoreClockConfiguration {
    @Bean("merchantStoreClock") @ConditionalOnMissingBean(name = ["merchantStoreClock"])
    fun clock(): Clock = Clock.systemUTC()
}

object MerchantStorePolicy {
    fun normalize(request: MerchantStoreUpdateRequest): MerchantStoreSettings {
        val zone = ApiInputPolicy.requiredShortText(request.timezone.orEmpty(), "timezone", 80)
        require(runCatching { ZoneId.of(zone) }.isSuccess) { "Select a valid timezone." }
        val weekly = requireNotNull(request.openingHours) { "openingHours is required (empty for manual hours)." }
        require(weekly.isEmpty() || weekly.map { it.dayOfWeek }.sorted() == (1..7).toList()) {
            "A weekly schedule must contain each day 1 to 7 exactly once, or be empty for manual hours."
        }
        val hours = weekly.sortedBy { it.dayOfWeek }.map {
            if (it.closed) it.copy(opensAt = "", closesAt = "")
            else it.also { day -> validateRange(day.opensAt, day.closesAt) }
        }
        val exceptions = requireNotNull(request.temporaryExceptions) { "temporaryExceptions is required." }
        require(exceptions.size <= 100) { "At most 100 dated exceptions are allowed." }
        require(exceptions.map { it.date }.distinct().size == exceptions.size) { "Only one exception is allowed per date." }
        val normalizedExceptions = exceptions.sortedBy { it.date }.map {
            require(runCatching { LocalDate.parse(it.date) }.getOrNull()?.toString() == it.date) { "Exception dates must use YYYY-MM-DD." }
            if (!it.closed) validateRange(it.opensAt.orEmpty(), it.closesAt.orEmpty())
            it.copy(id = ApiInputPolicy.requiredIdentifier(it.id ?: "date-${it.date}", "exceptionId"),
                opensAt = it.opensAt?.takeUnless { _ -> it.closed }, closesAt = it.closesAt?.takeUnless { _ -> it.closed },
                reason = ApiInputPolicy.optionalShortText(it.reason, "exception reason", 1000))
        }
        require(normalizedExceptions.map { it.id }.distinct().size == normalizedExceptions.size) { "Exception IDs must be unique." }
        val closure = request.temporaryClosure?.let {
            val start = runCatching { Instant.parse(it.startsAt) }.getOrNull()
            val end = runCatching { Instant.parse(it.endsAt) }.getOrNull()
            require(start != null && end != null && start < end) { "Temporary closure needs valid start and end instants, with end after start." }
            it.copy(id = ApiInputPolicy.requiredIdentifier(it.id ?: "temporary-closure", "closureId"),
                startsAt = start.toString(), endsAt = end.toString(), reason = ApiInputPolicy.optionalShortText(it.reason, "closure reason", 1000))
        }
        return MerchantStoreSettings(requireNotNull(request.open) { "open is required." },
            requireNotNull(request.acceptingOrders) { "acceptingOrders is required." }, zone, hours, normalizedExceptions,
            closure, ApiInputPolicy.optionalShortText(request.closureReason, "closureReason", 1000))
    }

    private fun validateRange(open: String, close: String) {
        val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        require(time.matches(open) && time.matches(close) && open < close) {
            "Hours must use HH:mm with closing after opening on the same day; overnight ranges are not supported."
        }
    }

    fun status(settings: MerchantStoreSettings, now: Instant): String {
        if (!settings.open) return "PAUSED"
        if (!settings.acceptingOrders) return "NOT_ACCEPTING_ORDERS"
        settings.temporaryClosure?.let {
            if (!now.isBefore(Instant.parse(it.startsAt)) && now.isBefore(Instant.parse(it.endsAt))) return "TEMPORARILY_CLOSED"
        }
        val local = now.atZone(ZoneId.of(settings.timezone))
        val exception = settings.temporaryExceptions.firstOrNull { it.date == local.toLocalDate().toString() }
        if (exception != null) {
            return if (exception.closed || !within(local.toLocalTime(), exception.opensAt, exception.closesAt)) "CLOSED_BY_EXCEPTION" else "OPEN"
        }
        if (settings.openingHours.isEmpty()) return "OPEN"
        val day = settings.openingHours.firstOrNull { it.dayOfWeek == local.dayOfWeek.value }
        return if (day == null || day.closed || !within(local.toLocalTime(), day.opensAt, day.closesAt)) "OUTSIDE_HOURS" else "OPEN"
    }

    private fun within(now: LocalTime, opensAt: String?, closesAt: String?): Boolean = opensAt != null && closesAt != null &&
        !now.isBefore(LocalTime.parse(opensAt)) && now.isBefore(LocalTime.parse(closesAt))
}

@Service
class MerchantStoreService(
    private val stores: MerchantStoreRepository, private val changes: MerchantStoreChangeRepository,
    private val merchants: CommerceMerchantRepository, private val users: UserRepository,
    private val memberships: MerchantMembershipRepository,
    @Qualifier("merchantStoreClock") private val clock: Clock,
) {
    private val mapper = ObjectMapper()

    @Transactional(readOnly = true)
    fun get(id: String): MerchantStoreState? = merchants.findById(id).orElse(null)?.let { state(it, stores.findById(id).orElse(null), true) }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun update(id: String, actor: Authentication, request: MerchantStoreUpdateRequest): AdminPartyMutationDto<MerchantStoreState> {
        requireWriter(actor, id)
        val settings = MerchantStorePolicy.normalize(request)
        val reason = ApiInputPolicy.requiredShortText(request.reason.orEmpty(), "reason", 1000)
        val expected = requireNotNull(request.expectedUpdatedAt) { "expectedUpdatedAt is required." }
        val key = ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey.orEmpty())
        val after = mapper.writeValueAsString(settings)
        val hash = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(listOf(id, actor.name, after, reason, expected.toString()))).toHexString()
        val merchant = merchants.lockForCatalogCreate(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val current = stores.findById(id).orElse(null)
        changes.findByIdempotencyKey(key)?.let {
            if (it.merchantId != id || it.actorUserId != actor.name || it.intentHash != hash)
                throw MerchantStoreConflict("merchant_store_retry_conflict", "This retry key belongs to a different store, actor or change. Refresh and review.")
            return AdminPartyMutationDto(state(merchant, current, true), it.dto())
        }
        val previousAt = current?.updatedAt ?: merchant.updatedAt
        if (previousAt != expected) throw MerchantStoreConflict("merchant_store_stale_revision", "Store settings changed. Refresh and review before saving.")
        val at = clock.instant().truncatedTo(ChronoUnit.MICROS).let { if (it > previousAt) it else previousAt.plusNanos(1000) }
        val event = changes.save(MerchantStoreChangeRecord(UUID.randomUUID().toString(), id, actor.name, current?.settingsJson, after, reason, key, hash, at))
        val record = current?.apply { settingsJson = after; updatedAt = at } ?: MerchantStoreRecord(id, after, at, at)
        stores.save(record)
        return AdminPartyMutationDto(state(merchant, record, true), event.dto())
    }

    private fun requireWriter(actor: Authentication, merchantId: String) {
        val user = users.findById(actor.name).orElse(null)
        if (user == null || !user.active || !user.status.canAuthenticate()) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        if (actor.hasAnyRole(RoleGroups.AdminOnly) && user.roles.any { it in RoleGroups.AdminOnly }) return
        val merchantRole = setOf(RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF)
        if (!actor.hasAnyRole(merchantRole) || user.roles.none { it in merchantRole } || !actor.hasMerchantScope(merchantId) ||
            (actor.name != merchantId && memberships.findAllByUserIdAndActiveTrue(actor.name).none { it.merchantId == merchantId }))
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    /** Used by new checkout while its user lock is held; writers take the same sorted merchant locks. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun lockForNewCheckout(ids: Collection<String>) {
        ids.distinct().sorted().forEach { id ->
            require(merchants.lockForCatalogCreate(id)?.status == "ACTIVE") { "A basket merchant is unavailable." }
        }
        quoteRevisions(ids)
    }

    @Transactional(readOnly = true)
    fun quoteRevisions(ids: Collection<String>): List<MerchantStoreQuoteRevision> {
        val byId = stores.findAllById(ids).associateBy { it.merchantId }
        return ids.distinct().sorted().map { id ->
            val record = byId[id]
            val accepts = record == null || MerchantStorePolicy.status(read(record.settingsJson), clock.instant()) == "OPEN"
            require(accepts) { "A store is not accepting new orders. Refresh the basket and try again when it reopens." }
            MerchantStoreQuoteRevision(id, record?.updatedAt?.toString(), accepts)
        }
    }

    @Transactional(readOnly = true)
    fun publicOpenStatuses(ids: Collection<String>): Map<String, String> = stores.findAllById(ids).associate { record ->
        record.merchantId to if (MerchantStorePolicy.status(read(record.settingsJson), clock.instant()) == "OPEN") "Open" else "Closed"
    }

    private fun state(merchant: MerchantRecord, record: MerchantStoreRecord?, history: Boolean): MerchantStoreState {
        val settings = record?.let { read(it.settingsJson) }
        val status = if (merchant.status != "ACTIVE") "MERCHANT_INACTIVE" else settings?.let { MerchantStorePolicy.status(it, clock.instant()) } ?: "NOT_CONFIGURED"
        fun label(days: IntRange): String = settings?.openingHours.orEmpty().filter { it.dayOfWeek in days }
            .joinToString(" · ") { "${it.dayOfWeek}: ${if (it.closed) "Closed" else "${it.opensAt}–${it.closesAt}"}" }
        return MerchantStoreState(merchant.id, record != null, settings?.open ?: false, settings?.acceptingOrders ?: false,
            status, label(1..5), label(6..7), settings?.openingHours.orEmpty(), settings?.temporaryExceptions.orEmpty(),
            settings?.temporaryClosure, settings?.closureReason, settings?.timezone, record?.updatedAt ?: merchant.updatedAt,
            (merchant.status == "ACTIVE" && (record == null || status == "OPEN")), if (history) changes.findTop50ByMerchantIdOrderByCreatedAtDesc(merchant.id).map { it.dto() } else emptyList())
    }

    private fun read(value: String): MerchantStoreSettings {
        val n = mapper.readTree(value)
        fun JsonNode.optionalText(name: String) = path(name).takeIf { it.isTextual }?.asText()
        return MerchantStoreSettings(n["open"].asBoolean(), n["acceptingOrders"].asBoolean(), n["timezone"].asText(),
            n["openingHours"].map { StoreOpeningHoursDay(it["dayOfWeek"].asInt(), it["opensAt"].asText(), it["closesAt"].asText(), it["closed"].asBoolean()) },
            n["temporaryExceptions"].map { StoreOpeningHoursException(it.optionalText("id"), it["date"].asText(), it["closed"].asBoolean(), it.optionalText("opensAt"), it.optionalText("closesAt"), it.optionalText("reason")) },
            n.path("temporaryClosure").takeIf { it.isObject }?.let { StoreTemporaryClosure(it.optionalText("id"), it["startsAt"].asText(), it["endsAt"].asText(), it.optionalText("reason")) }, n.optionalText("closureReason"))
    }

    private fun MerchantStoreChangeRecord.dto() = AdminPartyChangeDto(id, "SET_STORE_SETTINGS", actorUserId, beforeValue, afterValue, reason, idempotencyKey, createdAt)
}

@RestController
@RequestMapping("/api/merchant/store")
class MerchantStoreController(private val service: MerchantStoreService) {
    @GetMapping
    fun get(auth: Authentication?, @RequestParam(required = false) merchantId: String?): ResponseEntity<Any> = scoped(auth, merchantId) { id -> found(service.get(id)) }
    @PutMapping
    fun update(auth: Authentication?, @RequestParam(required = false) merchantId: String?, @RequestBody request: MerchantStoreUpdateRequest): ResponseEntity<Any> =
        scoped(auth, merchantId) { id -> ok(service.update(id, requireNotNull(auth), request)) }

    private fun scoped(auth: Authentication?, requested: String?, action: (String) -> ResponseEntity<Any>): ResponseEntity<Any> {
        if (auth == null) return ResponseEntity.status(401).build()
        if (!auth.hasAnyRole(setOf(RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF))) return ResponseEntity.status(403).build()
        val id = requested?.let { ApiInputPolicy.requiredIdentifier(it, "merchantId") } ?: auth.merchantScopeIds().singleOrNull()
        if (id == null || !auth.hasMerchantScope(id)) return ResponseEntity.status(403).build()
        return action(id)
    }
}

@RestController
@RequestMapping("/api/admin/web/merchants/{merchantId}/store")
class AdminMerchantStoreController(private val service: MerchantStoreService) {
    @GetMapping
    fun get(auth: Authentication?, @PathVariable merchantId: String): ResponseEntity<Any> = admin(auth) { found(service.get(ApiInputPolicy.requiredIdentifier(merchantId, "merchantId"))) }
    @PutMapping
    fun update(auth: Authentication?, @PathVariable merchantId: String, @RequestBody request: MerchantStoreUpdateRequest): ResponseEntity<Any> =
        admin(auth) { ok(service.update(ApiInputPolicy.requiredIdentifier(merchantId, "merchantId"), requireNotNull(auth), request)) }
    private fun admin(auth: Authentication?, action: () -> ResponseEntity<Any>): ResponseEntity<Any> =
        if (auth == null) ResponseEntity.status(401).build() else if (!auth.hasAnyRole(RoleGroups.AdminOnly)) ResponseEntity.status(403).build() else action()
}

private fun ok(value: Any): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
private fun found(value: Any?): ResponseEntity<Any> = value?.let(::ok) ?: ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
class MerchantStoreConflict(val code: String, override val message: String) : RuntimeException(message)

@RestControllerAdvice(assignableTypes = [MerchantStoreController::class, AdminMerchantStoreController::class])
class MerchantStoreErrors {
    @ExceptionHandler(MerchantStoreConflict::class)
    fun conflict(error: MerchantStoreConflict): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(ApiErrorResponse(error.code, error.message))
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun concurrent(): ResponseEntity<ApiErrorResponse> = ResponseEntity.status(409).cacheControl(CacheControl.noStore())
        .body(ApiErrorResponse("merchant_store_write_conflict", "A concurrent store change won. Refresh and review the current settings and history."))
}
