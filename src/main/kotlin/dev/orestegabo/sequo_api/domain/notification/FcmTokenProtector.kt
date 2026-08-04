package dev.orestegabo.sequo_api.domain.notification

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object NotificationTokenHashing {
    fun sha256Base64Url(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

interface FcmTokenProtector {
    fun protect(rawToken: String): String

    fun reveal(protectedToken: String): String
}

@Component
class AesGcmFcmTokenProtector(
    @Value("\${sequo.notifications.token-encryption-secret:sequo_notifications_dev_encryption_key_2026_change_before_prod}")
    encryptionSecret: String,
) : FcmTokenProtector {
    private val random = SecureRandom()
    private val keySpec = SecretKeySpec(deriveKey(encryptionSecret), "AES")

    override fun protect(rawToken: String): String {
        require(rawToken.isNotBlank()) { "fcmToken cannot be blank." }

        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)

        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val encrypted = cipher.doFinal(rawToken.toByteArray(StandardCharsets.UTF_8))

        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + encrypted)
    }

    override fun reveal(protectedToken: String): String {
        require(protectedToken.startsWith(TOKEN_PREFIX)) { "Unsupported protected FCM token version." }

        val encoded = protectedToken.removePrefix(TOKEN_PREFIX)
        val payload = Base64.getUrlDecoder().decode(encoded)
        require(payload.size > NONCE_BYTES) { "Protected FCM token payload is invalid." }

        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val encrypted = payload.copyOfRange(NONCE_BYTES, payload.size)

        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun deriveKey(secret: String): ByteArray {
        require(secret.length >= MIN_SECRET_LENGTH) {
            "sequo.notifications.token-encryption-secret must be at least $MIN_SECRET_LENGTH characters."
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(secret.toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val TOKEN_PREFIX = "v1:"
        private const val MIN_SECRET_LENGTH = 32
    }
}
