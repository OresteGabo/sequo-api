package dev.orestegabo.sequo_api.domain.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface AdminLoginCodeProtector {
    fun protect(code: String): String
    fun reveal(protectedCode: String): String
}

@Component
class AesGcmAdminLoginCodeProtector(
    @Value("\${sequo.admin-auth.code-encryption-secret:sequo_admin_auth_code_secret_2026_change_before_prod}")
    encryptionSecret: String,
) : AdminLoginCodeProtector {
    private val random = SecureRandom()
    private val keySpec = SecretKeySpec(deriveKey(encryptionSecret), "AES")

    override fun protect(code: String): String {
        require(code.isNotBlank()) { "code cannot be blank." }
        val nonce = ByteArray(NonceBytes)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance(AesGcmAlgorithm)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GcmTagBits, nonce))
        val encrypted = cipher.doFinal(code.toByteArray(StandardCharsets.UTF_8))
        return Prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + encrypted)
    }

    override fun reveal(protectedCode: String): String {
        require(protectedCode.startsWith(Prefix)) { "Unsupported protected admin login code version." }
        val payload = Base64.getUrlDecoder().decode(protectedCode.removePrefix(Prefix))
        require(payload.size > NonceBytes) { "Protected admin login code payload is invalid." }
        val nonce = payload.copyOfRange(0, NonceBytes)
        val encrypted = payload.copyOfRange(NonceBytes, payload.size)
        val cipher = Cipher.getInstance(AesGcmAlgorithm)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GcmTagBits, nonce))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun deriveKey(secret: String): ByteArray {
        require(secret.length >= MinSecretLength) {
            "sequo.admin-auth.code-encryption-secret must be at least $MinSecretLength characters."
        }
        return MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        private const val AesGcmAlgorithm = "AES/GCM/NoPadding"
        private const val GcmTagBits = 128
        private const val NonceBytes = 12
        private const val Prefix = "v1:"
        private const val MinSecretLength = 32
    }
}
