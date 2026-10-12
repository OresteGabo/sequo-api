package dev.orestegabo.sequo_api.domain.auth

import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

data class AdminLoginCode(
    val code: String,
    val codeHash: String,
    val expiresAt: Instant,
)

@Service
class AdminLoginCodeService(
    private val tokenService: PasswordResetTokenService,
) {
    private val secureRandom = SecureRandom()

    fun generate(
        now: Instant = Instant.now(),
        ttl: Duration = DefaultTtl,
    ): AdminLoginCode {
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive." }
        val rawCode = buildString(CodeLength) {
            repeat(CodeLength) {
                append(Alphabet[secureRandom.nextInt(Alphabet.length)])
            }
        }
        val displayCode = format(rawCode)
        return AdminLoginCode(
            code = displayCode,
            codeHash = tokenService.hash(rawCode),
            expiresAt = now.plus(ttl),
        )
    }

    fun verify(input: String, codeHash: String): Boolean {
        val normalized = normalize(input)
        if (normalized.length != CodeLength) return false
        if (normalized.any { it !in Alphabet }) return false
        return tokenService.matches(normalized, codeHash)
    }

    fun expired(expiresAt: Instant, now: Instant = Instant.now()): Boolean =
        !expiresAt.isAfter(now)

    fun normalize(input: String): String =
        input.trim()
            .uppercase()
            .filter { it != '-' && !it.isWhitespace() }

    fun formatPlaceholder(): String = "***-***"

    fun format(rawCode: String): String {
        val normalized = normalize(rawCode)
        require(normalized.length == CodeLength) { "code must contain $CodeLength characters." }
        require(normalized.all { it in Alphabet }) { "code contains unsupported characters." }
        return normalized.chunked(3).joinToString("-")
    }

    companion object {
        const val CodeLength: Int = 6
        const val Alphabet: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val DefaultTtl: Duration = Duration.ofMinutes(3)
    }
}
