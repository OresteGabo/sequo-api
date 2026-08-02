package dev.orestegabo.sequo_api.domain.auth

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PasswordPolicyTest {
    private val passwordPolicy = PasswordPolicy()

    @Test
    fun acceptsStrongPassword() {
        assertTrue(passwordPolicy.validate("RiverMarket2026!").isEmpty())
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

    @Test
    fun rejectsDateLikePassword() {
        val slashDateViolations = passwordPolicy.validate("Safe01/01/2000!")
        val compactDateViolations = passwordPolicy.validate("Safe20000101!")

        assertTrue(slashDateViolations.any { it.code == "password_looks_like_date" })
        assertTrue(compactDateViolations.any { it.code == "password_looks_like_date" })
    }

    @Test
    fun rejectsPasswordContainingDisplayName() {
        val violations = passwordPolicy.validate(
            rawPassword = "GamalSecure2026!",
            context = PasswordPolicyContext(displayName = "Gamal Tchadenou")
        )

        assertTrue(violations.any { it.code == "password_contains_personal_info" })
    }

    @Test
    fun rejectsPasswordContainingEmailLocalPart() {
        val violations = passwordPolicy.validate(
            rawPassword = "AminaSafe2026!",
            context = PasswordPolicyContext(email = "amina.kokou@example.com")
        )

        assertTrue(violations.any { it.code == "password_contains_personal_info" })
    }

    @Test
    fun rejectsKeyboardSequencesAndRepeatedCharacters() {
        val keyboardViolations = passwordPolicy.validate("QwertyStrong2026!")
        val repeatedViolations = passwordPolicy.validate("AaaaaaaStrong2026!")

        assertTrue(keyboardViolations.any { it.code == "password_keyboard_sequence" })
        assertTrue(repeatedViolations.any { it.code == "password_repeated_characters" })
    }
}
