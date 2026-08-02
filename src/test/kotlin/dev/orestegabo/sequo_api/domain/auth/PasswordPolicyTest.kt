package dev.orestegabo.sequo_api.domain.auth

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PasswordPolicyTest {
    private val passwordPolicy = PasswordPolicy()

    @Test
    fun acceptsStrongPassword() {
        assertTrue(passwordPolicy.validate("SequoStrong2026!").isEmpty())
    }

    @Test
    fun rejectsShortPassword() {
        val violations = passwordPolicy.validate("Sequo1!")

        assertTrue(violations.any { it.code == "password_too_short" })
    }

    @Test
    fun rejectsPasswordWithoutRequiredCharacterGroups() {
        val violations = passwordPolicy.validate("sequosequosequo")

        assertTrue(violations.any { it.code == "password_missing_uppercase" })
        assertTrue(violations.any { it.code == "password_missing_digit" })
        assertTrue(violations.any { it.code == "password_missing_special" })
    }

    @Test
    fun throwsForWeakPassword() {
        assertFailsWith<WeakPasswordException> {
            passwordPolicy.validateOrThrow("password123!")
        }
    }
}
