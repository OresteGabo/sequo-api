package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import dev.orestegabo.sequo_api.domain.relay.*
import jakarta.persistence.*
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity @Table(name = "relay_administration_operations")
class RelayAdministrationOperationRecord(
    @Id val id: String,
    @Column(name = "relay_point_id", nullable = false) val relayPointId: String,
    @Column(nullable = false, length = 32) val action: String,
    @Column(name = "actor_user_id", nullable = false) val actorUserId: String,
    @Column(nullable = false, length = 1000) val reason: String,
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128) val idempotencyKey: String,
    @Column(name = "intent_hash", nullable = false, length = 64) val intentHash: String,
    @Column(name = "before_json", columnDefinition = "text") val beforeJson: String?,
    @Column(name = "after_json", nullable = false, columnDefinition = "text") val afterJson: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)
interface RelayAdministrationOperationRepository : JpaRepository<RelayAdministrationOperationRecord, String> {
    fun findByIdempotencyKey(idempotencyKey: String): RelayAdministrationOperationRecord?
    fun findTop50ByRelayPointIdOrderByCreatedAtDesc(relayPointId: String): List<RelayAdministrationOperationRecord>
}
data class RelayAdministrationOperationDto(val id: String, val relayPointId: String, val action: String, val actorUserId: String,
    val reason: String, val idempotencyKey: String, val before: Map<String, String?>?, val after: Map<String, String?>, val createdAt: Instant)
data class RelaySetupSnapshot(val site: AdminRelaySiteDto, val slots: List<AdminLogicalSlotDto>, val history: List<RelayAdministrationOperationDto>)
data class RelaySetupMutation(val record: RelaySetupSnapshot, val operation: RelayAdministrationOperationDto,
    val parcelId: String? = null, val mission: DeliveryMissionSnapshot? = null)
data class RelaySiteDetailsRequest(val name: String? = null, val latitude: Double? = null, val longitude: Double? = null,
    val city: String? = null, val neighborhood: String? = null, val landmark: String? = null,
    val expectedUpdatedAt: Instant? = null, val reason: String? = null, val idempotencyKey: String? = null)
data class RelaySlotCreateRequest(val lockerCode: String? = null, val expectedUpdatedAt: Instant? = null,
    val reason: String? = null, val idempotencyKey: String? = null)
data class RelaySlotAvailabilityRequest(val status: HubLockerStatus? = null, val expectedUpdatedAt: Instant? = null,
    val reason: String? = null, val idempotencyKey: String? = null)
data class SequoRelayDepositRequest(val missionId: String? = null, val lockerId: String? = null,
    val expectedMissionUpdatedAt: Instant? = null, val expectedSlotUpdatedAt: Instant? = null,
    val physicalReceiptConfirmed: Boolean = false, val reason: String? = null, val idempotencyKey: String? = null)

@Service
class AdminRelayAdministrationService(
    private val points: RelayPointRepository, private val lockers: RelayLockerRepository,
    private val people: UserRepository, private val parties: AdminPartyControlService,
    private val operations: RelayAdministrationOperationRepository, private val parcels: RelayParcelRecordRepository,
    private val orders: CustomerOrderRecordRepository, private val missions: DeliveryMissionRepository,
    private val fulfillment: RelayFulfillmentService, private val entityManager: EntityManager,
) {
    private val json = ObjectMapper()
    @Transactional(readOnly = true)
    fun setup(id: String): RelaySetupSnapshot? = parties.site(id)?.let {
        RelaySetupSnapshot(it.site, parties.logicalSlots(id).orEmpty(), operations.findTop50ByRelayPointIdOrderByCreatedAtDesc(id).map(::dto))
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun create(actor: String, request: RelaySiteDetailsRequest): RelaySetupMutation {
        requireActor(actor)
        val values = details(request)
        val intent = intent("CREATE_SITE", "NEW", actor, request.reason, request.idempotencyKey, values)
        replay(intent)?.let { return response(it) }
        val id = UUID.randomUUID().toString(); val at = now()
        val site = RelayPointRecord(id, name = values.getValue("name")!!, latitude = request.latitude, longitude = request.longitude,
            city = values["city"], neighborhood = values["neighborhood"], landmark = values["landmark"], createdAt = at, updatedAt = at)
        entityManager.persist(site); entityManager.flush()
        return response(record(id, intent, null, siteState(site)))
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun update(id: String, actor: String, request: RelaySiteDetailsRequest): RelaySetupMutation {
        requireActor(actor)
        val values = details(request) + ("expectedUpdatedAt" to expected(request.expectedUpdatedAt).toString())
        val intent = intent("UPDATE_SITE", id, actor, request.reason, request.idempotencyKey, values)
        replay(intent)?.let { return response(it) }
        val site = site(id); replay(intent)?.let { return response(it) }; fresh(site.updatedAt, request.expectedUpdatedAt)
        // Once slots exist, changing the physical location requires a new site and custody review.
        require(site.latitude == null || site.longitude == null ||
            (site.latitude == request.latitude && site.longitude == request.longitude) || lockers.findByRelayPointId(id).isEmpty()) {
            "A site with storage slots cannot be relocated. Create a new site and review existing custody."
        }
        val before = siteState(site)
        site.name = values.getValue("name")!!; site.latitude = request.latitude; site.longitude = request.longitude
        site.city = values["city"]; site.neighborhood = values["neighborhood"]; site.landmark = values["landmark"]
        site.updatedAt = next(site.updatedAt); points.save(site)
        return response(record(id, intent, before, siteState(site)))
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun createSlot(id: String, actor: String, request: RelaySlotCreateRequest): RelaySetupMutation {
        requireActor(actor)
        val code = ApiInputPolicy.requiredShortText(request.lockerCode.orEmpty(), "lockerCode", 32).uppercase()
        require(code.matches(Regex("[A-Z0-9][A-Z0-9_-]{0,31}"))) { "Use letters, digits, underscores or hyphens for the logical slot code." }
        val intent = intent("CREATE_SLOT", id, actor, request.reason, request.idempotencyKey,
            mapOf("lockerCode" to code, "expectedUpdatedAt" to expected(request.expectedUpdatedAt).toString()))
        replay(intent)?.let { return response(it) }
        val site = site(id); replay(intent)?.let { return response(it) }; fresh(site.updatedAt, request.expectedUpdatedAt)
        require(lockers.findByRelayPointId(id).none { it.lockerCode.equals(code, true) }) { "This logical slot code already exists at the selected site." }
        val gridId = "$id:logical"
        if (entityManager.find(RelayLockerGridRecord::class.java, gridId) == null) {
            entityManager.persist(RelayLockerGridRecord(id = gridId, relayPointId = id, gridCode = "LOGICAL", label = "Logical storage slots"))
            entityManager.flush()
        }
        val at = next(site.updatedAt)
        val slot = RelayLockerRecord(UUID.randomUUID().toString(), id, relayLockerGridId = gridId, lockerCode = code,
            updatedByUserId = actor, createdAt = at, updatedAt = at)
        entityManager.persist(slot); entityManager.flush(); site.updatedAt = at; points.save(site)
        return response(record(id, intent, null, slotState(slot)))
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun availability(id: String, slotId: String, actor: String, request: RelaySlotAvailabilityRequest): RelaySetupMutation {
        requireActor(actor)
        val status = requireNotNull(request.status) { "status is required." }
        require(status in setOf(HubLockerStatus.AVAILABLE, HubLockerStatus.MAINTENANCE)) { "Occupied custody is recorded by deposit, not by a manual availability switch." }
        val intent = intent("SET_SLOT_AVAILABILITY", id, actor, request.reason, request.idempotencyKey,
            mapOf("lockerId" to slotId, "status" to status.name, "expectedUpdatedAt" to expected(request.expectedUpdatedAt).toString()))
        replay(intent)?.let { return response(it) }
        site(id)
        val slot = requireNotNull(lockers.lockForDeposit(slotId)) { "Logical slot was not found." }
        replay(intent)?.let { return response(it) }
        require(slot.relayPointId == id) { "Logical slot belongs to another relay site." }
        fresh(slot.updatedAt, request.expectedUpdatedAt)
        require(slot.status != HubLockerStatus.OCCUPIED && parcels.findByRelayPointIdOrderByUpdatedAtDesc(id).none {
            it.lockerId == slotId && it.status !in setOf(RelayParcelStatus.PickedUp, RelayParcelStatus.ReturnedToSeller, RelayParcelStatus.CollectedBySequo)
        }) { "Current custody must be resolved through pickup or collection before changing this slot." }
        val before = slotState(slot)
        slot.status = status; slot.availabilityReason = if (status == HubLockerStatus.MAINTENANCE) HubLockerAvailabilityReason.OTHER else HubLockerAvailabilityReason.OPERATOR_CONFIRMED_AVAILABLE
        slot.expectedAvailableAt = null; slot.updatedByUserId = actor; slot.updatedAt = next(slot.updatedAt); lockers.save(slot)
        return response(record(id, intent, before, slotState(slot)))
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun deposit(id: String, actor: String, request: SequoRelayDepositRequest): RelaySetupMutation {
        requireActor(actor)
        require(request.physicalReceiptConfirmed) { "Explicit confirmation of actual physical receipt is required." }
        val missionId = ApiInputPolicy.requiredIdentifier(request.missionId.orEmpty(), "missionId")
        val lockerId = ApiInputPolicy.requiredIdentifier(request.lockerId.orEmpty(), "lockerId")
        val intent = intent("SEQUO_DEPOSIT", id, actor, request.reason, request.idempotencyKey,
            mapOf("missionId" to missionId, "lockerId" to lockerId, "physicalReceiptConfirmed" to "true",
                "expectedMissionUpdatedAt" to expected(request.expectedMissionUpdatedAt).toString(), "expectedSlotUpdatedAt" to expected(request.expectedSlotUpdatedAt).toString()))
        replay(intent)?.let { return response(it, missionId) }
        val reference = missions.findById(missionId).orElse(null) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        orders.lockForCompletion(reference.orderId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val mission = requireNotNull(missions.lockForAction(missionId)); site(id)
        val slot = requireNotNull(lockers.lockForDeposit(lockerId)) { "Logical slot was not found." }
        replay(intent)?.let { return response(it, missionId) }
        require(slot.relayPointId == id) { "Logical slot belongs to another relay site." }
        fresh(mission.updatedAt, request.expectedMissionUpdatedAt); fresh(slot.updatedAt, request.expectedSlotUpdatedAt)
        require(mission.relayPointId == id && mission.destinationType == DeliveryMissionRecordDestination.RELAY_POINT &&
            mission.status == DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER && mission.pickupAt != null) {
            "The mission must have an authorized recorded pickup and this persisted relay destination, with no open delivery problem."
        }
        val before = mapOf("missionId" to missionId, "status" to mission.status.name, "lockerId" to lockerId)
        val result = fulfillment.depositBySequo(missionId, actor, id, slot.lockerCode, intent.reason, intent.key)
        check(result is DeliveryMissionServiceResult.Success) { "Physical relay receipt was not recorded." }
        val parcel = parcels.findByDeliveryMissionId(missionId).single()
        return response(record(id, intent, before, mapOf("missionId" to missionId, "status" to result.mission.status.name,
            "lockerId" to lockerId, "parcelId" to parcel.id)), missionId)
    }
    private fun response(record: RelayAdministrationOperationRecord, missionId: String? = null): RelaySetupMutation {
        val after = state(record.afterJson)
        return RelaySetupMutation(requireNotNull(setup(record.relayPointId)), dto(record), after["parcelId"],
            missionId?.let { missions.findById(it).orElse(null)?.toSnapshot() })
    }
    private fun site(id: String) = points.lockForAdministration(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    private fun requireActor(id: String) {
        val actor = people.findById(id).orElse(null)
        if (actor == null || !actor.active || !actor.status.canAuthenticate() || actor.roles.none { it in RoleGroups.AdminOnly }) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }
    private fun details(r: RelaySiteDetailsRequest): Map<String, String?> {
        require(r.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true && r.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true) { "Valid latitude and longitude are required." }
        return mapOf("name" to ApiInputPolicy.requiredShortText(r.name.orEmpty(), "name", 120), "latitude" to r.latitude.toString(), "longitude" to r.longitude.toString(),
            "city" to ApiInputPolicy.optionalShortText(r.city, "city", 128), "neighborhood" to ApiInputPolicy.optionalShortText(r.neighborhood, "neighborhood", 128),
            "landmark" to ApiInputPolicy.optionalShortText(r.landmark, "landmark", 500))
    }
    private data class Intent(val action: String, val actor: String, val reason: String, val key: String, val hash: String)
    private fun intent(action: String, id: String, actor: String, reason: String?, key: String?, values: Map<String, String?>): Intent {
        val safeReason = ApiInputPolicy.requiredShortText(reason.orEmpty(), "reason", 1000)
        val retry = ApiInputPolicy.requiredIdempotencyKey(key.orEmpty())
        val raw = json.writeValueAsString(listOf(action, id, actor, safeReason, values.toSortedMap()))
        return Intent(action, actor, safeReason, retry, MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) })
    }
    private fun replay(intent: Intent): RelayAdministrationOperationRecord? = operations.findByIdempotencyKey(intent.key)?.also {
        if (it.actorUserId != intent.actor || it.intentHash != intent.hash) throw AdminPartyConflict("relay_retry_conflict", "Retry key belongs to another actor, target, action or intent.")
    }
    private fun record(id: String, intent: Intent, before: Map<String, String?>?, after: Map<String, String?>): RelayAdministrationOperationRecord {
        val record = RelayAdministrationOperationRecord(UUID.randomUUID().toString(), id, intent.action, intent.actor, intent.reason,
            intent.key, intent.hash, before?.let(json::writeValueAsString), json.writeValueAsString(after), now())
        entityManager.persist(record); entityManager.flush(); return record
    }
    private fun dto(r: RelayAdministrationOperationRecord) = RelayAdministrationOperationDto(r.id, r.relayPointId, r.action, r.actorUserId, r.reason,
        r.idempotencyKey, r.beforeJson?.let(::state), state(r.afterJson), r.createdAt)
    private fun state(raw: String): Map<String, String?> = json.readTree(raw).fields().asSequence().associate { it.key to it.value.takeUnless { n -> n.isNull }?.asText() }
    private fun siteState(s: RelayPointRecord) = mapOf("name" to s.name, "latitude" to s.latitude?.toString(), "longitude" to s.longitude?.toString(), "city" to s.city, "neighborhood" to s.neighborhood, "landmark" to s.landmark)
    private fun slotState(s: RelayLockerRecord) = mapOf("lockerId" to s.id, "lockerCode" to s.lockerCode, "status" to s.status.name)
    private fun expected(value: Instant?) = requireNotNull(value) { "The exact current server revision is required." }
    private fun fresh(actual: Instant, expected: Instant?) { if (actual != expected) throw AdminPartyConflict("relay_stale_revision", "Record changed. Refresh and confirm the current state before a new action.") }
    private fun now() = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private fun next(previous: Instant) = now().let { if (it > previous) it else previous.plusNanos(1000) }
}

@RestController @RequestMapping("/api/admin/web/relay-sites")
class AdminRelayAdministrationController(private val service: AdminRelayAdministrationService) {
    @GetMapping("/{id}/setup") fun setup(a: Authentication?, @PathVariable id: String) = adminControlRead(a) {
        service.setup(ApiInputPolicy.requiredIdentifier(id, "id"))?.let(::ok) ?: ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
    }
    @PostMapping fun create(a: Authentication?, @RequestBody r: RelaySiteDetailsRequest) = adminControlRead(a) { ok(service.create(requireNotNull(a).name, r)) }
    @PatchMapping("/{id}/details") fun update(a: Authentication?, @PathVariable id: String, @RequestBody r: RelaySiteDetailsRequest) = adminControlRead(a) { ok(service.update(id, requireNotNull(a).name, r)) }
    @PostMapping("/{id}/slots") fun slot(a: Authentication?, @PathVariable id: String, @RequestBody r: RelaySlotCreateRequest) = adminControlRead(a) { ok(service.createSlot(id, requireNotNull(a).name, r)) }
    @PutMapping("/{id}/slots/{slotId}/availability") fun availability(a: Authentication?, @PathVariable id: String, @PathVariable slotId: String, @RequestBody r: RelaySlotAvailabilityRequest) = adminControlRead(a) { ok(service.availability(id, slotId, requireNotNull(a).name, r)) }
    @PostMapping("/{id}/deposits") fun deposit(a: Authentication?, @PathVariable id: String, @RequestBody r: SequoRelayDepositRequest) = adminControlRead(a) { ok(service.deposit(id, requireNotNull(a).name, r)) }
    private fun ok(v: Any): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v)
}
@RestControllerAdvice(assignableTypes = [AdminRelayAdministrationController::class])
class AdminRelayAdministrationErrors {
    @ExceptionHandler(AdminPartyConflict::class) fun conflict(e: AdminPartyConflict): ResponseEntity<dev.orestegabo.sequo_api.api.ApiErrorResponse> = ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(dev.orestegabo.sequo_api.api.ApiErrorResponse(e.code, e.message))
    @ExceptionHandler(DataIntegrityViolationException::class) fun collision(): ResponseEntity<dev.orestegabo.sequo_api.api.ApiErrorResponse> = ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(dev.orestegabo.sequo_api.api.ApiErrorResponse("relay_write_conflict", "A concurrent change won. Refresh the recorded site and history before retrying."))
}
