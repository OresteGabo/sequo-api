package dev.orestegabo.sequo_api.domain.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordResetTokenServiceTest {
    private val tokenService = PasswordResetTokenService()

    @Test
    fun generatedTokensAreHighEntropyAndUrlSafe() {
        val token = tokenService.generate()

        assertTrue(token.rawToken.length >= 40)
        assertFalse(token.rawToken.contains("="))
        assertNotEquals(token.rawToken, token.tokenHash)
    }

    @Test
    fun generatedTokenHashMatchesOnlyOriginalToken() {
        val token = tokenService.generate()

        assertTrue(tokenService.matches(token.rawToken, token.tokenHash))
        assertFalse(tokenService.matches("${token.rawToken}x", token.tokenHash))
    }

    @Test
    fun generatedTokensAreUnique() {
        val first = tokenService.generate()
        val second = tokenService.generate()

        assertNotEquals(first.rawToken, second.rawToken)
        assertNotEquals(first.tokenHash, second.tokenHash)
    }
}
