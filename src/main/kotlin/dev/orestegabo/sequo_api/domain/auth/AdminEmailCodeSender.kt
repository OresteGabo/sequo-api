package dev.orestegabo.sequo_api.domain.auth

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

interface AdminEmailCodeSender {
    fun checkAvailability() = Unit
    fun send(email: String, code: String, expiresAt: Instant)
}

class AdminEmailDeliveryUnavailableException : RuntimeException("Admin email sign-in is unavailable.")
class AdminEmailDeliveryException : RuntimeException("Admin email delivery failed.")

@Service
class ResendAdminEmailCodeSender(
    @Value("\${sequo.admin-auth.email.enabled:false}") private val enabled: Boolean,
    @Value("\${sequo.admin-auth.email.api-key:}") private val apiKey: String,
    @Value("\${sequo.admin-auth.email.from:}") private val from: String,
    private val transport: AdminEmailTransport,
) : AdminEmailCodeSender {
    private val json = ObjectMapper()

    init {
        if (enabled) {
            require(apiKey.startsWith("re_") && apiKey.length in 20..512 && apiKey.none { it.isWhitespace() || it.isISOControl() }) {
                "A valid Resend sending API key is required when admin email is enabled."
            }
            require(runCatching { ApiInputPolicy.normalizedIdentityEmail(from) }.isSuccess) {
                "A verified sender email address is required when admin email is enabled."
            }
        }
    }

    override fun checkAvailability() {
        if (!enabled) throw AdminEmailDeliveryUnavailableException()
    }

    override fun send(email: String, code: String, expiresAt: Instant) {
        checkAvailability()
        val recipient = ApiInputPolicy.normalizedIdentityEmail(email)
        require(code.matches(Regex("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{3}-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{3}"))) {
            "Invalid admin sign-in code format."
        }
        val payload = json.writeValueAsString(mapOf(
            "from" to "SEQUO Admin <${ApiInputPolicy.normalizedIdentityEmail(from)}>",
            "to" to listOf(recipient),
            "subject" to "SEQUO Admin — Code de connexion / Sign-in code",
            "text" to "Votre code de connexion SEQUO Admin / Your SEQUO Admin sign-in code:\n\n$code\n\n" +
                "Expire à / Expires at: $expiresAt (UTC).\n" +
                "Ce code est à usage unique. Ne le partagez pas. / This code is single-use. Do not share it.\n" +
                "Si vous n’avez pas demandé cette connexion, ignorez ce message. / If you did not request this sign-in, ignore this email.",
        ))
        // Stable per generated code, without disclosing the recipient or raw code in a provider header.
        val digest = MessageDigest.getInstance("SHA-256").digest("$recipient\n$code\n$expiresAt".toByteArray())
        val idempotencyKey = "sequo-admin-" + digest.joinToString("") { "%02x".format(it) }
        try {
            if (!transport.send(apiKey, payload, idempotencyKey)) throw AdminEmailDeliveryException()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AdminEmailDeliveryException()
        } catch (_: Exception) {
            // Never propagate provider bodies, request headers, keys or raw codes into HTTP/log errors.
            throw AdminEmailDeliveryException()
        }
    }
}
