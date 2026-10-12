package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class CreatedAdminLoginRequest(
    val requestId: String,
    val code: String,
    val expiresAt: Instant,
    val pollingIntervalSeconds: Int,
)

data class AdminLoginRequestStatusSnapshot(
    val status: AdminLoginRequestStatus,
    val expiresAt: Instant,
    val approvedAt: Instant?,
    val rejectedAt: Instant?,
)

data class MobileAdminLoginRequestSummary(
    val requestId: String,
    val code: String,
    val deviceLabel: String?,
    val browserInfo: String?,
    val requestedAt: Instant,
    val expiresAt: Instant,
)

data class AdminLoginApprovalStatus(
    val status: AdminLoginRequestStatus,
    val approvedAt: Instant?,
    val rejectedAt: Instant?,
)

@Service
class AdminAuthService(
    private val adminLoginRequestRepository: AdminLoginRequestRepository,
    private val codeService: AdminLoginCodeService,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun approveLoginRequest(requestId: String, userId: String, now: Instant = Instant.now()): AdminLoginApprovalStatus? {
        val user = eligibleApprover(userId) ?: return null
        val request = approvableRequestForUser(requestId, user, now) ?: return null
        request.status = AdminLoginRequestStatus.APPROVED
        request.approvedAt = now
        request.approvedByUserId = requireNotNull(user.id)
        adminLoginRequestRepository.save(request)
        return AdminLoginApprovalStatus(
            status = request.status,
            approvedAt = request.approvedAt,
            rejectedAt = request.rejectedAt,
        )
    }

    @Transactional
    fun rejectLoginRequest(requestId: String, userId: String, reason: String?, now: Instant = Instant.now()): AdminLoginApprovalStatus? {
        val user = eligibleApprover(userId) ?: return null
        val request = approvableRequestForUser(requestId, user, now) ?: return null
        request.status = AdminLoginRequestStatus.REJECTED
        request.rejectedAt = now
        request.rejectionReason = ApiInputPolicy.optionalShortText(reason, "reason", 100)
        adminLoginRequestRepository.save(request)
        return AdminLoginApprovalStatus(
            status = request.status,
            approvedAt = request.approvedAt,
            rejectedAt = request.rejectedAt,
        )
    }

    @Transactional(readOnly = true)
    fun pendingMobileLoginRequests(userId: String, now: Instant = Instant.now()): List<MobileAdminLoginRequestSummary>? {
        val user = userRepository.findById(userId).orElse(null) ?: return null
        if (!user.active || !user.status.canAuthenticate()) return null
        if (user.roles.none { it in AdminApproverRoles }) return null
        val email = user.email ?: return emptyList()
        val normalizedEmail = ApiInputPolicy.normalizedIdentityEmail(email)
        return adminLoginRequestRepository
            .findAllByEmailNormalizedAndStatusAndExpiresAtAfterOrderByRequestedAtDesc(
                normalizedEmail,
                AdminLoginRequestStatus.PENDING,
                now,
            )
            .map {
                MobileAdminLoginRequestSummary(
                    requestId = requireNotNull(it.id),
                    code = codeService.formatPlaceholder(),
                    deviceLabel = it.deviceLabel,
                    browserInfo = it.browserInfo,
                    requestedAt = it.requestedAt,
                    expiresAt = it.expiresAt,
                )
            }
    }

    @Transactional(readOnly = true)
    fun loginRequestStatus(requestId: String, now: Instant = Instant.now()): AdminLoginRequestStatusSnapshot? {
        val request = adminLoginRequestRepository.findById(requestId).orElse(null) ?: return null
        val status = if (request.status == AdminLoginRequestStatus.PENDING && codeService.expired(request.expiresAt, now)) {
            AdminLoginRequestStatus.EXPIRED
        } else {
            request.status
        }
        return AdminLoginRequestStatusSnapshot(
            status = status,
            expiresAt = request.expiresAt,
            approvedAt = request.approvedAt,
            rejectedAt = request.rejectedAt,
        )
    }

    @Transactional
    fun createLoginRequest(
        email: String,
        deviceLabel: String?,
        browserInfo: String?,
        now: Instant = Instant.now(),
    ): CreatedAdminLoginRequest {
        val normalizedEmail = ApiInputPolicy.normalizedIdentityEmail(email)
        val generatedCode = codeService.generate(now = now)
        val request = adminLoginRequestRepository.save(
            AdminLoginRequest(
                emailNormalized = normalizedEmail,
                codeHash = generatedCode.codeHash,
                deviceLabel = ApiInputPolicy.optionalShortText(deviceLabel, "deviceLabel"),
                browserInfo = ApiInputPolicy.optionalShortText(browserInfo, "browserInfo"),
                requestedAt = now,
                expiresAt = generatedCode.expiresAt,
            )
        )
        return CreatedAdminLoginRequest(
            requestId = requireNotNull(request.id),
            code = generatedCode.code,
            expiresAt = request.expiresAt,
            pollingIntervalSeconds = PollingIntervalSeconds,
        )
    }

    private fun approvableRequestForUser(requestId: String, user: User, now: Instant): AdminLoginRequest? {
        val request = adminLoginRequestRepository.findByIdAndStatus(requestId, AdminLoginRequestStatus.PENDING) ?: return null
        if (codeService.expired(request.expiresAt, now)) return null
        val email = user.email ?: return null
        val normalizedEmail = ApiInputPolicy.normalizedIdentityEmail(email)
        if (request.emailNormalized != normalizedEmail && RoleCode.SUPER_ADMIN !in user.roles) return null
        return request
    }

    private fun eligibleApprover(userId: String): User? {
        val user = userRepository.findById(userId).orElse(null) ?: return null
        if (!user.active || !user.status.canAuthenticate()) return null
        if (user.roles.none { it in AdminApproverRoles }) return null
        return user
    }

    private companion object {
        private const val PollingIntervalSeconds = 2
        private val AdminApproverRoles = setOf(RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    }
}
