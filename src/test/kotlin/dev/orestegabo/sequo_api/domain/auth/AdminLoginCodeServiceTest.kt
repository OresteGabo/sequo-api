package dev.orestegabo.sequo_api.domain.auth

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdminLoginCodeServiceTest {
    private val service = AdminLoginCodeService(PasswordResetTokenService())

    @Test
    fun generatedCodeUsesReadableSixCharacterFormat() {
        val generated = service.generate(now = Instant.parse("2026-10-12T08:00:00Z"))

        assertTrue(generated.code.matches(Regex("^[A-Z2-9]{3}-[A-Z2-9]{3}$")))
        assertEquals(6, service.normalize(generated.code).length)
        assertFalse(generated.code.any { it in setOf('O', '0', 'I', '1', 'L') })
        assertTrue(generated.expiresAt.isAfter(Instant.parse("2026-10-12T08:00:00Z")))
    }

    @Test
    fun generatedCodeHashDoesNotStorePlainCode() {
        val generated = service.generate()

        assertNotEquals(service.normalize(generated.code), generated.codeHash)
        assertTrue(generated.codeHash.length >= 40)
    }

    @Test
    fun verifiesOriginalCodeWithOrWithoutDashAndRejectsWrongCode() {
        val generated = service.generate()
        val compact = service.normalize(generated.code)

        assertTrue(service.verify(generated.code, generated.codeHash))
        assertTrue(service.verify(compact.lowercase(), generated.codeHash))
        assertFalse(service.verify("ABC-DEF", generated.codeHash))
        assertFalse(service.verify("ABC<DE", generated.codeHash))
    }

    @Test
    fun generatedCodesAreUniqueAcrossReasonableSample() {
        val codes = List(500) { service.generate().code }

        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun expiryHelperTreatsBoundaryAsExpired() {
        val now = Instant.parse("2026-10-12T08:00:00Z")
        val generated = service.generate(now = now, ttl = Duration.ofSeconds(30))

        assertFalse(service.expired(generated.expiresAt, now.plusSeconds(29)))
        assertTrue(service.expired(generated.expiresAt, now.plusSeconds(30)))
        assertTrue(service.expired(generated.expiresAt, now.plusSeconds(31)))
    }

    @Test
    fun formatRejectsUnsupportedCharacters() {
        assertEquals("ABC-DEF", service.format("abcdef"))

        assertFalse(runCatching { service.format("ABC0EF") }.isSuccess)
        assertFalse(runCatching { service.format("ABCDE") }.isSuccess)
    }
}
