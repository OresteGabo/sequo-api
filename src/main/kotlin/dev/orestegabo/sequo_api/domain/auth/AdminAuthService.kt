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

@Service
class AdminAuthService(
    private val adminLoginRequestRepository: AdminLoginRequestRepository,
    private val codeService: AdminLoginCodeService,
) {
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

    private companion object {
        private const val PollingIntervalSeconds = 2
    }
}
