package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import dev.orestegabo.sequo_api.domain.relay.RelayPointRepository
import dev.orestegabo.sequo_api.domain.settlement.*
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(name = "admin_party_changes", uniqueConstraints = [UniqueConstraint(name = "uk_admin_party_retry", columnNames = ["idempotency_key"])])
class AdminPartyChangeRecord(
    @Id val id: String,
    @Column(nullable = false, length = 32) val domain: String,
    @Column(name = "entity_id", nullable = false) val entityId: String,
    @Column(nullable = false, length = 32) val action: String,
    @Column(name = "actor_user_id", nullable = false) val actorUserId: String,
    @Column(name = "before_value") val beforeValue: String?,
    @Column(name = "after_value") val afterValue: String?,
    @Column(nullable = false, length = 1000) val reason: String,
    @Column(name = "idempotency_key", nullable = false, length = 128) val idempotencyKey: String,
    @Column(name = "intent_hash", nullable = false, length = 64) val intentHash: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)

interface AdminPartyChangeRepository : JpaRepository<AdminPartyChangeRecord, String> {
    fun findByIdempotencyKey(idempotencyKey: String): AdminPartyChangeRecord?
    fun findTop50ByDomainAndEntityIdOrderByCreatedAtDesc(domain: String, entityId: String): List<AdminPartyChangeRecord>
}

data class AdminPartyChangeDto(val id: String, val action: String, val actorUserId: String, val beforeValue: String?,
    val afterValue: String?, val reason: String, val idempotencyKey: String, val createdAt: Instant)
data class AdminPartyMutationDto<T>(val record: T, val operation: AdminPartyChangeDto)
data class AdminCourierDto(val id: String, val userId: String?, val displayName: String?, val status: String,
    val workforceType: DeliveryWorkforceType?, val rawWorkforceType: String?, val vehicleType: String?,
    val availability: CourierAvailabilitySnapshot, val activeMissionCount: Long, val updatedAt: Instant)
data class AdminCourierDetailDto(val courier: AdminCourierDto, val payOverview: RiderPayoutSummaryResponse?, val history: List<AdminPartyChangeDto>)
data class AdminRelaySiteDto(val id: String, val name: String, val status: String, val operatorUserId: String?,
    val operatorDisplayName: String?, val city: String?, val neighborhood: String?, val landmark: String?, val updatedAt: Instant,
    val latitude: Double? = null, val longitude: Double? = null)
data class AdminRelaySiteDetailDto(val site: AdminRelaySiteDto, val history: List<AdminPartyChangeDto>)
data class AdminLogicalSlotDto(val id: String, val relayPointId: String, val relayLockerGridId: String, val lockerCode: String,
    val status: HubLockerStatus, val availabilityReason: HubLockerAvailabilityReason?, val expectedAvailableAt: Instant?, val updatedAt: Instant)

data class AdminWorkforceRequest(val workforceType: DeliveryWorkforceType?, val reason: String?, val expectedUpdatedAt: Instant?, val idempotencyKey: String?)
data class AdminRelayOperatorRequest(val operatorUserId: String?, val reason: String?, val expectedUpdatedAt: Instant?, val idempotencyKey: String?)
data class AdminRelayOperatorRemoval(val reason: String?, val expectedUpdatedAt: Instant?, val idempotencyKey: String?)

@Service
class AdminPartyControlService(
    private val couriers: SettlementCourierRecordRepository,
    private val points: RelayPointRepository,
    private val users: UserRepository,
    private val missions: DeliveryMissionRepository,
    private val ledger: SettlementLedgerEntryRecordRepository,
    private val availability: CourierAvailabilityService,
    private val settlement: SettlementPersistenceService,
    private val changes: AdminPartyChangeRepository,
    private val lockers: RelayLockerRepository,
) {
    private val busy = setOf(DeliveryMissionRecordStatus.CREATED, DeliveryMissionRecordStatus.OFFERED_TO_COURIER,
        DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER, DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER, DeliveryMissionRecordStatus.PROBLEM_REPORTED)

    @Transactional(readOnly = true)
    fun couriers(): List<AdminCourierDto> = couriers.findAll().sortedBy { it.id }.map(::courierDto)

    @Transactional(readOnly = true)
    fun courier(id: String): AdminCourierDetailDto? = couriers.findById(id).orElse(null)?.let(::courierDetail)

    @Transactional(readOnly = true)
    fun sites(): List<AdminRelaySiteDto> = points.findAll().sortedBy { it.id }.map(::siteDto)

    @Transactional(readOnly = true)
    fun site(id: String): AdminRelaySiteDetailDto? = points.findById(id).orElse(null)?.let(::siteDetail)

    @Transactional(readOnly = true)
    fun logicalSlots(id: String): List<AdminLogicalSlotDto>? {
        if (!points.existsById(id)) return null
        return lockers.findByRelayPointId(id).sortedBy { it.lockerCode }.map {
            AdminLogicalSlotDto(it.id, it.relayPointId, it.relayLockerGridId, it.lockerCode, it.status,
                it.availabilityReason, it.expectedAvailableAt, it.updatedAt)
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun workforce(id: String, actor: String, request: AdminWorkforceRequest): AdminPartyMutationDto<AdminCourierDetailDto> {
        requireActor(actor)
        val desired = requireNotNull(request.workforceType) { "workforceType is required." }
        val command = command("COURIER_WORKFORCE", id, "SET_WORKFORCE", actor, desired.name, request.reason, request.expectedUpdatedAt, request.idempotencyKey)
        val courier = couriers.lockForAdministration(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        replay(command)?.let { return AdminPartyMutationDto(courierDetail(courier), it.dto()) }
        requireFresh(courier.updatedAt, command.expected)
        if (courierWorkforce(courier.workforceType) != desired &&
            (missions.findByCourierIdOrderByUpdatedAtDesc(id).any { it.assignmentWorkforceType == null } ||
                ledger.findByCourierIdOrderByCreatedAtDesc(id).any { it.workforceType == null })) {
            throw AdminPartyConflict("legacy_workforce_review_required", "Legacy workforce snapshots need review before changing future workforce; no history was rewritten.")
        }
        val at = nextTimestamp(courier.updatedAt)
        val event = append(command, courier.workforceType, at)
        courier.workforceType = if (desired == DeliveryWorkforceType.SequoSalaried) "SEQUO_SALARIED" else "FREELANCER"
        courier.updatedAt = at
        couriers.save(courier)
        return AdminPartyMutationDto(courierDetail(courier), event.dto())
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun operator(id: String, actor: String, request: AdminRelayOperatorRequest): AdminPartyMutationDto<AdminRelaySiteDetailDto> =
        changeOperator(id, actor, ApiInputPolicy.requiredIdentifier(request.operatorUserId ?: "", "operatorUserId"),
            request.reason, request.expectedUpdatedAt, request.idempotencyKey)

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun removeOperator(id: String, actor: String, request: AdminRelayOperatorRemoval): AdminPartyMutationDto<AdminRelaySiteDetailDto> =
        changeOperator(id, actor, null, request.reason, request.expectedUpdatedAt, request.idempotencyKey)

    private fun changeOperator(id: String, actor: String, operator: String?, reason: String?, expected: Instant?, key: String?): AdminPartyMutationDto<AdminRelaySiteDetailDto> {
        requireActor(actor)
        val command = command("RELAY_OPERATOR", id, if (operator == null) "REMOVE_OPERATOR" else "ASSIGN_OPERATOR", actor, operator, reason, expected, key)
        val site = points.lockForAdministration(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        replay(command)?.let { return AdminPartyMutationDto(siteDetail(site), it.dto()) }
        requireFresh(site.updatedAt, command.expected)
        if (operator != null) {
            val user = users.lockCheckoutOwner(operator)
            if (user == null || !user.active || !user.status.canAuthenticate() || RoleCode.RELAY_PARTNER !in user.roles) {
                throw AdminPartyConflict("relay_operator_ineligible", "Select an existing active relay partner; no role is granted by this action.")
            }
            if (site.status != "ACTIVE") throw AdminPartyConflict("relay_site_inactive", "Only an active relay site can receive an operator.")
            if (points.findByOperatorUserId(operator).any { it.status == "ACTIVE" && it.id != site.id }) {
                throw AdminPartyConflict("relay_operator_already_assigned", "This operator is already assigned to another active relay site.")
            }
        }
        val at = nextTimestamp(site.updatedAt)
        val event = append(command, site.operatorUserId, at)
        site.operatorUserId = operator
        site.updatedAt = at
        points.save(site)
        return AdminPartyMutationDto(siteDetail(site), event.dto())
    }

    private fun requireActor(id: String) {
        val user = users.findById(id).orElse(null)
        if (user == null || !user.active || !user.status.canAuthenticate() || user.roles.none { it in RoleGroups.AdminOnly }) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "An active administrator is required.")
        }
    }

    private data class Command(val domain: String, val target: String, val action: String, val actor: String,
        val desired: String?, val reason: String, val expected: Instant, val key: String, val hash: String)

    private fun command(domain: String, id: String, action: String, actor: String, value: String?, reason: String?, expected: Instant?, key: String?): Command {
        val safeReason = ApiInputPolicy.requiredShortText(reason ?: "", "reason", 1000)
        val revision = requireNotNull(expected) { "expectedUpdatedAt is required." }
        val retryKey = ApiInputPolicy.requiredIdempotencyKey(key ?: "")
        val intent = listOf(domain, id, action, actor, value.orEmpty(), safeReason, revision.toString()).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(intent.toByteArray()).joinToString("") { "%02x".format(it) }
        return Command(domain, id, action, actor, value, safeReason, revision, retryKey, hash)
    }

    private fun replay(command: Command): AdminPartyChangeRecord? =
        changes.findByIdempotencyKey(command.key)?.also {
            if (it.actorUserId != command.actor || it.intentHash != command.hash) {
                throw AdminPartyConflict("admin_party_retry_conflict", "The retry key belongs to a different actor, target, action or intent.")
            }
        }

    private fun requireFresh(actual: Instant, expected: Instant) {
        if (actual != expected) throw AdminPartyConflict("admin_party_stale_revision", "This record changed. Refresh and review before submitting a new action.")
    }

    private fun append(command: Command, before: String?, at: Instant): AdminPartyChangeRecord = changes.save(AdminPartyChangeRecord(
        UUID.randomUUID().toString(), command.domain, command.target, command.action, command.actor,
        before, command.desired, command.reason, command.key, command.hash, at))

    private fun history(domain: String, id: String) = changes.findTop50ByDomainAndEntityIdOrderByCreatedAtDesc(domain, id).map { it.dto() }
    private fun nextTimestamp(previous: Instant): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
        .let { if (it > previous) it else previous.plusNanos(1000) }

    private fun courierDto(courier: CourierRecord): AdminCourierDto = AdminCourierDto(courier.id, courier.userId,
        courier.userId?.let { users.findById(it).orElse(null)?.displayName }, courier.status,
        courierWorkforce(courier.workforceType), courier.workforceType, courier.vehicleType,
        availability.get(courier.id), missions.countByCourierIdAndStatusIn(courier.id, busy), courier.updatedAt)

    private fun courierDetail(courier: CourierRecord) = AdminCourierDetailDto(courierDto(courier),
        courier.userId?.let { settlement.riderPayoutSummary(it) }, history("COURIER_WORKFORCE", courier.id))

    private fun siteDto(site: RelayPointRecord) = AdminRelaySiteDto(site.id, site.name, site.status, site.operatorUserId,
        site.operatorUserId?.let { users.findById(it).orElse(null)?.displayName }, site.city, site.neighborhood, site.landmark, site.updatedAt,
        site.latitude, site.longitude)
    private fun siteDetail(site: RelayPointRecord) = AdminRelaySiteDetailDto(siteDto(site), history("RELAY_OPERATOR", site.id))
}

private fun AdminPartyChangeRecord.dto() = AdminPartyChangeDto(id, action, actorUserId, beforeValue, afterValue, reason, idempotencyKey, createdAt)

@RestController
@RequestMapping("/api/admin/web")
class AdminPartyControlController(private val service: AdminPartyControlService) {
    @GetMapping("/couriers")
    fun couriers(authentication: Authentication?) = adminControlRead(authentication) { ok(service.couriers()) }
    @GetMapping("/couriers/{id}")
    fun courier(authentication: Authentication?, @PathVariable id: String) = adminControlRead(authentication) { found(service.courier(identifier(id))) }
    @PutMapping("/couriers/{id}/workforce")
    fun workforce(authentication: Authentication?, @PathVariable id: String, @RequestBody request: AdminWorkforceRequest) =
        adminControlRead(authentication) { ok(service.workforce(identifier(id), requireNotNull(authentication).name, request)) }
    @GetMapping("/relay-sites")
    fun sites(authentication: Authentication?) = adminControlRead(authentication) { ok(service.sites()) }
    @GetMapping("/relay-sites/{id}")
    fun site(authentication: Authentication?, @PathVariable id: String) = adminControlRead(authentication) { found(service.site(identifier(id))) }
    @GetMapping("/relay-sites/{id}/slots")
    fun slots(authentication: Authentication?, @PathVariable id: String) = adminControlRead(authentication) { found(service.logicalSlots(identifier(id))) }
    @PutMapping("/relay-sites/{id}/operator")
    fun operator(authentication: Authentication?, @PathVariable id: String, @RequestBody request: AdminRelayOperatorRequest) =
        adminControlRead(authentication) { ok(service.operator(identifier(id), requireNotNull(authentication).name, request)) }
    @DeleteMapping("/relay-sites/{id}/operator")
    fun removeOperator(authentication: Authentication?, @PathVariable id: String, @RequestBody request: AdminRelayOperatorRemoval) =
        adminControlRead(authentication) { ok(service.removeOperator(identifier(id), requireNotNull(authentication).name, request)) }
    private fun identifier(value: String) = ApiInputPolicy.requiredIdentifier(value, "id")
    private fun ok(value: Any): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
    private fun found(value: Any?): ResponseEntity<Any> = value?.let(::ok) ?: ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
}

class AdminPartyConflict(val code: String, override val message: String) : RuntimeException(message)

@RestControllerAdvice(assignableTypes = [AdminPartyControlController::class, DeliveryMissionController::class, PersistedDeliveryAssignmentController::class])
class AdminPartyControlErrors {
    @ExceptionHandler(AdminPartyConflict::class)
    fun conflict(error: AdminPartyConflict): ResponseEntity<dev.orestegabo.sequo_api.api.ApiErrorResponse> =
        ResponseEntity.status(409).cacheControl(CacheControl.noStore())
            .body(dev.orestegabo.sequo_api.api.ApiErrorResponse(error.code, error.message))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun concurrentRetry(): ResponseEntity<dev.orestegabo.sequo_api.api.ApiErrorResponse> =
        ResponseEntity.status(409).cacheControl(CacheControl.noStore())
            .body(dev.orestegabo.sequo_api.api.ApiErrorResponse("admin_party_write_conflict", "A concurrent action won. Refresh and review the current record and history."))
}
