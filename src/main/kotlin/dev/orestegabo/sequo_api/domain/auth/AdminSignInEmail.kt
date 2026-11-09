package dev.orestegabo.sequo_api.domain.auth

import org.springframework.core.io.ClassPathResource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Static, self-contained branding: no remote images, fonts, tracking pixels or code-bearing links. */
internal object AdminSignInEmail {
    private val template = ClassPathResource("email/admin-sign-in.html").inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    private val logo = Base64.getEncoder().encodeToString(ClassPathResource("email/sequo-logo.png").inputStream.use { it.readBytes() })
    private val expiryFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd · HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    fun html(code: String, expiresAt: Instant): String {
        require(code.matches(Regex("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{3}-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{3}")))
        return template.replace("{{CODE}}", code).replace("{{EXPIRY}}", expiryFormat.format(expiresAt))
    }

    fun attachments(): List<Map<String, String>> = listOf(mapOf(
        "filename" to "sequo-logo.png",
        "content_type" to "image/png",
        "content_id" to "sequo-logo",
        "content" to logo,
    ))
}
