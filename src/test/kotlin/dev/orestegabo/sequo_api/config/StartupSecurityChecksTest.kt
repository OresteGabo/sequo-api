package dev.orestegabo.sequo_api.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupSecurityChecksTest {
    @Test
    fun localProfileAllowsDevelopmentDefaults() {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = emptySet(),
                jwtSecret = "sequo_auth_dev_secret_key_2026_v1",
                notificationTokenEncryptionSecret = "sequo_notifications_dev_encryption_key_2026_change_before_prod",
                googleClientId = "google_dev_client_id",
                facebookAppId = "facebook_dev_app_id",
                appleClientId = "apple_dev_client_id",
                datasourceUrl = "jdbc:h2:mem:sequodb",
                datasourcePassword = "sequo_dev_password",
                hibernateDdlAuto = "update",
                h2ConsoleEnabled = true,
            )
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun dockerProfileIsStrictByDefault() {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("docker"),
                jwtSecret = "sequo_compose_dev_secret_key_2026_change_before_prod",
            )
        )

        assertTrue(findings.any { it.contains("sequo.auth.jwt.secret") })
    }

    @Test
    fun explicitDevelopmentAllowanceBypassesDockerStrictness() {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("docker"),
                allowDevDefaults = true,
                jwtSecret = "sequo_compose_dev_secret_key_2026_change_before_prod",
                notificationTokenEncryptionSecret = "sequo_compose_notification_token_secret_2026_change_before_prod",
                googleClientId = "google_docker_dev_client_id",
                facebookAppId = "facebook_docker_dev_app_id",
                appleClientId = "apple_docker_dev_client_id",
                datasourceUrl = "jdbc:postgresql://postgres:5432/sequo",
                datasourcePassword = "sequo_dev_password",
            )
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun productionProfileCannotBypassStrictnessWithDevelopmentAllowance() {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("prod"),
                allowDevDefaults = true,
                jwtSecret = "sequo_compose_dev_secret_key_2026_change_before_prod",
                notificationTokenEncryptionSecret = "sequo_compose_notification_token_secret_2026_change_before_prod",
                googleClientId = "google_docker_dev_client_id",
                facebookAppId = "facebook_docker_dev_app_id",
                appleClientId = "apple_docker_dev_client_id",
                datasourceUrl = "jdbc:h2:mem:sequodb",
                datasourcePassword = "sequo_dev_password",
                hibernateDdlAuto = "update",
                h2ConsoleEnabled = true,
            )
        )

        assertTrue(findings.any { it.contains("sequo.auth.jwt.secret") })
        assertTrue(findings.any { it.contains("sequo.notifications.token-encryption-secret") })
        assertTrue(findings.any { it.contains("spring.datasource.url") })
    }

    @Test
    fun productionProfileRejectsUnsafeAuthAndNotificationConfig() {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("prod"),
                jwtSecret = "short",
                notificationTokenEncryptionSecret = "sequo_notifications_dev_encryption_key_2026_change_before_prod",
                googleClientId = "google_dev_client_id",
                facebookAppId = "facebook_dev_app_id",
                appleClientId = "apple_dev_client_id",
                yasTogoApiKey = "yas_togo_dev_api_key_2026_change_before_prod",
                yasTogoWebhookSecret = "yas_togo_dev_webhook_secret_2026_change_before_prod",
                moovAfricaApiKey = "moov_africa_dev_api_key_2026_change_before_prod",
                moovAfricaWebhookSecret = "moov_africa_dev_webhook_secret_2026_change_before_prod",
            )
        )

        assertTrue(findings.any { it.contains("sequo.auth.jwt.secret") })
        assertTrue(findings.any { it.contains("sequo.notifications.token-encryption-secret") })
        assertTrue(findings.any { it.contains("sequo.auth.google.client-id") })
        assertTrue(findings.any { it.contains("sequo.auth.facebook.app-id") })
        assertTrue(findings.any { it.contains("sequo.auth.apple.client-id") })
        assertTrue(findings.any { it.contains("sequo.wallets.yas-togo.api-key") })
        assertTrue(findings.any { it.contains("sequo.wallets.yas-togo.webhook-secret") })
        assertTrue(findings.any { it.contains("sequo.wallets.moov-africa.api-key") })
        assertTrue(findings.any { it.contains("sequo.wallets.moov-africa.webhook-secret") })
    }

    @Test
    fun productionProfileRejectsUnsafeDatabaseConfig() {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("production"),
                datasourceUrl = "jdbc:h2:mem:sequodb",
                hibernateDdlAuto = "update",
                h2ConsoleEnabled = true,
            )
        )

        assertTrue(findings.any { it.contains("spring.datasource.url") })
        assertTrue(findings.any { it.contains("spring.jpa.hibernate.ddl-auto") })
        assertTrue(findings.any { it.contains("spring.h2.console.enabled") })
    }

    @Test
    fun productionProfileRejectsMissingWildcardOrNonHttpsCorsOrigins() {
        val missingFindings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("prod"),
                corsAllowedOrigins = emptyList(),
            )
        )
        val wildcardFindings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("prod"),
                corsAllowedOrigins = listOf("https://app.sequo.example", "*"),
            )
        )
        val insecureFindings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("prod"),
                corsAllowedOrigins = listOf("http://app.sequo.example"),
            )
        )

        assertTrue(missingFindings.any { it.contains("sequo.security.cors.allowed-origins") })
        assertTrue(wildcardFindings.any { it.contains("wildcard origins") })
        assertTrue(insecureFindings.any { it.contains("HTTPS origins") })
    }

    @Test
    fun productionProfileRejectsTrackedPlaceholderValues() {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("prod"),
                googleClientId = "1234567890-sequo.apps.googleusercontent.com",
                facebookAppId = "123456789012345",
                corsAllowedOrigins = listOf("https://app.sequo.example"),
                datasourceUrl = "jdbc:postgresql://localhost:5432/sequo",
                datasourcePassword = "sequo_dev_password",
            )
        )

        assertTrue(findings.any { it.contains("sequo.auth.google.client-id") })
        assertTrue(findings.any { it.contains("sequo.auth.facebook.app-id") })
        assertTrue(findings.any { it.contains("sequo.security.cors.allowed-origins") })
        assertTrue(findings.any { it.contains("spring.datasource.url") })
        assertTrue(findings.any { it.contains("spring.datasource.password") })
    }

    @Test
    fun productionProfileAcceptsExplicitHttpsNonPlaceholderCorsOrigins() {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(
                activeProfiles = setOf("prod"),
                corsAllowedOrigins = listOf(
                    "https://app.sequo.tg",
                    "https://admin.sequo.tg",
                ),
            )
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun productionProfileAcceptsSecureConfig() {
        val findings = StartupSecurityChecks.unsafeProductionFindings(
            secureSettings(activeProfiles = setOf("prod"))
        )

        assertFalse(findings.isNotEmpty())
    }

    private fun secureSettings(
        activeProfiles: Set<String> = setOf("prod"),
        allowDevDefaults: Boolean = false,
        jwtSecret: String? = "realistic_prod_jwt_secret_2026_value_64_chars_minimum",
        notificationTokenEncryptionSecret: String? = "realistic_notification_secret_2026_value_64_chars_minimum",
        googleClientId: String? = "7643198250-prod.apps.googleusercontent.com",
        facebookAppId: String? = "581049273650184",
        appleClientId: String? = "com.sequo.service.signin.production",
        yasTogoApiKey: String? = "realistic_yas_togo_api_key_2026_value_64_chars_minimum",
        yasTogoWebhookSecret: String? = "realistic_yas_togo_webhook_secret_2026_value_64_chars_minimum",
        moovAfricaApiKey: String? = "realistic_moov_africa_api_key_2026_value_64_chars_minimum",
        moovAfricaWebhookSecret: String? = "realistic_moov_africa_webhook_secret_2026_value_64_chars_minimum",
        corsAllowedOrigins: List<String> = listOf("https://app.sequo.tg"),
        datasourceUrl: String? = "jdbc:postgresql://postgres:5432/sequo",
        datasourceUsername: String? = "sequo_app",
        datasourcePassword: String? = "realistic_database_password_2026_value_64_chars_minimum",
        hibernateDdlAuto: String? = "validate",
        h2ConsoleEnabled: Boolean = false,
    ): StartupSecuritySettings =
        StartupSecuritySettings(
            activeProfiles = activeProfiles,
            allowDevDefaults = allowDevDefaults,
            jwtSecret = jwtSecret,
            notificationTokenEncryptionSecret = notificationTokenEncryptionSecret,
            googleClientId = googleClientId,
            facebookAppId = facebookAppId,
            appleClientId = appleClientId,
            yasTogoApiKey = yasTogoApiKey,
            yasTogoWebhookSecret = yasTogoWebhookSecret,
            moovAfricaApiKey = moovAfricaApiKey,
            moovAfricaWebhookSecret = moovAfricaWebhookSecret,
            corsAllowedOrigins = corsAllowedOrigins,
            datasourceUrl = datasourceUrl,
            datasourceUsername = datasourceUsername,
            datasourcePassword = datasourcePassword,
            hibernateDdlAuto = hibernateDdlAuto,
            h2ConsoleEnabled = h2ConsoleEnabled,
        )
}
