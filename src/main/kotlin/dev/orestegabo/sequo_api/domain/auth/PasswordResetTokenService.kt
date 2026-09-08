package dev.orestegabo.sequo_api.domain.auth

import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PasswordResetToken(
    val rawToken: String,
    val tokenHash: String
)

interface PasswordResetTokenNotifier {
    fun send(userId: String, email: String, rawToken: String)
}

@Service
class NoopPasswordResetTokenNotifier : PasswordResetTokenNotifier {
    override fun send(userId: String, email: String, rawToken: String) = Unit
}

@Service
class PasswordResetTokenService {
    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): PasswordResetToken {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)

        val rawToken = encoder.encodeToString(bytes)
        return PasswordResetToken(
            rawToken = rawToken,
            tokenHash = hash(rawToken)
        )
    }

    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray(Charsets.UTF_8))

        return encoder.encodeToString(digest)
    }

    fun matches(rawToken: String, tokenHash: String): Boolean =
        MessageDigest.isEqual(
            hash(rawToken).toByteArray(Charsets.UTF_8),
            tokenHash.toByteArray(Charsets.UTF_8)
        )

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
