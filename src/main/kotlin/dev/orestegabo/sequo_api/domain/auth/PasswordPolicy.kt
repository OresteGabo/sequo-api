package dev.orestegabo.sequo_api.domain.auth

import org.springframework.stereotype.Service
import java.time.DateTimeException
import java.time.LocalDate

data class PasswordPolicyContext(
    val email: String? = null,
    val displayName: String? = null,
    val additionalUnsafeTerms: Set<String> = emptySet()
)

data class PasswordPolicyViolation(
    val code: String,
    val message: String
)

class WeakPasswordException(
    val violations: List<PasswordPolicyViolation>
) : IllegalArgumentException(
    violations.joinToString(separator = "; ") { it.message }
)

@Service
class PasswordPolicy {
    private val specialCharacters = setOf(
        '!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '-', '_', '+', '=',
        '[', ']', '{', '}', ':', ';', ',', '.', '?', '/', '~', '`', '|', '\\'
    )

    fun validate(
        rawPassword: String?,
        context: PasswordPolicyContext = PasswordPolicyContext()
    ): List<PasswordPolicyViolation> {
        if (rawPassword.isNullOrBlank()) {
            return listOf(PasswordPolicyViolation("password_blank", "Password must not be blank."))
        }

        val violations = mutableListOf<PasswordPolicyViolation>()
        val normalizedPassword = normalize(rawPassword)

        if (rawPassword.length < 12) {
            violations += PasswordPolicyViolation(
                "password_too_short",
                "Password must contain at least 12 characters."
            )
        }

        if (rawPassword.length > 128) {
            violations += PasswordPolicyViolation(
                "password_too_long",
                "Password must contain no more than 128 characters."
            )
        }

        if (rawPassword.none { it.isLowerCase() }) {
            violations += PasswordPolicyViolation(
                "password_missing_lowercase",
                "Password must contain at least one lowercase letter."
            )
        }

        if (rawPassword.none { it.isUpperCase() }) {
            violations += PasswordPolicyViolation(
                "password_missing_uppercase",
                "Password must contain at least one uppercase letter."
            )
        }

        if (rawPassword.none { it.isDigit() }) {
            violations += PasswordPolicyViolation(
                "password_missing_digit",
                "Password must contain at least one digit."
            )
        }

        if (rawPassword.none { it in specialCharacters }) {
            violations += PasswordPolicyViolation(
                "password_missing_special",
                "Password must contain at least one special character."
            )
        }

        if (rawPassword.lowercase() in blockedPasswords || normalizedPassword in normalizedBlockedPasswords) {
            violations += PasswordPolicyViolation(
                "password_common",
                "Password is too common."
            )
        }

        if (keyboardSequences.any { normalizedPassword.contains(it) }) {
            violations += PasswordPolicyViolation(
                "password_keyboard_sequence",
                "Password must not contain obvious keyboard or numeric sequences."
            )
        }

        if (hasLongRepeatedCharacterRun(rawPassword)) {
            violations += PasswordPolicyViolation(
                "password_repeated_characters",
                "Password must not rely on repeated characters."
            )
        }

        if (containsLikelyDate(rawPassword)) {
            violations += PasswordPolicyViolation(
                "password_looks_like_date",
                "Password must not contain a date such as a birthday or anniversary."
            )
        }

        if (containsPersonalInformation(normalizedPassword, context)) {
            violations += PasswordPolicyViolation(
                "password_contains_personal_info",
                "Password must not contain your name or email."
            )
        }

        return violations
    }

    fun validateOrThrow(
        rawPassword: String?,
        context: PasswordPolicyContext = PasswordPolicyContext()
    ) {
        val violations = validate(rawPassword, context)
        if (violations.isNotEmpty()) {
            throw WeakPasswordException(violations)
        }
    }

    private fun containsPersonalInformation(
        normalizedPassword: String,
        context: PasswordPolicyContext
    ): Boolean = personalTerms(context).any { normalizedPassword.contains(it) }

    private fun personalTerms(context: PasswordPolicyContext): Set<String> {
        val emailLocalPart = context.email
            ?.substringBefore("@")
            ?.split(Regex("[^A-Za-z0-9]+"))
            .orEmpty()

        val nameParts = context.displayName
            ?.split(Regex("[^A-Za-z0-9]+"))
            .orEmpty()

        return (emailLocalPart + nameParts + context.additionalUnsafeTerms)
            .map(::normalize)
            .filter { it.length >= 3 && it.any(Char::isLetter) }
            .toSet()
    }

    private fun hasLongRepeatedCharacterRun(rawPassword: String): Boolean {
        var previous: Char? = null
        var runLength = 0

        rawPassword.lowercase().forEach { current ->
            if (current == previous) {
                runLength += 1
            } else {
                previous = current
                runLength = 1
            }

            if (runLength >= 6) return true
        }

        return false
    }

    private fun containsLikelyDate(rawPassword: String): Boolean {
        val digits = rawPassword.filter(Char::isDigit)
        if (digits.length < 6) return false

        return digits.windowed(size = 8, step = 1, partialWindows = false).any(::isValidDateDigits) ||
            digits.windowed(size = 6, step = 1, partialWindows = false).any(::isValidDateDigits)
    }

    private fun isValidDateDigits(digits: String): Boolean =
        when (digits.length) {
            8 -> isValidDate(digits.take(4), digits.substring(4, 6), digits.takeLast(2)) ||
                isValidDate(digits.takeLast(4), digits.substring(2, 4), digits.take(2)) ||
                isValidDate(digits.takeLast(4), digits.take(2), digits.substring(2, 4))
            6 -> isValidDate("20${digits.take(2)}", digits.substring(2, 4), digits.takeLast(2)) ||
                isValidDate("20${digits.takeLast(2)}", digits.substring(2, 4), digits.take(2)) ||
                isValidDate("20${digits.takeLast(2)}", digits.take(2), digits.substring(2, 4))
            else -> false
        }

    private fun isValidDate(yearText: String, monthText: String, dayText: String): Boolean {
        val year = yearText.toIntOrNull() ?: return false
        val month = monthText.toIntOrNull() ?: return false
        val day = dayText.toIntOrNull() ?: return false

        if (year !in 1900..2035) return false

        return try {
            LocalDate.of(year, month, day)
            true
        } catch (e: DateTimeException) {
            false
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private companion object {
        val blockedPasswords = setOf(
            "password",
            "password123",
            "password123!",
            "password1234",
            "passw0rd",
            "p@ssw0rd",
            "changeme",
            "changeme123",
            "letmein",
            "letmein123",
            "welcome",
            "welcome123",
            "welcome2026",
            "admin",
            "admin123",
            "administrator",
            "root",
            "rootroot",
            "user123",
            "test1234",
            "demo1234",
            "qwerty",
            "qwerty123",
            "azerty",
            "azerty123",
            "iloveyou",
            "monkey",
            "dragon",
            "football",
            "abc123",
            "abcd1234",
            "123456789012",
            "111111111111",
            "000000000000",
            "qwerty123456",
            "azerty123456",
            "sequo",
            "sequo123",
            "sequo123456",
            "sequo2026",
            "sequo2026!",
            "sequoapi",
            "sequoapi2026"
        )

        val normalizedBlockedPasswords = blockedPasswords.map { password ->
            password.lowercase().filter(Char::isLetterOrDigit)
        }.toSet()

        val keyboardSequences = setOf(
            "123456",
            "234567",
            "345678",
            "456789",
            "987654",
            "876543",
            "765432",
            "654321",
            "abcdef",
            "fedcba",
            "qwerty",
            "azerty",
            "asdf",
            "zxcv"
        )
    }
}
