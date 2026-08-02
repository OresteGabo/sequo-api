package dev.orestegabo.sequo_api.domain.auth

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PasswordPolicyTest {
    private val passwordPolicy = PasswordPolicy()

    @Test
    fun acceptsStrongPassword() {
        assertTrue(passwordPolicy.validate("Cobalt-Violet-47!").isEmpty())
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

    @Test
    fun rejectsLeetspeakWeakTermsInsideLongerPasswords() {
        val violations = passwordPolicy.validate("MyP@ssw0rd2026!")

        assertTrue(violations.any { it.code == "password_contains_weak_term" })
    }

    @Test
    fun rejectsLongNumericRunsThatLookLikePhoneNumbersOrIds() {
        val violations = passwordPolicy.validate("Secure99011234!")

        assertTrue(violations.any { it.code == "password_long_numeric_run" })
    }

    @Test
    fun rejectsRepeatedPatterns() {
        val violations = passwordPolicy.validate("Ab1!Ab1!Ab1!")

        assertTrue(violations.any { it.code == "password_repeated_pattern" })
    }

    @Test
    fun rejectsCalendarWordsWithNumbers() {
        val englishViolations = passwordPolicy.validate("JanuarySafe2026!")
        val frenchViolations = passwordPolicy.validate("JanvierSafe2026!")

        assertTrue(englishViolations.any { it.code == "password_contains_calendar_term" })
        assertTrue(frenchViolations.any { it.code == "password_contains_calendar_term" })
    }

    @Test
    fun rejectsLocalBusinessTerms() {
        val sequoViolations = passwordPolicy.validate("SequoSecure2026!")
        val lomeViolations = passwordPolicy.validate("LomeSecure2026!")

        assertTrue(sequoViolations.any { it.code == "password_contains_weak_term" })
        assertTrue(lomeViolations.any { it.code == "password_contains_weak_term" })
    }
}
