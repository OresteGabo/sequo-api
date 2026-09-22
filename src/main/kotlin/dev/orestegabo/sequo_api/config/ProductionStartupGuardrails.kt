package dev.orestegabo.sequo_api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

data class StartupSecuritySettings(
    val activeProfiles: Set<String>,
    val allowDevDefaults: Boolean,
    val jwtSecret: String?,
    val notificationTokenEncryptionSecret: String?,
    val corsAllowedOrigins: List<String>,
    val datasourceUrl: String?,
    val datasourceUsername: String?,
    val datasourcePassword: String?,
    val hibernateDdlAuto: String?,
    val h2ConsoleEnabled: Boolean,
)

object StartupSecurityChecks {
    fun unsafeProductionFindings(settings: StartupSecuritySettings): List<String> {
        if (!requiresStrictSecurity(settings)) return emptyList()

        val findings = mutableListOf<String>()
        requireSecret(
            propertyName = "sequo.auth.jwt.secret",
            value = settings.jwtSecret,
            knownUnsafeValues = KNOWN_UNSAFE_JWT_SECRETS,
            findings = findings,
        )
        requireSecret(
            propertyName = "sequo.notifications.token-encryption-secret",
            value = settings.notificationTokenEncryptionSecret,
            knownUnsafeValues = KNOWN_UNSAFE_NOTIFICATION_SECRETS,
            findings = findings,
        )
        requireSafeCorsSettings(settings.corsAllowedOrigins, findings)
        requireSafeDatabaseSettings(settings, findings)
        requireNoTrackedPlaceholders(settings, findings)

        return findings
    }

    fun requiresStrictSecurity(settings: StartupSecuritySettings): Boolean {
        val activeProfiles = settings.activeProfiles.map { it.lowercase() }.toSet()
        if (settings.allowDevDefaults) return false
        return activeProfiles.any { it in STRICT_SECURITY_PROFILES }
    }

    private fun requireSecret(
        propertyName: String,
        value: String?,
        knownUnsafeValues: Set<String>,
        findings: MutableList<String>,
    ) {
        val normalized = value.orEmpty().trim()
        if (normalized.length < MIN_SECRET_LENGTH || normalized in knownUnsafeValues) {
            findings += "$propertyName must be set to a non-default secret of at least $MIN_SECRET_LENGTH characters."
        }
    }

    private fun requireSafeDatabaseSettings(
        settings: StartupSecuritySettings,
        findings: MutableList<String>,
    ) {
        val datasourceUrl = settings.datasourceUrl.orEmpty().trim()
        val hibernateDdlAuto = settings.hibernateDdlAuto.orEmpty().trim().lowercase()

        if (datasourceUrl.isBlank()) {
            findings += "spring.datasource.url must be configured."
        }
        if (datasourceUrl.lowercase().startsWith("jdbc:h2:")) {
            findings += "spring.datasource.url must not use H2 in production-like profiles."
        }
        if (hibernateDdlAuto in UNSAFE_DDL_AUTO_VALUES) {
            findings += "spring.jpa.hibernate.ddl-auto must not be '$hibernateDdlAuto' in production-like profiles."
        }
        if (settings.h2ConsoleEnabled) {
            findings += "spring.h2.console.enabled must be false in production-like profiles."
        }
    }

    private fun requireNoTrackedPlaceholders(
        settings: StartupSecuritySettings,
        findings: MutableList<String>,
    ) {
        mapOf(
            "sequo.auth.jwt.secret" to listOf(settings.jwtSecret),
            "sequo.notifications.token-encryption-secret" to listOf(settings.notificationTokenEncryptionSecret),
            "sequo.security.cors.allowed-origins" to settings.corsAllowedOrigins,
            "spring.datasource.url" to listOf(settings.datasourceUrl),
            "spring.datasource.username" to listOf(settings.datasourceUsername),
            "spring.datasource.password" to listOf(settings.datasourcePassword),
        ).forEach { (propertyName, values) ->
            values.filterNotNull().forEach { value ->
                val normalized = value.trim().lowercase()
                if (TRACKED_PLACEHOLDER_MARKERS.any { it in normalized }) {
                    findings += "$propertyName must not contain tracked placeholder, test, local, or example values in production-like profiles."
                }
            }
        }
    }

    private fun requireSafeCorsSettings(
        allowedOrigins: List<String>,
        findings: MutableList<String>,
    ) {
        val normalizedOrigins = allowedOrigins.map { it.trim() }.filter { it.isNotBlank() }
        if (normalizedOrigins.isEmpty()) {
            findings += "sequo.security.cors.allowed-origins must list explicit HTTPS origins for production-like profiles."
            return
        }

        normalizedOrigins.forEach { origin ->
            if (origin == "*" || origin.contains("*")) {
                findings += "sequo.security.cors.allowed-origins must not contain wildcard origins in production-like profiles."
            }
            if (!origin.startsWith("https://")) {
                findings += "sequo.security.cors.allowed-origins must use HTTPS origins in production-like profiles."
            }
        }
    }

    private const val MIN_SECRET_LENGTH = 32
    private val STRICT_SECURITY_PROFILES = setOf("docker", "prod", "production", "stage", "staging")
    private val UNSAFE_DDL_AUTO_VALUES = setOf("create", "create-drop", "update")
    private val KNOWN_UNSAFE_JWT_SECRETS = setOf(
        "sequo_auth_dev_secret_key_2026_v1",
        "sequo_compose_dev_secret_key_2026_change_before_prod",
        "ci_only_sequo_auth_secret_2026_change_me",
    )
    private val KNOWN_UNSAFE_NOTIFICATION_SECRETS = setOf(
        "sequo_notifications_dev_encryption_key_2026_change_before_prod",
        "sequo_compose_notification_token_secret_2026_change_before_prod",
        "ci_only_sequo_notification_secret_2026_change_me",
    )
    private val TRACKED_PLACEHOLDER_MARKERS = setOf(
        ".example",
        ".test",
        ".local",
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "sequo_dev_password",
        "replace_this",
        "change_before_prod",
        "change_me",
        "dev_client_id",
        "docker_dev",
        "ci_only",
        "1234567890-sequo.apps.googleusercontent.com",
        "123456789012345",
    )
}

@Component
class ProductionStartupGuardrails(
    private val environment: Environment,
    @Value("\${sequo.security.allow-dev-defaults:false}")
    private val allowDevDefaults: Boolean,
    @Value("\${sequo.auth.jwt.secret:}")
    private val jwtSecret: String?,
    @Value("\${sequo.notifications.token-encryption-secret:}")
    private val notificationTokenEncryptionSecret: String?,
    @Value("\${sequo.security.cors.allowed-origins:}")
    private val corsAllowedOrigins: List<String>,
    @Value("\${spring.datasource.url:}")
    private val datasourceUrl: String?,
    @Value("\${spring.datasource.username:}")
    private val datasourceUsername: String?,
    @Value("\${spring.datasource.password:}")
    private val datasourcePassword: String?,
    @Value("\${spring.jpa.hibernate.ddl-auto:}")
    private val hibernateDdlAuto: String?,
    @Value("\${spring.h2.console.enabled:false}")
    private val h2ConsoleEnabled: Boolean,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            StartupSecuritySettings(
                activeProfiles = environment.activeProfiles.toSet(),
                allowDevDefaults = allowDevDefaults,
                jwtSecret = jwtSecret,
                notificationTokenEncryptionSecret = notificationTokenEncryptionSecret,
                corsAllowedOrigins = corsAllowedOrigins,
                datasourceUrl = datasourceUrl,
                datasourceUsername = datasourceUsername,
                datasourcePassword = datasourcePassword,
                hibernateDdlAuto = hibernateDdlAuto,
                h2ConsoleEnabled = h2ConsoleEnabled,
            )
        )

        check(findings.isEmpty()) {
            "Unsafe production configuration: ${findings.joinToString(" ")}"
        }
    }
}
