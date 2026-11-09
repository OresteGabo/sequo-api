package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.api.ApiErrorResponse
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commerce.MerchantStorefrontRecord
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionConfigurationService
import dev.orestegabo.sequo_api.domain.delivery.DeliveryVehicleType
import dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import jakarta.persistence.*
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

enum class AdminEnrollmentKind { MERCHANT, COURIER }
@Entity
@Table(name = "admin_enrollment_changes", uniqueConstraints = [UniqueConstraint(name = "uk_admin_enrollment_retry", columnNames = ["idempotency_key"])])
class AdminEnrollmentChangeRecord(
    @Id val id: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val kind: AdminEnrollmentKind,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "entity_id", nullable = false) val entityId: String,
    @Column(name = "actor_user_id", nullable = false) val actorUserId: String,
    @Column(nullable = false, length = 1000) val reason: String,
    @Column(name = "idempotency_key", nullable = false, length = 128) val idempotencyKey: String,
    @Column(name = "intent_hash", nullable = false, length = 64) val intentHash: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "recorded_name", length = 160) val recordedName: String? = null,
    @Enumerated(EnumType.STRING) @Column(name = "workforce_type", length = 32) val workforceType: DeliveryWorkforceType? = null,
    @Enumerated(EnumType.STRING) @Column(name = "vehicle_type", length = 32) val vehicleType: DeliveryVehicleType? = null,
)
interface AdminEnrollmentChangeRepository : JpaRepository<AdminEnrollmentChangeRecord, String> {
    fun findByIdempotencyKey(idempotencyKey: String): AdminEnrollmentChangeRecord?
    fun findTop50ByUserIdOrderByCreatedAtDesc(userId: String): List<AdminEnrollmentChangeRecord>
}
interface AdminEnrollmentMerchantRepository : JpaRepository<MerchantRecord, String> {
    fun findByOwnerUserId(ownerUserId: String): List<MerchantRecord>
}
interface AdminEnrollmentCourierRepository : JpaRepository<CourierRecord, String> {
    fun findByUserId(userId: String): CourierRecord?
}
data class AdminEnrollmentMerchantDto(val id: String, val name: String, val status: String, val createdAt: Instant, val updatedAt: Instant)
data class AdminEnrollmentCourierDto(val id: String, val status: String, val workforceType: String?, val vehicleType: String?, val createdAt: Instant, val updatedAt: Instant)
data class AdminEnrollmentOperationDto(val id: String, val kind: AdminEnrollmentKind, val userId: String, val entityId: String,
    val actorUserId: String, val reason: String, val idempotencyKey: String, val createdAt: Instant,
    val recordedName: String?, val workforceType: DeliveryWorkforceType?, val vehicleType: DeliveryVehicleType?)
data class AdminEnrollmentUserDto(val user: AdminAccessUserDto, val merchants: List<AdminEnrollmentMerchantDto>,
    val courier: AdminEnrollmentCourierDto?, val history: List<AdminEnrollmentOperationDto>)
data class AdminEnrollmentMutationDto(val record: AdminEnrollmentUserDto, val operation: AdminEnrollmentOperationDto)
data class AdminMerchantEnrollmentRequest(val userId: String?, val name: String?, val expectedUserUpdatedAt: Instant?, val reason: String?, val idempotencyKey: String?)
data class AdminCourierEnrollmentRequest(val userId: String?, val workforceType: DeliveryWorkforceType?, val vehicleType: DeliveryVehicleType?, val expectedUserUpdatedAt: Instant?, val reason: String?, val idempotencyKey: String?)

@Service
class AdminEnrollmentService(
    private val users: UserRepository, private val scopes: AdminAccessScopeRepository,
    private val merchants: AdminEnrollmentMerchantRepository, private val couriers: AdminEnrollmentCourierRepository,
    private val changes: AdminEnrollmentChangeRepository, private val commission: MerchantCommissionConfigurationService,
    private val sessions: RefreshSessionRepository, private val entityManager: EntityManager,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun user(id: String, actor: String): AdminEnrollmentUserDto? {
        requireActor(users.findById(actor).orElse(null))
        return users.findById(id).orElse(null)?.let(::snapshot)
    }

    @Transactional(rollbackFor = [Exception::class], isolation = Isolation.READ_COMMITTED)
    fun merchant(actor: String, request: AdminMerchantEnrollmentRequest): AdminEnrollmentMutationDto {
        val name = ApiInputPolicy.requiredShortText(request.name.orEmpty(), "name", 160)
        val command = command(AdminEnrollmentKind.MERCHANT, actor, request.userId, name, request.expectedUserUpdatedAt, request.reason, request.idempotencyKey)
        val target = begin(command)
        replay(command)?.let { return AdminEnrollmentMutationDto(snapshot(target), it.dto()) }
        eligible(target, command, RoleCode.MERCHANT_OWNER)
        if (merchants.findByOwnerUserId(command.user).any { it.name.equals(name, true) })
            conflict("merchant_already_enrolled", "This account already owns a merchant with that name. Open the existing record; no account or ownership was replaced.")
        val at = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val id = "merchant-${UUID.randomUUID()}"
        entityManager.persist(MerchantRecord(id, ownerUserId = command.user, name = name, commissionRateBps = commission.resolveRateBps(id), createdAt = at, updatedAt = at))
        // No fabricated rating, ETA, address, wallet or opening-hours claim.
        entityManager.persist(MerchantStorefrontRecord(merchantId = id, createdAt = at, updatedAt = at))
        entityManager.persist(MerchantMembership(userId = command.user, merchantId = id, role = RoleCode.MERCHANT_OWNER, createdAt = at, updatedAt = at))
        entityManager.flush()
        // New scope must be obtained by normal authentication, never added to an old token.
        invalidate(target, at)
        val operation = append(command, id, at, name = name)
        return AdminEnrollmentMutationDto(snapshot(target), operation.dto())
    }

    @Transactional(rollbackFor = [Exception::class], isolation = Isolation.READ_COMMITTED)
    fun courier(actor: String, request: AdminCourierEnrollmentRequest): AdminEnrollmentMutationDto {
        val workforce = requireNotNull(request.workforceType) { "workforceType is required." }
        val vehicle = requireNotNull(request.vehicleType) { "vehicleType is required." }
        val command = command(AdminEnrollmentKind.COURIER, actor, request.userId, "${workforce.name}:${vehicle.name}", request.expectedUserUpdatedAt, request.reason, request.idempotencyKey)
        val target = begin(command)
        replay(command)?.let { return AdminEnrollmentMutationDto(snapshot(target), it.dto()) }
        eligible(target, command, RoleCode.COURIER)
        if (couriers.findById(command.user).orElse(null)?.let { it.userId == null } == true)
            conflict("legacy_courier_link_review_required", "An unlinked legacy rider uses this account reference. Review and explicitly authorize its account mapping before enrolling another profile. No history or ownership was changed.")
        if (couriers.findByUserId(command.user) != null)
            conflict("courier_already_enrolled", "This account already has a rider profile. Use its existing workforce controls; no identity, history or wallet was replaced.")
        val at = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val id = "courier-${UUID.randomUUID()}"
        entityManager.persist(CourierRecord(id, userId = command.user,
            workforceType = if (workforce == DeliveryWorkforceType.SequoSalaried) "SEQUO_SALARIED" else "FREELANCER",
            vehicleType = vehicle.name, createdAt = at, updatedAt = at))
        val operation = append(command, id, at, workforce = workforce, vehicle = vehicle)
        return AdminEnrollmentMutationDto(snapshot(target), operation.dto())
    }

    private data class Command(val kind: AdminEnrollmentKind, val actor: String, val user: String, val expected: Instant, val reason: String, val key: String, val hash: String)
    private fun command(kind: AdminEnrollmentKind, actor: String, user: String?, value: String, expected: Instant?, reason: String?, key: String?): Command {
        val id = ApiInputPolicy.requiredIdentifier(user.orEmpty(), "userId")
        val revision = requireNotNull(expected) { "expectedUserUpdatedAt is required." }
        val safeReason = ApiInputPolicy.requiredLongText(reason.orEmpty(), "reason", 1000)
        val retry = ApiInputPolicy.requiredIdempotencyKey(key.orEmpty())
        val intent = listOf(kind.name, actor, id, value, revision.toString(), safeReason).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(intent.toByteArray()).joinToString("") { "%02x".format(it) }
        return Command(kind, actor, id, revision, safeReason, retry, hash)
    }
    private fun begin(command: Command): User {
        scopes.lockScope("GLOBAL") ?: conflict("enrollment_guard_unavailable", "Apply the access-administration migration before enrolling profiles.")
        val actor = users.lockCheckoutOwner(command.actor)
        actor?.let(entityManager::refresh)
        requireActor(actor)
        if (actor!!.roles.none { it in RoleGroups.StaffManagers }) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Staff manager or owner access is required for enrollment.")
        if (command.actor == command.user) conflict("self_enrollment_denied", "Use another authorized administrator to enroll a profile for your own account.")
        val target = users.lockCheckoutOwner(command.user) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        entityManager.refresh(target)
        if (RoleCode.SUPER_ADMIN !in actor.roles && target.roles.any { it in RoleGroups.ProtectedAdministrators })
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only an owner can manage an administrator's personnel profile.")
        return target
    }
    private fun requireActor(actor: User?) {
        if (actor == null || !actor.active || !actor.status.canAuthenticate() || actor.roles.none { it in RoleGroups.AdminOnly })
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Current active administrator authority is required.")
    }
    private fun eligible(target: User, command: Command, role: RoleCode) {
        if (target.updatedAt != command.expected) conflict("enrollment_stale_revision", "This account changed. Refresh its details and review a new enrollment.")
        require(target.active && target.status == UserStatus.ACTIVE) { "Select an existing active account that has completed normal authentication; enrollment cannot verify or recreate an identity." }
        require(role in target.roles) { "Grant $role separately through the SUPER_ADMIN account-role controls before enrolling this profile. Enrollment does not promote an account." }
    }
    private fun replay(c: Command): AdminEnrollmentChangeRecord? = changes.findByIdempotencyKey(c.key)?.also {
        if (it.intentHash != c.hash || it.actorUserId != c.actor) conflict("enrollment_retry_conflict", "This retry key belongs to another actor or reviewed enrollment. No existing profile was changed.")
    }
    private fun append(c: Command, id: String, at: Instant, name: String? = null, workforce: DeliveryWorkforceType? = null, vehicle: DeliveryVehicleType? = null) =
        changes.saveAndFlush(AdminEnrollmentChangeRecord(UUID.randomUUID().toString(), c.kind, c.user, id, c.actor, c.reason, c.key, c.hash, at, name, workforce, vehicle))
    private fun invalidate(user: User, at: Instant) {
        user.updatedAt = maxOf(at, user.updatedAt.plusNanos(1000))
        users.saveAndFlush(user)
        check(users.rotateAuthorizationVersion(requireNotNull(user.id), UUID.randomUUID().toString()) == 1)
        entityManager.refresh(user)
        entityManager.createQuery("update UserDevice d set d.revokedAt = :at where d.userId = :id and d.revokedAt is null")
            .setParameter("at", at).setParameter("id", user.id).executeUpdate()
        sessions.findAllByUserIdAndRevokedAtIsNull(requireNotNull(user.id)).forEach { it.revokedAt = at }
    }
    private fun snapshot(user: User): AdminEnrollmentUserDto {
        val id = requireNotNull(user.id)
        return AdminEnrollmentUserDto(AdminAccessUserDto(id, user.email, user.displayName, user.active, user.status, user.roles.sortedBy { it.name }, user.updatedAt),
            merchants.findByOwnerUserId(id).sortedBy { it.id }.map { AdminEnrollmentMerchantDto(it.id, it.name, it.status, it.createdAt, it.updatedAt) },
            couriers.findByUserId(id)?.let { AdminEnrollmentCourierDto(it.id, it.status, it.workforceType, it.vehicleType, it.createdAt, it.updatedAt) },
            changes.findTop50ByUserIdOrderByCreatedAtDesc(id).map { it.dto() })
    }
    private fun AdminEnrollmentChangeRecord.dto() = AdminEnrollmentOperationDto(id, kind, userId, entityId, actorUserId, reason, idempotencyKey, createdAt, recordedName, workforceType, vehicleType)
    private fun conflict(code: String, message: String): Nothing = throw AdminPartyConflict(code, message)
}

@RestController
@RequestMapping("/api/admin/web/enrollment")
class AdminEnrollmentController(private val service: AdminEnrollmentService) {
    @GetMapping("/users/{userId}")
    fun user(authentication: Authentication?, @PathVariable userId: String) = authorized(authentication) {
        service.user(ApiInputPolicy.requiredIdentifier(userId, "userId"), authentication!!.name)?.let(::ok)
            ?: ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build<Any>()
    }
    @PostMapping("/merchants")
    fun merchant(authentication: Authentication?, @RequestBody request: AdminMerchantEnrollmentRequest) = authorized(authentication) { ok(service.merchant(authentication!!.name, request)) }
    @PostMapping("/couriers")
    fun courier(authentication: Authentication?, @RequestBody request: AdminCourierEnrollmentRequest) = authorized(authentication) { ok(service.courier(authentication!!.name, request)) }
    private fun ok(body: Any): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    private fun authorized(authentication: Authentication?, action: () -> ResponseEntity<Any>): ResponseEntity<Any> = when {
        authentication == null -> ResponseEntity.status(401).build()
        !authentication.hasAnyRole(RoleGroups.AdminOnly) -> ResponseEntity.status(403).build()
        else -> action()
    }
}
@RestControllerAdvice(assignableTypes = [AdminEnrollmentController::class])
class AdminEnrollmentErrors {
    @ExceptionHandler(AdminPartyConflict::class)
    fun conflict(error: AdminPartyConflict) = ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(ApiErrorResponse(error.code, error.message))
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    fun race() = ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(ApiErrorResponse("enrollment_write_conflict", "A concurrent enrollment won. Refresh this account and its enrollment history; do not recreate or replace a profile."))
}
