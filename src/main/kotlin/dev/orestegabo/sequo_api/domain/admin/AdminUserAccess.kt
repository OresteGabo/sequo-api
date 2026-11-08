package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionMerchantRepository
import jakarta.persistence.*
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(name = "admin_access_scopes")
class AdminAccessScopeRecord(@Id val id: String, @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH)
interface AdminAccessScopeRepository : JpaRepository<AdminAccessScopeRecord, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AdminAccessScopeRecord s where s.id = :id")
    fun lockScope(id: String): AdminAccessScopeRecord?
}
/** Flyway creates GLOBAL in deployed databases; ORM-only local fixtures need the same mutex. */
@Component
class AdminAccessScopeInitializer(private val scopes: AdminAccessScopeRepository) : ApplicationRunner {
    override fun run(args: ApplicationArguments) { if (!scopes.existsById("GLOBAL")) scopes.saveAndFlush(AdminAccessScopeRecord("GLOBAL")) }
}

@Entity
@Table(name = "admin_access_changes", uniqueConstraints = [UniqueConstraint(name = "uk_admin_access_retry", columnNames = ["idempotency_key"])])
class AdminAccessChangeRecord(
    @Id val id: String,
    @Column(nullable = false, length = 32) val domain: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "merchant_id") val merchantId: String?,
    @Column(nullable = false, length = 32) val action: String,
    @Column(name = "actor_user_id", nullable = false) val actorUserId: String,
    @Column(name = "before_value", length = 2000) val beforeValue: String?,
    @Column(name = "after_value", length = 2000) val afterValue: String?,
    @Column(nullable = false, length = 1000) val reason: String,
    @Column(name = "idempotency_key", nullable = false, length = 128) val idempotencyKey: String,
    @Column(name = "intent_hash", nullable = false, length = 64) val intentHash: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)
interface AdminAccessChangeRepository : JpaRepository<AdminAccessChangeRecord, String> {
    fun findByIdempotencyKey(idempotencyKey: String): AdminAccessChangeRecord?
    fun findTop50ByUserIdOrderByCreatedAtDesc(userId: String): List<AdminAccessChangeRecord>
    fun findTop50ByMerchantIdOrderByCreatedAtDesc(merchantId: String): List<AdminAccessChangeRecord>
}
interface AdminMembershipRepository : JpaRepository<MerchantMembership, String> {
    fun findByMerchantIdOrderByCreatedAtAsc(merchantId: String): List<MerchantMembership>
    fun findByUserIdOrderByCreatedAtAsc(userId: String): List<MerchantMembership>
    fun findByUserIdAndMerchantIdAndRole(userId: String, merchantId: String, role: RoleCode): MerchantMembership?
}

data class AdminAccessUserDto(val id: String, val email: String?, val displayName: String?, val active: Boolean, val status: UserStatus, val roles: List<RoleCode>, val updatedAt: Instant)
data class AdminMembershipDto(val id: String, val userId: String, val merchantId: String, val role: RoleCode, val active: Boolean, val createdAt: Instant, val updatedAt: Instant)
data class AdminUserAccessDto(val user: AdminAccessUserDto, val memberships: List<AdminMembershipDto>, val history: List<AdminPartyChangeDto>)
data class AdminMerchantMembershipsDto(val merchantId: String, val updatedAt: Instant, val memberships: List<AdminMembershipDto>, val history: List<AdminPartyChangeDto>)
data class AdminAccessRequest(val active: Boolean?, val status: UserStatus?, val reason: String?, val expectedUpdatedAt: Instant?, val idempotencyKey: String?)
data class AdminRoleRequest(val reason: String?, val expectedUpdatedAt: Instant?, val idempotencyKey: String?)
data class AdminMembershipRequest(val active: Boolean?, val reason: String?, val expectedUpdatedAt: Instant?, val idempotencyKey: String?)

@Service
class AdminUserAccessService(
    private val users: UserRepository,
    private val memberships: AdminMembershipRepository,
    private val merchants: MerchantCommissionMerchantRepository,
    private val scopes: AdminAccessScopeRepository,
    private val changes: AdminAccessChangeRepository,
    private val sessions: RefreshSessionRepository,
    private val relayPoints: dev.orestegabo.sequo_api.domain.relay.RelayPointRepository,
    private val entityManager: EntityManager,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun user(id: String): AdminUserAccessDto? = users.findById(id).orElse(null)?.let(::userDto)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun merchant(id: String): AdminMerchantMembershipsDto? {
        val merchant = merchants.findById(id).orElse(null) ?: return null
        return AdminMerchantMembershipsDto(id, scopes.findById("merchant:$id").orElse(null)?.updatedAt ?: merchant.updatedAt,
            memberships.findByMerchantIdOrderByCreatedAtAsc(id).map(::membershipDto), changes.findTop50ByMerchantIdOrderByCreatedAtDesc(id).map(::changeDto))
    }

    @Transactional(rollbackFor = [Exception::class], isolation = Isolation.READ_COMMITTED)
    fun access(id: String, actor: String, request: AdminAccessRequest): AdminPartyMutationDto<AdminUserAccessDto> {
        val global = begin(actor, true)
        val active = requireNotNull(request.active) { "active is required." }
        val status = requireNotNull(request.status) { "status is required." }
        require(status in managedStatuses) { "Choose ACTIVE, LOCKED or SUSPENDED; this action cannot verify or delete an identity." }
        val command = command("USER_ACCESS", id, null, "SET_ACCESS", actor, "$active:$status", request.reason, request.expectedUpdatedAt, request.idempotencyKey)
        val target = target(id, actor)
        replay(command)?.let { return AdminPartyMutationDto(userDto(target), changeDto(it)) }
        fresh(target.updatedAt, command.expected)
        if (!active || !status.canAuthenticate()) { protectLastSuper(target); protectOwners(target); protectRelayOperator(target) }
        val before = accessValue(target)
        target.active = active; target.status = status
        invalidate(target)
        val at = tick(global, target.updatedAt)
        val operation = append(command, before, accessValue(target), at)
        return AdminPartyMutationDto(userDto(target), changeDto(operation))
    }

    @Transactional(rollbackFor = [Exception::class], isolation = Isolation.READ_COMMITTED)
    fun role(id: String, role: RoleCode, grant: Boolean, actor: String, request: AdminRoleRequest): AdminPartyMutationDto<AdminUserAccessDto> {
        val global = begin(actor, true)
        val command = command("USER_ACCESS", id, null, if (grant) "GRANT_ROLE" else "REVOKE_ROLE", actor, role.name, request.reason, request.expectedUpdatedAt, request.idempotencyKey)
        val target = target(id, actor)
        replay(command)?.let { return AdminPartyMutationDto(userDto(target), changeDto(it)) }
        fresh(target.updatedAt, command.expected)
        require(target.active && target.status.canAuthenticate()) { "Role changes require an active established account." }
        if (!grant) {
            if (role == RoleCode.SUPER_ADMIN) protectLastSuper(target)
            if (role == RoleCode.RELAY_PARTNER) protectRelayOperator(target)
            if (memberships.findByUserIdOrderByCreatedAtAsc(id).any { it.active && it.role == role })
                conflict("active_membership_role_required", "Deactivate the matching merchant memberships before revoking this role; membership-derived access cannot silently restore it.")
            require(target.roles.size > 1 || role !in target.roles) { "An account must retain at least one explicit role." }
        }
        val before = roleValue(target)
        if (grant) target.roles.add(role) else target.roles.remove(role)
        invalidate(target)
        val operation = append(command, before, roleValue(target), tick(global, target.updatedAt))
        return AdminPartyMutationDto(userDto(target), changeDto(operation))
    }

    @Transactional(rollbackFor = [Exception::class], isolation = Isolation.READ_COMMITTED)
    fun membership(merchantId: String, userId: String, role: RoleCode, actor: String, request: AdminMembershipRequest): AdminPartyMutationDto<AdminMerchantMembershipsDto> {
        val global = begin(actor, false)
        require(role == RoleCode.MERCHANT_OWNER || role == RoleCode.MERCHANT_STAFF) { "Membership role must be MERCHANT_OWNER or MERCHANT_STAFF." }
        val active = requireNotNull(request.active) { "active is required." }
        val command = command("MERCHANT_MEMBERSHIP", userId, merchantId, "SET_MEMBERSHIP", actor, "${role.name}:$active", request.reason, request.expectedUpdatedAt, request.idempotencyKey)
        val merchant = merchants.findById(merchantId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val target = target(userId, actor)
        replay(command)?.let { return AdminPartyMutationDto(requireNotNull(this.merchant(merchantId)), changeDto(it)) }
        require(target.active && target.status.canAuthenticate()) { "Membership changes require an active established account." }
        require(role in target.roles) { "Grant the matching account role separately before assigning merchant scope; membership does not promote an account." }
        require(merchant.status.equals("ACTIVE", true)) { "Membership changes require an active merchant." }
        val scopeId = "merchant:$merchantId"
        val scope = scopes.findById(scopeId).orElse(null)
        fresh(scope?.updatedAt ?: merchant.updatedAt, command.expected)
        val existing = memberships.findByUserIdAndMerchantIdAndRole(userId, merchantId, role)
        if (!active && existing?.active == true && role == RoleCode.MERCHANT_OWNER) protectOwner(merchantId, userId)
        val before = "merchant=$merchantId;role=$role;active=${existing?.active?.toString() ?: "ABSENT"}"
        val at = tick(global, maxOf(target.updatedAt, scope?.updatedAt ?: merchant.updatedAt))
        val record = existing ?: MerchantMembership(userId = userId, merchantId = merchantId, role = role, createdAt = at)
        record.active = active; record.updatedAt = at
        memberships.saveAndFlush(record)
        scopes.save(AdminAccessScopeRecord(scopeId, at))
        invalidate(target)
        val operation = append(command, before, "merchant=$merchantId;role=$role;active=$active", at)
        return AdminPartyMutationDto(requireNotNull(this.merchant(merchantId)), changeDto(operation))
    }

    private fun begin(actor: String, superOnly: Boolean): AdminAccessScopeRecord {
        val global = scopes.lockScope("GLOBAL") ?: conflict("access_guard_unavailable", "Access administration is unavailable until its migration is applied.")
        val user = users.lockCheckoutOwner(actor) ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "An active administrator account is required.")
        entityManager.refresh(user)
        val permitted = if (superOnly) setOf(RoleCode.SUPER_ADMIN) else RoleGroups.AdminOnly
        if (!user.active || !user.status.canAuthenticate() || user.roles.none { it in permitted }) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Current administrator authority is required.")
        return global
    }
    private fun target(id: String, actor: String): User {
        if (id == actor) conflict("self_access_change_denied", "Use another authorized administrator for changes to your own access or merchant scope.")
        val user = users.lockCheckoutOwner(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        entityManager.refresh(user)
        require(user.status in managedStatuses) { "This account is pending verification or deleted; its identity cannot be promoted or recreated here." }
        return user
    }
    private fun protectRelayOperator(user: User) {
        if (relayPoints.findByOperatorUserId(requireNotNull(user.id)).any { it.status == "ACTIVE" })
            conflict("relay_operator_unbind_required", "Remove or reassign the operator through the audited relay-site controls before disabling this account or revoking RELAY_PARTNER. No site assignment was changed.")
    }
    private fun protectLastSuper(user: User) {
        if (user.active && user.status.canAuthenticate() && RoleCode.SUPER_ADMIN in user.roles &&
            users.findAll().count { it.active && it.status.canAuthenticate() && RoleCode.SUPER_ADMIN in it.roles } <= 1)
            conflict("last_super_admin_required", "Keep another active SUPER_ADMIN before removing this account's access.")
    }
    private fun protectOwners(user: User) {
        if (user.active && user.status.canAuthenticate()) memberships.findByUserIdOrderByCreatedAtAsc(requireNotNull(user.id))
            .filter { it.active && it.role == RoleCode.MERCHANT_OWNER }.forEach { protectOwner(it.merchantId, requireNotNull(user.id)) }
    }
    private fun protectOwner(merchantId: String, removedUser: String) {
        if (merchants.findById(merchantId).orElse(null)?.status?.equals("ACTIVE", true) != true) return
        val another = memberships.findByMerchantIdOrderByCreatedAtAsc(merchantId).any { membership ->
            membership.active && membership.role == RoleCode.MERCHANT_OWNER && membership.userId != removedUser &&
                users.findById(membership.userId).orElse(null)?.let { it.active && it.status.canAuthenticate() && RoleCode.MERCHANT_OWNER in it.roles } == true
        }
        if (!another) conflict("sole_owner_review_required", "Add another active, authorized merchant owner before removing this owner's access. The merchant ownership identity is not changed here.")
    }
    private fun invalidate(user: User) {
        val now = Instant.now()
        user.updatedAt = maxOf(now.truncatedTo(ChronoUnit.MICROS), user.updatedAt.plusNanos(1000))
        users.saveAndFlush(user)
        val id = requireNotNull(user.id)
        check(users.rotateAuthorizationVersion(id, UUID.randomUUID().toString()) == 1)
        entityManager.refresh(user)
        entityManager.createQuery("update UserDevice d set d.revokedAt = :at where d.userId = :userId and d.revokedAt is null")
            .setParameter("at", now).setParameter("userId", id).executeUpdate()
        sessions.findAllByUserIdAndRevokedAtIsNull(id).forEach { it.revokedAt = now }
        // Version enforcement covers every access token, independently of whether it has a session ID.
    }
    private data class Command(val domain: String, val user: String, val merchant: String?, val action: String, val actor: String, val reason: String, val expected: Instant, val key: String, val hash: String)
    private fun command(domain: String, user: String, merchant: String?, action: String, actor: String, value: String, reason: String?, expected: Instant?, key: String?): Command {
        val safeReason = ApiInputPolicy.requiredLongText(reason.orEmpty(), "reason", 1000)
        val revision = requireNotNull(expected) { "expectedUpdatedAt is required." }
        val retryKey = ApiInputPolicy.requiredIdempotencyKey(key.orEmpty())
        val intent = listOf(domain, user, merchant.orEmpty(), action, actor, value, safeReason, revision.toString()).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(intent.toByteArray()).joinToString("") { "%02x".format(it) }
        return Command(domain, user, merchant, action, actor, safeReason, revision, retryKey, hash)
    }
    private fun replay(command: Command): AdminAccessChangeRecord? = changes.findByIdempotencyKey(command.key)?.also {
        if (it.actorUserId != command.actor || it.intentHash != command.hash) conflict("access_retry_conflict", "The retry key belongs to a different actor, target, action or reviewed intent.")
    }
    private fun fresh(actual: Instant, expected: Instant) { if (actual != expected) conflict("access_stale_revision", "This access record changed. Refresh and review before submitting a new action.") }
    private fun tick(global: AdminAccessScopeRecord, previous: Instant): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
        .let { maxOf(it, previous.plusNanos(1000), global.updatedAt.plusNanos(1000)) }.also { global.updatedAt = it }
    private fun append(c: Command, before: String?, after: String?, at: Instant) = changes.saveAndFlush(AdminAccessChangeRecord(UUID.randomUUID().toString(), c.domain, c.user, c.merchant, c.action, c.actor, before, after, c.reason, c.key, c.hash, at))
    private fun userDto(user: User) = AdminUserAccessDto(AdminAccessUserDto(requireNotNull(user.id), user.email, user.displayName, user.active, user.status, user.roles.sortedBy { it.name }, user.updatedAt),
        memberships.findByUserIdOrderByCreatedAtAsc(requireNotNull(user.id)).map(::membershipDto), changes.findTop50ByUserIdOrderByCreatedAtDesc(requireNotNull(user.id)).map(::changeDto))
    private fun membershipDto(m: MerchantMembership) = AdminMembershipDto(requireNotNull(m.id), m.userId, m.merchantId, m.role, m.active, m.createdAt, m.updatedAt)
    private fun changeDto(c: AdminAccessChangeRecord) = AdminPartyChangeDto(c.id, c.action, c.actorUserId, c.beforeValue, c.afterValue, c.reason, c.idempotencyKey, c.createdAt)
    private fun accessValue(u: User) = "active=${u.active};status=${u.status}"
    private fun roleValue(u: User) = u.roles.map { it.name }.sorted().joinToString(",")
    private fun conflict(code: String, message: String): Nothing = throw AdminPartyConflict(code, message)
    private val managedStatuses = setOf(UserStatus.ACTIVE, UserStatus.LOCKED, UserStatus.SUSPENDED)
}

@RestController
@RequestMapping("/api/admin/web")
class AdminUserAccessController(private val service: AdminUserAccessService) {
    @GetMapping("/users/{userId}/access")
    fun user(authentication: Authentication?, @PathVariable userId: String) = authorized(authentication) { found(service.user(id(userId))) }
    @PutMapping("/users/{userId}/access")
    fun access(authentication: Authentication?, @PathVariable userId: String, @RequestBody request: AdminAccessRequest) = authorized(authentication, true) { ok(service.access(id(userId), requireNotNull(authentication).name, request)) }
    @PutMapping("/users/{userId}/roles/{roleCode}")
    fun grant(authentication: Authentication?, @PathVariable userId: String, @PathVariable roleCode: RoleCode, @RequestBody request: AdminRoleRequest) = authorized(authentication, true) { ok(service.role(id(userId), roleCode, true, requireNotNull(authentication).name, request)) }
    @DeleteMapping("/users/{userId}/roles/{roleCode}")
    fun revoke(authentication: Authentication?, @PathVariable userId: String, @PathVariable roleCode: RoleCode, @RequestBody request: AdminRoleRequest) = authorized(authentication, true) { ok(service.role(id(userId), roleCode, false, requireNotNull(authentication).name, request)) }
    @GetMapping("/merchant-memberships")
    fun memberships(authentication: Authentication?, @RequestParam merchantId: String) = authorized(authentication) { found(service.merchant(id(merchantId))) }
    @PutMapping("/merchant-memberships/{merchantId}/{userId}/{roleCode}")
    fun membership(authentication: Authentication?, @PathVariable merchantId: String, @PathVariable userId: String, @PathVariable roleCode: RoleCode, @RequestBody request: AdminMembershipRequest) = authorized(authentication) { ok(service.membership(id(merchantId), id(userId), roleCode, requireNotNull(authentication).name, request)) }
    private fun id(value: String) = ApiInputPolicy.requiredIdentifier(value, "id")
    private fun ok(value: Any): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
    private fun found(value: Any?): ResponseEntity<Any> = value?.let(::ok) ?: ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
    private fun authorized(authentication: Authentication?, superOnly: Boolean = false, block: () -> ResponseEntity<Any>): ResponseEntity<Any> {
        if (authentication == null) return ResponseEntity.status(401).build()
        if (!authentication.hasAnyRole(if (superOnly) setOf(RoleCode.SUPER_ADMIN) else RoleGroups.AdminOnly)) return ResponseEntity.status(403).build()
        return block()
    }
}

@RestControllerAdvice(assignableTypes = [AdminUserAccessController::class])
class AdminUserAccessErrors {
    @ExceptionHandler(AdminPartyConflict::class)
    fun conflict(error: AdminPartyConflict): ResponseEntity<dev.orestegabo.sequo_api.api.ApiErrorResponse> =
        ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(dev.orestegabo.sequo_api.api.ApiErrorResponse(error.code, error.message))
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    fun concurrent(): ResponseEntity<dev.orestegabo.sequo_api.api.ApiErrorResponse> =
        ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(dev.orestegabo.sequo_api.api.ApiErrorResponse("access_write_conflict", "A concurrent action won. Refresh the access record and its history before continuing."))
}
