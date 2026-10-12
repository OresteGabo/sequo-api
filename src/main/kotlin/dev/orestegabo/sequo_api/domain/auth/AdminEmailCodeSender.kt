package dev.orestegabo.sequo_api.domain.auth

import org.springframework.stereotype.Service
import java.time.Instant

interface AdminEmailCodeSender {
    fun send(email: String, code: String, expiresAt: Instant)
}

@Service
class NoopAdminEmailCodeSender : AdminEmailCodeSender {
    override fun send(email: String, code: String, expiresAt: Instant) = Unit
}
