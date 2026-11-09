package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.delivery.DeliveryVehicleType
import dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType
import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry
import jakarta.persistence.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.*
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class PartnerKind { VENDOR, RIDER, HUB }
enum class PartnerApplicationState { PENDING, APPROVED, REJECTED }
data class PartnerDetails(val displayName: String = "", val email: String? = null, val phoneNumber: String? = null,
    val businessName: String? = null, val city: String = "", val neighborhood: String = "", val landmark: String? = null,
    val latitude: Double? = null, val longitude: Double? = null, val vehicleType: DeliveryVehicleType? = null)
data class PartnerSubmission(val kind: PartnerKind?, val details: PartnerDetails, val acknowledged: Boolean = false, val idempotencyKey: String?)
data class PartnerPreviewRequest(val kind: PartnerKind?, val details: PartnerDetails)
data class PartnerRegisterRequest(val details: PartnerDetails, val reason: String?, val idempotencyKey: String?,
    val expectedUserUpdatedAt: Instant? = null, val workforceType: DeliveryWorkforceType? = null,
    val expectedApplicationUpdatedAt: Instant? = null)
data class PartnerRejectRequest(val reason: String?, val idempotencyKey: String?, val expectedApplicationUpdatedAt: Instant?)
data class PartnerMatchedUser(val id: String, val publicReference: String, val displayName: String?, val email: String?, val phoneNumber: String?, val roles: List<RoleCode>, val updatedAt: Instant)
data class PartnerPreview(val details: PartnerDetails, val existingAccount: PartnerMatchedUser?)
data class PartnerReceipt(val id: String, val state: String = "RECEIVED")

@Entity @Table(name = "partner_applications")
class PartnerApplicationRecord(@Id val id: String = UUID.randomUUID().toString(),
    @Enumerated(EnumType.STRING) @Column(nullable = false) val kind: PartnerKind,
    @Column(name = "submitted_json", nullable = false, columnDefinition = "text") val submittedJson: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var state: PartnerApplicationState = PartnerApplicationState.PENDING,
    @Column(name = "submitted_at", nullable = false) val submittedAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = submittedAt,
    @Column(name = "reviewer_user_id") var reviewerUserId: String? = null,
    @Column(name = "review_reason", length = 1000) var reviewReason: String? = null,
    @Column(name = "operation_id") var operationId: String? = null,
    @Version @Column(nullable = false) var version: Long = 0)
interface PartnerApplicationRepository : JpaRepository<PartnerApplicationRecord, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from PartnerApplicationRecord a where a.id = :id")
    fun lockForReview(id: String): PartnerApplicationRecord?
    fun findByKindAndStateOrderBySubmittedAtAsc(kind: PartnerKind, state: PartnerApplicationState, page: org.springframework.data.domain.Pageable): List<PartnerApplicationRecord>
}
@Entity @Table(name = "partner_onboarding_operations")
class PartnerOnboardingOperation(@Id val id: String = UUID.randomUUID().toString(),
    @Column(name = "actor_user_id") val actorUserId: String?, @Column(name = "application_id") val applicationId: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val kind: PartnerKind,
    @Column(nullable = false) val action: String,
    @Column(name = "user_id") val userId: String?, @Column(name = "entity_id") val entityId: String?,
    @Column(name = "details_json", nullable = false, columnDefinition = "text") val detailsJson: String,
    @Column(nullable = false, length = 1000) val reason: String,
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160) val idempotencyKey: String,
    @Column(name = "intent_hash", nullable = false, length = 64) val intentHash: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now())
interface PartnerOnboardingRepository : JpaRepository<PartnerOnboardingOperation, String> {
    fun findByIdempotencyKey(idempotencyKey: String): PartnerOnboardingOperation?
    fun findTop50ByApplicationIdOrderByCreatedAtAsc(applicationId: String): List<PartnerOnboardingOperation>
}
data class PartnerOperationDto(val id: String, val kind: PartnerKind, val action: String, val actorUserId: String?, val actorReference: String?,
    val applicationId: String?, val userId: String?, val userReference: String?, val entityId: String?, val entityReference: String?,
    val details: PartnerDetails, val reason: String, val createdAt: Instant)
data class PartnerApplicationDto(val id: String, val kind: PartnerKind, val state: PartnerApplicationState, val details: PartnerDetails,
    val submittedAt: Instant, val updatedAt: Instant, val reviewReason: String?, val reviewerUserId: String?, val operation: PartnerOperationDto?,
    val history: List<PartnerOperationDto>)

@Service
class PartnerOnboardingService(private val applications: PartnerApplicationRepository, private val operations: PartnerOnboardingRepository,
    private val users: UserRepository, private val auth: AuthService, private val scopes: AdminAccessScopeRepository,
    private val enrollment: AdminEnrollmentService, private val relaySetup: AdminRelayAdministrationService,
    private val parties: AdminPartyControlService, private val accessChanges: AdminAccessChangeRepository,
    private val references: PublicReferenceRegistry, private val em: EntityManager) {
    private val json = ObjectMapper()
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun key(value: String?) = ApiInputPolicy.requiredIdempotencyKey(value.orEmpty())
    private fun why(value: String?) = ApiInputPolicy.requiredLongText(value.orEmpty(), "reason", 1000)
    private fun role(kind: PartnerKind) = when(kind) { PartnerKind.VENDOR -> RoleCode.MERCHANT_OWNER; PartnerKind.RIDER -> RoleCode.COURIER; PartnerKind.HUB -> RoleCode.RELAY_PARTNER }
    private fun clean(kind: PartnerKind, r: PartnerDetails): PartnerDetails {
        val email = r.email?.takeIf(String::isNotBlank)?.let { ApiInputPolicy.normalizedIdentityEmail(it) }
        val phone = r.phoneNumber?.takeIf(String::isNotBlank)?.let(auth::normalizePhone)
        require(email != null || phone != null) { "Provide an email or international phone number." }
        require((r.latitude == null) == (r.longitude == null)) { "Provide both coordinates or neither." }
        require(r.latitude == null || r.latitude.isFinite() && r.latitude in -90.0..90.0) { "Invalid latitude." }
        require(r.longitude == null || r.longitude.isFinite() && r.longitude in -180.0..180.0) { "Invalid longitude." }
        if (kind == PartnerKind.RIDER) requireNotNull(r.vehicleType) { "Select the rider vehicle." }
        return PartnerDetails(ApiInputPolicy.requiredShortText(r.displayName, "displayName", 160), email, phone,
            if (kind == PartnerKind.RIDER) null else ApiInputPolicy.requiredShortText(r.businessName.orEmpty(), "businessName", if (kind == PartnerKind.HUB) 120 else 160),
            ApiInputPolicy.requiredShortText(r.city, "city", 128), ApiInputPolicy.requiredShortText(r.neighborhood, "neighborhood", 128),
            ApiInputPolicy.optionalShortText(r.landmark, "landmark", 500), r.latitude, r.longitude,
            if (kind == PartnerKind.RIDER) r.vehicleType else null)
    }
    private fun actor(id: String): User {
        scopes.lockScope("GLOBAL") ?: conflict("partner_guard_unavailable", "Personnel management is unavailable.")
        val user = users.lockCheckoutOwner(id) ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        em.refresh(user)
        if (!user.active || !user.status.canAuthenticate() || user.roles.none { it in RoleGroups.StaffManagers }) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return user
    }
    private fun match(details: PartnerDetails, actor: User): User? {
        val candidates = listOfNotNull(details.email?.let(users::findByEmailIgnoreCase), details.phoneNumber?.let(users::findByPhoneNumber)).distinctBy { it.id }
        if (candidates.size > 1) conflict("partner_identity_conflict", "These contacts belong to different accounts. They cannot be merged here.")
        return candidates.singleOrNull()?.also {
            if ((details.email != null && it.email?.let(ApiInputPolicy::normalizedIdentityEmail) != details.email) ||
                (details.phoneNumber != null && it.phoneNumber != details.phoneNumber)) conflict("partner_contact_change_requires_verification", "Use the existing account's verified contact details. A new login contact must be verified through the identity flow.")
            if (it.id == actor.id) conflict("partner_self_approval_denied", "Another authorized reviewer must register your own partner profile.")
            if (!it.active || it.status != UserStatus.ACTIVE) conflict("partner_account_unavailable", "This account needs an access or identity review before partner onboarding.")
            if (RoleCode.SUPER_ADMIN !in actor.roles && it.roles.any { r -> r in RoleGroups.ProtectedAdministrators }) throw ResponseStatusException(HttpStatus.FORBIDDEN, "An Owner must review this administrator account.")
        }
    }
    @Transactional(readOnly = true) fun canReview(id: String): Boolean = users.findById(id).orElse(null)?.let { it.active && it.status.canAuthenticate() && it.roles.any { role -> role in RoleGroups.StaffManagers } } == true
    @Transactional fun preview(actorId: String, kind: PartnerKind, details: PartnerDetails): PartnerPreview {
        val actor = actor(actorId); val clean = clean(kind, details)
        return PartnerPreview(clean, match(clean, actor)?.let { PartnerMatchedUser(it.id!!, references.reference(PublicReferenceKind.USER, requireNotNull(it.id)), it.name, it.email, it.phoneNumber, it.roles.sortedBy(RoleCode::name), it.updatedAt) })
    }
    @Transactional fun submit(r: PartnerSubmission): PartnerReceipt {
        require(r.acknowledged) { "Confirm that SEQUO may review and contact you about your application." }
        val kind = requireNotNull(r.kind) { "Choose a partner type." }; val details = clean(kind, r.details)
        val retry = "public:" + key(r.idempotencyKey); val digest = hash(kind.name + json.writeValueAsString(details))
        operations.findByIdempotencyKey(retry)?.let {
            if (it.action != "SUBMIT" || it.intentHash != digest) conflict("partner_retry_conflict", "This saved submission differs. Use the original details for a retry.")
            return PartnerReceipt(requireNotNull(it.applicationId))
        }
        val application = applications.saveAndFlush(PartnerApplicationRecord(kind = kind, submittedJson = json.writeValueAsString(details)))
        operations.saveAndFlush(PartnerOnboardingOperation(actorUserId = null, applicationId = application.id, kind = kind, action = "SUBMIT", userId = null, entityId = null,
            detailsJson = application.submittedJson, reason = "Applicant submitted details for review; no account authority granted. Privacy notice 2026-11-09 acknowledged.", idempotencyKey = retry, intentHash = digest))
        return PartnerReceipt(application.id)
    }
    @Transactional(readOnly = true) fun list(kind: PartnerKind, state: PartnerApplicationState, limit: Int) = applications.findByKindAndStateOrderBySubmittedAtAsc(kind, state, PageRequest.of(0, limit.coerceIn(1, 100))).map(::dto)
    @Transactional(readOnly = true) fun application(id: String) = applications.findById(id).orElse(null)?.let(::dto) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    @Transactional(readOnly = true) fun operation(retry: String) = operations.findByIdempotencyKey("staff:" + key(retry))?.let(::dto)

    @Transactional fun register(actorId: String, kind: PartnerKind, r: PartnerRegisterRequest, applicationId: String? = null): PartnerOperationDto {
        val actor = actor(actorId); val details = clean(kind, r.details); val reason = why(r.reason); val retry = "staff:" + key(r.idempotencyKey)
        val workforce = r.workforceType ?: DeliveryWorkforceType.Freelancer
        val digest = hash(json.writeValueAsString(listOf(actorId, kind.name, details, reason, r.expectedUserUpdatedAt?.toString(), workforce.name, applicationId, r.expectedApplicationUpdatedAt?.toString())))
        operations.findByIdempotencyKey(retry)?.let { if (it.actorUserId != actorId || it.intentHash != digest) conflict("partner_retry_conflict", "This retry belongs to another reviewed action."); return dto(it) }
        val application = applicationId?.let { applications.lockForReview(it) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (application != null) {
            require(application.kind == kind) { "Application type cannot be changed." }
            if (application.state != PartnerApplicationState.PENDING || application.updatedAt != r.expectedApplicationUpdatedAt) conflict("partner_application_changed", "This application has already changed. Refresh its current review.")
        }
        val matched = match(details, actor)
        if (matched?.updatedAt != r.expectedUserUpdatedAt) conflict("partner_account_changed", "The account match changed. Review the current preview before confirming.")
        val target = if (matched != null) users.lockCheckoutOwner(matched.id!!)!!.also { em.refresh(it); if (it.updatedAt != r.expectedUserUpdatedAt) conflict("partner_account_changed", "The account changed. Refresh the preview.") }
            else users.saveAndFlush(User(email = details.email, phoneNumber = details.phoneNumber, name = details.displayName, authorizationVersion = UUID.randomUUID().toString()))
        val uid = target.id!!; val neededRole = role(kind)
        if (neededRole !in target.roles) {
            val before = target.roles.map { it.name }.sorted().joinToString(",")
            target.roles.add(neededRole); users.saveAndFlush(target)
            users.rotateAuthorizationVersion(uid, UUID.randomUUID().toString()); em.refresh(target)
            em.createQuery("update UserDevice d set d.revokedAt = :at where d.userId = :id and d.revokedAt is null").setParameter("at", Instant.now()).setParameter("id", uid).executeUpdate()
            em.createQuery("update RefreshSession s set s.revokedAt = :at where s.userId = :id and s.revokedAt is null").setParameter("at", Instant.now()).setParameter("id", uid).executeUpdate()
            accessChanges.saveAndFlush(AdminAccessChangeRecord(UUID.randomUUID().toString(), "USER_ACCESS", uid, null, "GRANT_ROLE", actorId, before,
                target.roles.map { it.name }.sorted().joinToString(","), reason, "partner-role-" + hash(retry), digest, Instant.now()))
        }
        val subkey = "partner-" + hash(retry)
        val entityId = when(kind) {
            PartnerKind.VENDOR -> {
                val result = enrollment.merchant(actorId, AdminMerchantEnrollmentRequest(uid, details.businessName, target.updatedAt, reason, subkey))
                em.createQuery("update MerchantStorefrontRecord s set s.area = :area where s.merchantId = :id").setParameter("area", "${details.city} · ${details.neighborhood}".take(255)).setParameter("id", result.operation.entityId).executeUpdate()
                result.operation.entityId
            }
            PartnerKind.RIDER -> enrollment.courier(actorId, AdminCourierEnrollmentRequest(uid, workforce, details.vehicleType, target.updatedAt, reason, subkey)).operation.entityId
            PartnerKind.HUB -> {
                require(details.latitude != null && details.longitude != null) { "Confirm the hub coordinates before approval." }
                val site = relaySetup.create(actorId, RelaySiteDetailsRequest(details.businessName, details.latitude, details.longitude, details.city, details.neighborhood, details.landmark, reason = reason, idempotencyKey = subkey)).record.site
                parties.operator(site.id, actorId, AdminRelayOperatorRequest(uid, reason, site.updatedAt, subkey + "-operator"))
                site.id
            }
        }
        val operation = operations.saveAndFlush(PartnerOnboardingOperation(actorUserId = actorId, applicationId = applicationId, kind = kind, action = if (applicationId == null) "REGISTER" else "APPROVE", userId = uid, entityId = entityId,
            detailsJson = json.writeValueAsString(details), reason = reason, idempotencyKey = retry, intentHash = digest))
        application?.apply { state = PartnerApplicationState.APPROVED; reviewerUserId = actorId; reviewReason = reason; operationId = operation.id; updatedAt = next(updatedAt) }
        return dto(operation)
    }
    @Transactional fun approve(actor: String, id: String, r: PartnerRegisterRequest): PartnerOperationDto {
        actor(actor)
        val kind = applications.findById(id).orElse(null)?.kind ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return register(actor, kind, r, id)
    }
    @Transactional fun reject(actorId: String, id: String, r: PartnerRejectRequest): PartnerOperationDto {
        actor(actorId); val reason = why(r.reason); val retry = "staff:" + key(r.idempotencyKey)
        val digest = hash(json.writeValueAsString(listOf(actorId, id, "REJECT", reason, r.expectedApplicationUpdatedAt?.toString())))
        operations.findByIdempotencyKey(retry)?.let { if (it.actorUserId != actorId || it.intentHash != digest) conflict("partner_retry_conflict", "This retry belongs to another review."); return dto(it) }
        val application = applications.lockForReview(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (application.state != PartnerApplicationState.PENDING || application.updatedAt != r.expectedApplicationUpdatedAt) conflict("partner_application_changed", "This application has already changed. Refresh its current review.")
        val result = operations.saveAndFlush(PartnerOnboardingOperation(actorUserId = actorId, applicationId = id, kind = application.kind, action = "REJECT", userId = null, entityId = null, detailsJson = application.submittedJson, reason = reason, idempotencyKey = retry, intentHash = digest))
        application.apply { state = PartnerApplicationState.REJECTED; reviewerUserId = actorId; reviewReason = reason; operationId = result.id; updatedAt = next(updatedAt) }
        return dto(result)
    }
    private fun next(at: Instant) = maxOf(Instant.now().truncatedTo(ChronoUnit.MICROS), at.plusNanos(1000))
    private fun details(raw: String) = json.readValue(raw, PartnerDetails::class.java)
    private fun dto(o: PartnerOnboardingOperation) = PartnerOperationDto(o.id, o.kind, o.action, o.actorUserId, o.actorUserId?.let { references.reference(PublicReferenceKind.USER, it) }, o.applicationId, o.userId,
        o.userId?.let { references.reference(PublicReferenceKind.USER, it) }, o.entityId, o.entityId?.let { references.reference(when(o.kind) { PartnerKind.VENDOR -> PublicReferenceKind.MERCHANT; PartnerKind.RIDER -> PublicReferenceKind.COURIER; PartnerKind.HUB -> PublicReferenceKind.RELAY }, it) }, details(o.detailsJson), o.reason, o.createdAt)
    private fun dto(a: PartnerApplicationRecord) = PartnerApplicationDto(a.id, a.kind, a.state, details(a.submittedJson), a.submittedAt, a.updatedAt, a.reviewReason, a.reviewerUserId,
        a.operationId?.let { operations.findById(it).orElse(null)?.let(::dto) }, operations.findTop50ByApplicationIdOrderByCreatedAtAsc(a.id).map(::dto))
    private fun conflict(code: String, message: String): Nothing = throw AdminPartyConflict(code, message)
}

@RestController
class PartnerOnboardingController(private val service: PartnerOnboardingService, private val limiter: InMemoryRateLimiter) {
    @PostMapping("/api/partner-applications") fun submit(@RequestBody r: PartnerSubmission, request: HttpServletRequest): ResponseEntity<Any> {
        val decision = limiter.consume("partner.application.ip", MessageDigest.getInstance("SHA-256").digest((request.remoteAddr ?: "unknown").toByteArray()).joinToString("") { "%02x".format(it) }, RateLimitRule(30, Duration.ofHours(1)))
        if (!decision.allowed) return ResponseEntity.status(429).header("Retry-After", decision.retryAfterSeconds.toString()).body(mapOf("code" to "rate_limited", "message" to "Please wait before submitting another application."))
        return ok(service.submit(r))
    }
    @PostMapping("/api/admin/web/onboarding/preview") fun preview(a: Authentication?, @RequestBody r: PartnerPreviewRequest) = staff(a) { service.preview(a!!.name, requireNotNull(r.kind), r.details) }
    @PostMapping("/api/admin/web/onboarding/{kind}") fun register(a: Authentication?, @PathVariable kind: PartnerKind, @RequestBody r: PartnerRegisterRequest) = staff(a) { service.register(a!!.name, kind, r) }
    @GetMapping("/api/admin/web/onboarding/operations/{key}") fun operation(a: Authentication?, @PathVariable key: String) = staff(a) { mapOf("operation" to service.operation(key)) }
    @GetMapping("/api/admin/web/partner-applications") fun list(a: Authentication?, @RequestParam kind: PartnerKind, @RequestParam(defaultValue = "PENDING") state: PartnerApplicationState, @RequestParam(defaultValue = "50") limit: Int) = staff(a) { service.list(kind, state, limit) }
    @GetMapping("/api/admin/web/partner-applications/{id}") fun detail(a: Authentication?, @PathVariable id: String) = staff(a) { service.application(id) }
    @PostMapping("/api/admin/web/partner-applications/{id}/approve") fun approve(a: Authentication?, @PathVariable id: String, @RequestBody r: PartnerRegisterRequest) = staff(a) { service.approve(a!!.name, id, r) }
    @PostMapping("/api/admin/web/partner-applications/{id}/reject") fun reject(a: Authentication?, @PathVariable id: String, @RequestBody r: PartnerRejectRequest) = staff(a) { service.reject(a!!.name, id, r) }
    private fun ok(value: Any): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
    private fun staff(a: Authentication?, action: () -> Any): ResponseEntity<Any> = when { a == null -> ResponseEntity.status(401).build(); !a.hasAnyRole(RoleGroups.StaffManagers) || !service.canReview(a.name) -> ResponseEntity.status(403).build(); else -> ok(action()) }
    @ExceptionHandler(AdminPartyConflict::class) fun conflict(e: AdminPartyConflict) = ResponseEntity.status(409).body(dev.orestegabo.sequo_api.api.ApiErrorResponse(e.code, e.message))
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class, org.springframework.orm.ObjectOptimisticLockingFailureException::class) fun race() = ResponseEntity.status(409).body(dev.orestegabo.sequo_api.api.ApiErrorResponse("partner_write_conflict", "A concurrent submission won. Review the current account and application before retrying."))
}
