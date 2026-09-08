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
            )
        )

        assertTrue(findings.any { it.contains("sequo.auth.jwt.secret") })
        assertTrue(findings.any { it.contains("sequo.notifications.token-encryption-secret") })
        assertTrue(findings.any { it.contains("sequo.auth.google.client-id") })
        assertTrue(findings.any { it.contains("sequo.auth.facebook.app-id") })
        assertTrue(findings.any { it.contains("sequo.auth.apple.client-id") })
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
        googleClientId: String? = "1234567890-sequo.apps.googleusercontent.com",
        facebookAppId: String? = "123456789012345",
        appleClientId: String? = "com.sequo.service.signin",
        datasourceUrl: String? = "jdbc:postgresql://postgres:5432/sequo",
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
            datasourceUrl = datasourceUrl,
            hibernateDdlAuto = hibernateDdlAuto,
            h2ConsoleEnabled = h2ConsoleEnabled,
        )
}
