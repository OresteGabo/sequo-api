package dev.orestegabo.sequo_api.config

import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProductionStartupGuardrailsIntegrationTest {
    @Test
    fun productionProfileFailsApplicationStartupWithDevelopmentDefaults() {
        val failure = assertFailsWith<IllegalStateException> {
            startGuardrailContext(
                "spring.profiles.active=prod",
                "sequo.auth.jwt.secret=sequo_auth_dev_secret_key_2026_v1",
                "sequo.notifications.token-encryption-secret=sequo_notifications_dev_encryption_key_2026_change_before_prod",
                "sequo.auth.google.client-id=google_dev_client_id",
                "sequo.auth.facebook.app-id=facebook_dev_app_id",
                "sequo.auth.apple.client-id=apple_dev_client_id",
                "sequo.wallets.yas-togo.api-key=yas_togo_dev_api_key_2026_change_before_prod",
                "sequo.wallets.yas-togo.webhook-secret=yas_togo_dev_webhook_secret_2026_change_before_prod",
                "sequo.wallets.moov-africa.api-key=moov_africa_dev_api_key_2026_change_before_prod",
                "sequo.wallets.moov-africa.webhook-secret=moov_africa_dev_webhook_secret_2026_change_before_prod",
                "sequo.security.cors.allowed-origins=*",
                "spring.datasource.url=jdbc:h2:mem:sequodb",
                "spring.jpa.hibernate.ddl-auto=update",
                "spring.h2.console.enabled=true",
            )
        }

        val message = failure.fullMessage()
        assertTrue(message.contains("Unsafe production configuration"))
        assertTrue(message.contains("sequo.auth.jwt.secret"))
        assertTrue(message.contains("sequo.wallets.yas-togo.api-key"))
        assertTrue(message.contains("sequo.security.cors.allowed-origins"))
        assertTrue(message.contains("spring.datasource.url"))
    }

    @Test
    fun productionProfileIgnoresDevelopmentDefaultAllowance() {
        val failure = assertFailsWith<IllegalStateException> {
            startGuardrailContext(
                "spring.profiles.active=prod",
                "sequo.security.allow-dev-defaults=true",
                "sequo.auth.jwt.secret=sequo_compose_dev_secret_key_2026_change_before_prod",
                "sequo.notifications.token-encryption-secret=sequo_compose_notification_token_secret_2026_change_before_prod",
                "sequo.auth.google.client-id=google_docker_dev_client_id",
                "sequo.auth.facebook.app-id=facebook_docker_dev_app_id",
                "sequo.auth.apple.client-id=apple_docker_dev_client_id",
                "sequo.wallets.yas-togo.api-key=yas_togo_dev_api_key_2026_change_before_prod",
                "sequo.wallets.yas-togo.webhook-secret=yas_togo_dev_webhook_secret_2026_change_before_prod",
                "sequo.wallets.moov-africa.api-key=moov_africa_dev_api_key_2026_change_before_prod",
                "sequo.wallets.moov-africa.webhook-secret=moov_africa_dev_webhook_secret_2026_change_before_prod",
                "sequo.security.cors.allowed-origins=*",
                "spring.datasource.url=jdbc:h2:mem:sequodb",
                "spring.jpa.hibernate.ddl-auto=update",
                "spring.h2.console.enabled=true",
            )
        }

        assertTrue(failure.fullMessage().contains("Unsafe production configuration"))
    }

    @Test
    fun dockerProfileCanUseExplicitDevelopmentDefaultsForLocalComposeAndCi() {
        startGuardrailContext(
            "spring.profiles.active=docker",
            "sequo.security.allow-dev-defaults=true",
            "sequo.auth.jwt.secret=sequo_compose_dev_secret_key_2026_change_before_prod",
            "sequo.notifications.token-encryption-secret=sequo_compose_notification_token_secret_2026_change_before_prod",
            "sequo.auth.google.client-id=google_docker_dev_client_id",
            "sequo.auth.facebook.app-id=facebook_docker_dev_app_id",
            "sequo.auth.apple.client-id=apple_docker_dev_client_id",
            "sequo.wallets.yas-togo.api-key=yas_togo_dev_api_key_2026_change_before_prod",
            "sequo.wallets.yas-togo.webhook-secret=yas_togo_dev_webhook_secret_2026_change_before_prod",
            "sequo.wallets.moov-africa.api-key=moov_africa_dev_api_key_2026_change_before_prod",
            "sequo.wallets.moov-africa.webhook-secret=moov_africa_dev_webhook_secret_2026_change_before_prod",
            "sequo.security.cors.allowed-origins=*",
            "spring.datasource.url=jdbc:postgresql://postgres:5432/sequo",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.h2.console.enabled=false",
        ).close()
    }

    @Test
    fun productionProfileStartsWithSecureSettings() {
        startGuardrailContext(
            "spring.profiles.active=prod",
            "sequo.auth.jwt.secret=realistic_prod_jwt_secret_2026_value_64_chars_minimum",
            "sequo.notifications.token-encryption-secret=realistic_notification_secret_2026_value_64_chars_minimum",
            "sequo.auth.google.client-id=1234567890-sequo.apps.googleusercontent.com",
            "sequo.auth.facebook.app-id=123456789012345",
            "sequo.auth.apple.client-id=com.sequo.service.signin",
            "sequo.wallets.yas-togo.api-key=realistic_yas_togo_api_key_2026_value_64_chars_minimum",
            "sequo.wallets.yas-togo.webhook-secret=realistic_yas_togo_webhook_secret_2026_value_64_chars_minimum",
            "sequo.wallets.moov-africa.api-key=realistic_moov_africa_api_key_2026_value_64_chars_minimum",
            "sequo.wallets.moov-africa.webhook-secret=realistic_moov_africa_webhook_secret_2026_value_64_chars_minimum",
            "sequo.security.cors.allowed-origins=https://app.sequo.example,https://admin.sequo.example",
            "spring.datasource.url=jdbc:postgresql://postgres:5432/sequo",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.h2.console.enabled=false",
        ).close()
    }

    private fun startGuardrailContext(vararg properties: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(GuardrailContext::class.java)
            .web(WebApplicationType.NONE)
            .run(*properties.map { "--$it" }.toTypedArray())

    private fun Throwable.fullMessage(): String =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")

    @Configuration(proxyBeanMethods = false)
    @Import(ProductionStartupGuardrails::class)
    private class GuardrailContext
}
