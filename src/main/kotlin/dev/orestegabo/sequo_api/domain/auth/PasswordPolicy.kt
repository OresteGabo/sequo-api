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
        val leetspeakNormalizedPassword = normalizeLeetspeak(rawPassword)

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

        if (
            rawPassword.lowercase() in blockedPasswords ||
            normalizedPassword in normalizedBlockedPasswords ||
            leetspeakNormalizedPassword in normalizedBlockedPasswords
        ) {
            violations += PasswordPolicyViolation(
                "password_common",
                "Password is too common."
            )
        }

        if (containsWeakTerm(normalizedPassword) || containsWeakTerm(leetspeakNormalizedPassword)) {
            violations += PasswordPolicyViolation(
                "password_contains_weak_term",
                "Password must not contain obvious weak words."
            )
        }

        if (keyboardSequences.any { normalizedPassword.contains(it) }) {
            violations += PasswordPolicyViolation(
                "password_keyboard_sequence",
                "Password must not contain obvious keyboard or numeric sequences."
            )
        }

        if (hasLongNumericRun(rawPassword)) {
            violations += PasswordPolicyViolation(
                "password_long_numeric_run",
                "Password must not contain long number runs that look like phone numbers or IDs."
            )
        }

        if (hasLongRepeatedCharacterRun(rawPassword)) {
            violations += PasswordPolicyViolation(
                "password_repeated_characters",
                "Password must not rely on repeated characters."
            )
        }

        if (hasRepeatedPattern(normalizedPassword)) {
            violations += PasswordPolicyViolation(
                "password_repeated_pattern",
                "Password must not rely on repeated word or character patterns."
            )
        }

        if (containsLikelyDate(rawPassword)) {
            violations += PasswordPolicyViolation(
                "password_looks_like_date",
                "Password must not contain a date such as a birthday or anniversary."
            )
        }

        if (containsCalendarTermWithNumber(normalizedPassword)) {
            violations += PasswordPolicyViolation(
                "password_contains_calendar_term",
                "Password must not contain obvious month or calendar terms with numbers."
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

    private fun hasLongNumericRun(rawPassword: String): Boolean =
        Regex("\\d{8,}").containsMatchIn(rawPassword)

    private fun hasRepeatedPattern(normalizedPassword: String): Boolean {
        if (normalizedPassword.length < 8) return false

        for (patternLength in 2..6) {
            val pattern = normalizedPassword.take(patternLength)
            val repeated = pattern.repeat(normalizedPassword.length / patternLength)
            if (
                repeated == normalizedPassword &&
                normalizedPassword.length / patternLength >= 3
            ) {
                return true
            }
        }

        return false
    }

    private fun containsWeakTerm(normalizedPassword: String): Boolean =
        weakTerms.any { normalizedPassword.contains(it) }

    private fun containsCalendarTermWithNumber(normalizedPassword: String): Boolean =
        normalizedPassword.any(Char::isDigit) && calendarTerms.any { normalizedPassword.contains(it) }

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

    private fun normalizeLeetspeak(value: String): String =
        normalize(
            value
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('@', 'a')
                .replace('$', 's')
                .replace('!', 'i')
        )

    private companion object {
        val blockedPasswords = setOf(
            "password",
            "password123",
            "password123!",
            "password1234",
            "password2026",
            "passw0rd",
            "p@ssw0rd",
            "p@ssword123",
            "changeme",
            "changeme123",
            "changeit",
            "changeit123",
            "letmein",
            "letmein123",
            "welcome",
            "welcome123",
            "welcome2026",
            "bienvenue",
            "bienvenue123",
            "bonjour",
            "bonjour123",
            "bonsoir",
            "bonsoir123",
            "admin",
            "admin123",
            "adminadmin",
            "administrator",
            "root",
            "rootroot",
            "user123",
            "useruser",
            "test1234",
            "demo1234",
            "guest123",
            "guestguest",
            "qwerty",
            "qwerty123",
            "azerty",
            "azerty123",
            "azertyuiop",
            "iloveyou",
            "love123",
            "amour123",
            "secret",
            "secret123",
            "monkey",
            "dragon",
            "football",
            "soccer",
            "basketball",
            "princess",
            "sunshine",
            "master",
            "freedom",
            "whatever",
            "trustno1",
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

        val weakTerms = setOf(
            "password",
            "passw0rd",
            "admin",
            "administrator",
            "root",
            "welcome",
            "bienvenue",
            "bonjour",
            "bonsoir",
            "changeme",
            "letmein",
            "secret",
            "qwerty",
            "azerty",
            "iloveyou",
            "love",
            "amour",
            "master",
            "login",
            "sequo",
            "sequoservice",
            "sequoapi",
            "togo",
            "lome",
            "lomé",
            "default",
            "temporary",
            "temporaire"
        ).map { it.lowercase().filter(Char::isLetterOrDigit) }.toSet()

        val calendarTerms = setOf(
            "january",
            "february",
            "march",
            "april",
            "may",
            "june",
            "july",
            "august",
            "september",
            "october",
            "november",
            "december",
            "janvier",
            "fevrier",
            "février",
            "mars",
            "avril",
            "mai",
            "juin",
            "juillet",
            "aout",
            "août",
            "septembre",
            "octobre",
            "novembre",
            "decembre",
            "décembre",
            "anniversaire",
            "birthday"
        ).map { it.lowercase().filter(Char::isLetterOrDigit) }.toSet()

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
            "ytrewq",
            "ytreza",
            "qwertyuiop",
            "azertyuiop",
            "asdf",
            "zxcv",
            "qazwsx",
            "1q2w3e",
            "zaq12wsx",
            "poiuyt",
            "mlkjhg"
        )
    }
}
