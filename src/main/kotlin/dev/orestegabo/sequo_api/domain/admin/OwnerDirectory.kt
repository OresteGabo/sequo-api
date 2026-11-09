package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.relay.*
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.*
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

@Entity @Table(name = "owner_directory_operations")
class OwnerDirectoryOperation(
    @Id val id: String = UUID.randomUUID().toString(),
    @Column(nullable = false) val kind: String,
    @Column(name = "entity_id", nullable = false) val entityId: String,
    @Column(name = "actor_user_id", nullable = false) val actorUserId: String,
    @Column(nullable = false) val action: String,
    @Column(name = "before_json", columnDefinition = "text") val beforeJson: String?,
    @Column(name = "after_json", nullable = false, columnDefinition = "text") val afterJson: String,
    @Column(nullable = false, length = 1000) val reason: String,
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128) val idempotencyKey: String,
    @Column(name = "intent_hash", nullable = false, length = 64) val intentHash: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)
interface OwnerDirectoryRepository : JpaRepository<OwnerDirectoryOperation, String> {
    fun findByIdempotencyKey(idempotencyKey: String): OwnerDirectoryOperation?
    fun findTop50ByKindAndEntityIdOrderByCreatedAtDesc(kind: String, entityId: String): List<OwnerDirectoryOperation>
}
data class OwnerAccountCreate(val email: String?, val displayName: String?, val roleCode: RoleCode?, val reason: String?, val idempotencyKey: String?, val phoneNumber: String? = null)
data class OwnerDirectoryEdit(val name: String? = null, val vehicleType: DeliveryVehicleType? = null, val status: String? = null,
    val expectedUpdatedAt: Instant?, val reason: String?, val idempotencyKey: String?)
data class OwnerDirectoryDetail(val record: Map<String, Any?>, val history: List<OwnerDirectoryOperation>)
data class OwnerDirectoryResult(val record: Map<String, Any?>, val operation: OwnerDirectoryOperation)

@Service
class OwnerDirectoryService(private val users: UserRepository, private val auth: AuthService, private val scopes: AdminAccessScopeRepository,
    private val merchants: CommerceMerchantRepository, private val couriers: SettlementCourierRecordRepository,
    private val sites: RelayPointRepository, private val missions: DeliveryMissionRepository,
    private val subOrders: MerchantSubOrderRepository, private val parcels: RelayParcelRecordRepository,
    private val pending: PendingPaymentCheckoutRepository, private val operations: OwnerDirectoryRepository,
    private val accessChanges: AdminAccessChangeRepository, private val em: EntityManager,
) {
    private val json = ObjectMapper()
    private val activeMissions = DeliveryMissionRecordStatus.entries.toSet() - setOf(DeliveryMissionRecordStatus.CANCELLED,
        DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, DeliveryMissionRecordStatus.RELEASED_BY_RELAY)
    private val openSubOrders = listOf(MerchantSubOrderStatus.MERCHANT_PENDING, MerchantSubOrderStatus.ACCEPTED, MerchantSubOrderStatus.PREPARING, MerchantSubOrderStatus.PACKED_READY)
    private data class Intent(val actor: String, val kind: String, val action: String, val key: String, val hash: String, val reason: String)
    private fun owner(actor: String) {
        scopes.lockScope("GLOBAL") ?: conflict("directory_guard_unavailable", "Access guard is unavailable.")
        val user = users.lockCheckoutOwner(actor) ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        em.refresh(user)
        if (!user.active || !user.status.canAuthenticate() || RoleCode.SUPER_ADMIN !in user.roles) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }
    private fun intent(actor: String, kind: String, action: String, request: Any, reason: String?, key: String?): Intent {
        require(kind in setOf("accounts", "merchants", "couriers", "hubs")) { "Unknown directory." }
        val why = ApiInputPolicy.requiredLongText(reason.orEmpty(), "reason", 1000)
        val retry = ApiInputPolicy.requiredIdempotencyKey(key.orEmpty())
        val hash = MessageDigest.getInstance("SHA-256").digest("$actor\u0000$kind\u0000$action\u0000$why\u0000${json.writeValueAsString(request)}".toByteArray()).joinToString("") { "%02x".format(it) }
        return Intent(actor, kind, action, retry, hash, why)
    }
    private fun replay(i: Intent): OwnerDirectoryResult? = operations.findByIdempotencyKey(i.key)?.let {
        if (it.actorUserId != i.actor || it.intentHash != i.hash) conflict("directory_retry_conflict", "This retry key belongs to another reviewed action.")
        OwnerDirectoryResult(snapshot(it.kind, it.entityId), it)
    }
    @Transactional
    fun create(actor: String, r: OwnerAccountCreate): OwnerDirectoryResult {
        owner(actor)
        val email = r.email?.takeIf { it.isNotBlank() }?.let { ApiInputPolicy.normalizedIdentityEmail(it) }
        val phone = r.phoneNumber?.takeIf { it.isNotBlank() }?.let(auth::normalizePhone)
        require(email != null || phone != null) { "Provide an approved email or phone number." }
        val name = ApiInputPolicy.requiredShortText(r.displayName.orEmpty(), "displayName", 160)
        val role = requireNotNull(r.roleCode) { "Choose a personnel role." }
        require(role in RoleGroups.PersonnelRoles + RoleGroups.AdminOnly) { "Choose an assignable personnel role." }
        require(role !in RoleGroups.AdminOnly || email != null) { "Administrator accounts need an approved email for website sign-in." }
        val i = intent(actor, "accounts", "CREATE_ACCOUNT", listOf(email, phone, name, role.name), r.reason, r.idempotencyKey)
        replay(i)?.let { return it }
        if ((email != null && users.findByEmailIgnoreCase(email) != null) || (phone != null && users.findByPhoneNumber(phone) != null)) conflict("account_exists", "An account already uses this email or phone number. Load it in Users and review its existing roles.")
        val user = users.saveAndFlush(User(email = email, phoneNumber = phone, name = name, provider = AuthProvider.EMAIL,
            roles = mutableSetOf(RoleCode.CUSTOMER, role), authorizationVersion = UUID.randomUUID().toString()))
        val id = requireNotNull(user.id)
        accessChanges.save(AdminAccessChangeRecord(UUID.randomUUID().toString(), "USER_ACCESS", id, null, "GRANT_ROLE", actor,
            null, user.roles.map { it.name }.sorted().joinToString(","), i.reason, i.key, i.hash, Instant.now()))
        return append(i, id, null, snapshot("accounts", id))
    }
    @Transactional(readOnly = true)
    fun detail(kind: String, id: String) = OwnerDirectoryDetail(snapshot(kind, id), operations.findTop50ByKindAndEntityIdOrderByCreatedAtDesc(kind, id))

    @Transactional
    fun edit(actor: String, kind: String, id: String, r: OwnerDirectoryEdit, archive: Boolean = false): OwnerDirectoryResult {
        owner(actor)
        val i = intent(actor, kind, if (archive) "ARCHIVE:$id" else "EDIT:$id", listOf(r.name, r.vehicleType?.name, r.status, r.expectedUpdatedAt?.toString()), r.reason, r.idempotencyKey)
        replay(i)?.let { return it }
        val entity: Any = when(kind) {
            "accounts" -> users.lockCheckoutOwner(id)
            "merchants" -> merchants.lockForCatalogCreate(id)
            "couriers" -> couriers.lockForAdministration(id)
            "hubs" -> sites.lockForAdministration(id)
            else -> null
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        em.refresh(entity)
        val before = snapshot(kind, id)
        if (before["updatedAt"] != r.expectedUpdatedAt?.toString()) conflict("directory_stale_revision", "Record changed. Refresh and review the current details.")
        val status = if (archive) "INACTIVE" else r.status
        require(status == null || status in setOf("ACTIVE", "INACTIVE")) { "Unsupported profile status." }
        require(r.name != null || r.vehicleType != null || status != null) { "Choose a change." }
        val name = r.name?.let { ApiInputPolicy.requiredShortText(it, "name", 160) }
        if (status == "INACTIVE" && before["status"] != "INACTIVE") ensureIdle(kind, id)
        if (status == "ACTIVE" && before["status"] != "ACTIVE") {
            val (userId, role) = when(kind) {
                "merchants" -> merchants.findById(id).orElseThrow().ownerUserId to RoleCode.MERCHANT_OWNER
                "couriers" -> couriers.findById(id).orElseThrow().userId to RoleCode.COURIER
                "hubs" -> sites.findById(id).orElseThrow().operatorUserId to RoleCode.RELAY_PARTNER
                else -> null to RoleCode.CUSTOMER
            }
            val person = userId?.let { users.findById(it).orElse(null) }
            if (person == null || !person.active || !person.status.canAuthenticate() || role !in person.roles)
                conflict("directory_personnel_unavailable", "Assign an active authorized account before restoring this profile.")
        }
        val previous = Instant.parse(before.getValue("updatedAt").toString())
        val at = maxOf(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), previous.plusNanos(1000))
        when (entity) {
            is User -> { require(!archive && status == null && r.vehicleType == null) { "Use account access controls to suspend personnel or remove roles." }; entity.name = requireNotNull(name) }
            is dev.orestegabo.sequo_api.domain.party.MerchantRecord -> { require(r.vehicleType == null); name?.let { entity.name = it }; status?.let { entity.status = it }; entity.updatedAt = at }
            is dev.orestegabo.sequo_api.domain.party.CourierRecord -> { require(name == null) { "Edit the linked account to change the rider name." }; r.vehicleType?.let { if (before["vehicleType"] != it.name) ensureIdle(kind, id); entity.vehicleType = it.name }; status?.let { entity.status = it }; entity.updatedAt = at }
            is dev.orestegabo.sequo_api.domain.party.RelayPointRecord -> { require(name == null && r.vehicleType == null) { "Use hub setup to edit its name and address." }; status?.let { entity.status = it }; entity.updatedAt = at }
        }
        em.flush()
        return append(i, id, before, snapshot(kind, id))
    }
    private fun ensureIdle(kind: String, id: String) {
        if (kind == "accounts") throw IllegalArgumentException("Use account access controls.")
        val busy = when(kind) {
            "merchants" -> subOrders.findByMerchantIdAndStatusInOrderByUpdatedAtDesc(id, openSubOrders).isNotEmpty()
            "couriers" -> missions.findByCourierIdOrderByUpdatedAtDesc(id).any { it.status in setOf(DeliveryMissionRecordStatus.CREATED, DeliveryMissionRecordStatus.OFFERED_TO_COURIER, DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER, DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER, DeliveryMissionRecordStatus.PROBLEM_REPORTED) }
            "hubs" -> parcels.findByRelayPointIdOrderByUpdatedAtDesc(id).any { it.status !in setOf(RelayParcelStatus.PickedUp, RelayParcelStatus.CollectedBySequo, RelayParcelStatus.ReturnedToSeller) } ||
                missions.existsByRelayPointIdAndStatusIn(id, activeMissions)
            else -> true
        }
        if (busy) conflict("directory_work_in_progress", "Resolve current orders, missions or parcel custody before removing this profile or changing its vehicle.")
        if (kind in setOf("merchants", "hubs") && pending.findByStatus(PendingPaymentCheckoutStatus.AWAITING_WEBHOOK).any {
            val request = runCatching { json.readPendingRequest(it.requestPayload) }.getOrElse { conflict("directory_checkout_review_required", "An older pending checkout needs review before profile removal.") }
            if (kind == "hubs") request.relayDestination?.id == id else request.lines.any { line -> line.sellerId == id }
        }) conflict("directory_checkout_in_progress", "Resolve pending customer checkouts before removing this profile.")
    }
    private fun snapshot(kind: String, id: String): Map<String, Any?> = when(kind) {
        "accounts" -> users.findById(id).orElse(null)?.let { mapOf("id" to id, "name" to it.name, "email" to it.email, "phoneNumber" to it.phoneNumber, "status" to it.status.name, "roles" to it.roles.map { r -> r.name }.sorted(), "updatedAt" to it.updatedAt.toString()) }
        "merchants" -> merchants.findById(id).orElse(null)?.let { mapOf("id" to id, "name" to it.name, "status" to it.status, "ownerUserId" to it.ownerUserId, "updatedAt" to it.updatedAt.toString()) }
        "couriers" -> couriers.findById(id).orElse(null)?.let { mapOf("id" to id, "userId" to it.userId, "vehicleType" to it.vehicleType, "status" to it.status, "updatedAt" to it.updatedAt.toString()) }
        "hubs" -> sites.findById(id).orElse(null)?.let { mapOf("id" to id, "name" to it.name, "status" to it.status, "updatedAt" to it.updatedAt.toString()) }
        else -> throw IllegalArgumentException("Unknown directory.")
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    private fun append(i: Intent, id: String, before: Map<String, Any?>?, after: Map<String, Any?>): OwnerDirectoryResult {
        val operation = operations.saveAndFlush(OwnerDirectoryOperation(kind = i.kind, entityId = id, actorUserId = i.actor,
            action = i.action.substringBefore(':'), beforeJson = before?.let(json::writeValueAsString), afterJson = json.writeValueAsString(after), reason = i.reason, idempotencyKey = i.key, intentHash = i.hash))
        return OwnerDirectoryResult(after, operation)
    }
    private fun conflict(code: String, message: String): Nothing = throw AdminPartyConflict(code, message)
}

@RestController @RequestMapping("/api/admin/web/directory")
class OwnerDirectoryController(private val service: OwnerDirectoryService) {
    @GetMapping("/{kind}/{id}") fun detail(a: Authentication?, @PathVariable kind: String, @PathVariable id: String) = authorized(a) { service.detail(kind, id) }
    @PostMapping("/accounts") fun create(a: Authentication?, @RequestBody r: OwnerAccountCreate) = authorized(a) { service.create(requireNotNull(a).name, r) }
    @PatchMapping("/{kind}/{id}") fun edit(a: Authentication?, @PathVariable kind: String, @PathVariable id: String, @RequestBody r: OwnerDirectoryEdit) = authorized(a) { service.edit(requireNotNull(a).name, kind, id, r) }
    @DeleteMapping("/{kind}/{id}") fun archive(a: Authentication?, @PathVariable kind: String, @PathVariable id: String, @RequestBody r: OwnerDirectoryEdit) = authorized(a) { service.edit(requireNotNull(a).name, kind, id, r, true) }
    private fun authorized(a: Authentication?, action: () -> Any): ResponseEntity<Any> = when {
        a == null -> ResponseEntity.status(401).build()
        !a.hasAnyRole(setOf(RoleCode.SUPER_ADMIN)) -> ResponseEntity.status(403).build()
        else -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action())
    }
    @ExceptionHandler(AdminPartyConflict::class) fun conflict(e: AdminPartyConflict) = ResponseEntity.status(409).body(dev.orestegabo.sequo_api.api.ApiErrorResponse(e.code, e.message))
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class) fun duplicate() = ResponseEntity.status(409).body(dev.orestegabo.sequo_api.api.ApiErrorResponse("directory_write_conflict", "A concurrent change won. Refresh the account and its history."))
}
