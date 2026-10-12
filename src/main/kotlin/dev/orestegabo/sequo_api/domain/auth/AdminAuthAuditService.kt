package dev.orestegabo.sequo_api.domain.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

enum class AdminAuthAuditEventType {
    LOGIN_REQUEST_CREATED,
    LOGIN_REQUEST_APPROVED,
    LOGIN_REQUEST_REJECTED,
    LOGIN_REQUEST_EXPIRED,
    LOGIN_REQUEST_EXCHANGED,
    EMAIL_OTP_REQUESTED,
    EMAIL_OTP_VERIFIED,
    SOCIAL_LOGIN_SUCCEEDED,
    SOCIAL_LOGIN_FAILED,
}

data class AdminAuthAuditEvent(
    val type: AdminAuthAuditEventType,
    val occurredAt: Instant = Instant.now(),
    val userId: String? = null,
    val requestId: String? = null,
    val clientType: AppSource = AppSource.SEQUO_ADMIN,
    val provider: AuthProvider? = null,
)

interface AdminAuthAuditService {
    fun record(event: AdminAuthAuditEvent)
}

@Service
class LoggingAdminAuthAuditService : AdminAuthAuditService {
    private val logger = LoggerFactory.getLogger(LoggingAdminAuthAuditService::class.java)

    override fun record(event: AdminAuthAuditEvent) {
        logger.info(
            "Admin auth audit event type={} occurredAt={} userId={} requestId={} clientType={} provider={}",
            event.type,
            event.occurredAt,
            event.userId,
            event.requestId,
            event.clientType,
            event.provider,
        )
    }
}

