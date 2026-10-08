package dev.orestegabo.sequo_api.api

object ApiInputPolicy {
    private const val MaxEmailLength = 254
    private const val MaxShortTokenLength = 512
    private const val MaxLongTokenLength = 4096
    private const val MaxIdLength = 128
    private const val MaxIdempotencyKeyLength = 128
    private const val MaxShortTextLength = 255
    private const val MaxLongTextLength = 2_000

    private val EmailPattern = Regex("^[A-Za-z0-9.!#\$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")
    private val SafeIdentifierPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val IdempotencyKeyPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
    private val LanguageTagPattern = Regex("^[a-zA-Z]{2,3}([_-][a-zA-Z0-9]{2,8}){0,2}$")

    fun normalizedEmail(value: String, field: String = "email"): String {
        requireNoControlChars(value, field)
        val normalized = value.trim().lowercase()
        require(normalized.isNotBlank()) { "$field is required." }
        require(normalized.length <= MaxEmailLength) { "$field cannot exceed $MaxEmailLength characters." }
        require(EmailPattern.matches(normalized)) { "$field must be a valid email address." }
        return normalized
    }

    fun normalizedIdentityEmail(value: String, field: String = "email"): String {
        val normalized = normalizedEmail(value, field)
        val localPart = normalized.substringBefore("@")
        val domain = normalized.substringAfter("@")

        if (domain !in GmailDomains) return normalized

        val canonicalLocalPart = localPart.substringBefore("+").replace(".", "")
        require(canonicalLocalPart.isNotBlank()) { "$field must be a valid email address." }
        return "$canonicalLocalPart@gmail.com"
    }

    fun requiredToken(value: String, field: String = "token", maxLength: Int = MaxLongTokenLength): String {
        requireNoControlChars(value, field)
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "$field is required." }
        require(normalized.length <= maxLength) { "$field cannot exceed $maxLength characters." }
        return normalized
    }

    fun requiredShortToken(value: String, field: String = "token"): String =
        requiredToken(value, field, MaxShortTokenLength)

    fun requiredIdentifier(value: String, field: String): String {
        requireNoControlChars(value, field)
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "$field is required." }
        require(normalized.length <= MaxIdLength) { "$field cannot exceed $MaxIdLength characters." }
        require(SafeIdentifierPattern.matches(normalized)) {
            "$field may contain only letters, numbers, dots, underscores, colons, and dashes."
        }
        return normalized
    }

    fun optionalIdentifier(value: String?, field: String): String? =
        value?.takeIf { it.isNotBlank() }?.let { requiredIdentifier(it, field) }

    fun requiredIdempotencyKey(value: String, field: String = "idempotencyKey"): String {
        requireNoControlChars(value, field)
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "$field is required." }
        require(normalized.length <= MaxIdempotencyKeyLength) { "$field cannot exceed $MaxIdempotencyKeyLength characters." }
        require(IdempotencyKeyPattern.matches(normalized)) {
            "$field must be 8 to 128 characters and may contain only letters, numbers, dots, underscores, colons, and dashes."
        }
        return normalized
    }

    fun optionalIdempotencyKey(value: String?, field: String = "idempotencyKey"): String? =
        value?.takeIf { it.isNotBlank() }?.let { requiredIdempotencyKey(it, field) }

    fun optionalShortText(value: String?, field: String, maxLength: Int = MaxShortTextLength): String? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        requireNoControlChars(normalized, field)
        require(normalized.length <= maxLength) { "$field cannot exceed $maxLength characters." }
        return normalized
    }

    fun requiredShortText(value: String, field: String, maxLength: Int = MaxShortTextLength): String {
        val normalized = optionalShortText(value, field, maxLength)
        require(!normalized.isNullOrBlank()) { "$field is required." }
        return normalized
    }

    fun optionalLongText(value: String?, field: String, maxLength: Int = MaxLongTextLength): String? =
        optionalShortText(value, field, maxLength)

    fun requiredLongText(value: String, field: String, maxLength: Int = MaxLongTextLength): String {
        val normalized = optionalLongText(value, field, maxLength)
        require(!normalized.isNullOrBlank()) { "$field is required." }
        return normalized
    }

    fun optionalLanguageTag(value: String?, field: String = "language"): String? {
        val normalized = optionalShortText(value, field, 32) ?: return null
        require(LanguageTagPattern.matches(normalized)) { "$field must be a valid language tag." }
        return normalized
    }

    private fun requireNoControlChars(value: String, field: String) {
        require(value.none { it.isISOControl() }) { "$field cannot contain control characters." }
    }

    private val GmailDomains = setOf("gmail.com", "googlemail.com")
}
