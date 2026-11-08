package dev.orestegabo.sequo_api.domain.auth

import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DevelopmentAdminAccessPolicyTest {
    private fun settings() = DevelopmentAdminAccessSettings(true, setOf("dev"), "LOCAL_FIXTURE", "127.0.0.1", "NONE",
        listOf("http://127.0.0.1:5173"), "jdbc:h2:mem:sequo_admin_dev_policy", "fixture-admin",
        DevelopmentAdminAccessChecks.Issuer, DevelopmentAdminAccessChecks.Audience)

    @Test
    fun configurationRequiresEveryIndependentLocalFixtureBoundary() {
        assertTrue(DevelopmentAdminAccessChecks.findings(settings()).isEmpty())
        val invalid = listOf(
            settings().copy(profiles = emptySet()),
            *listOf("prod", "production", "stage", "staging", "docker", "test", "future-profile").map {
                settings().copy(profiles = setOf("dev", it))
            }.toTypedArray(),
            settings().copy(fixtureEnvironment = "STAGING"),
            settings().copy(bindAddress = "0.0.0.0"),
            settings().copy(bindAddress = "localhost"),
            settings().copy(forwardHeadersStrategy = "FRAMEWORK"),
            settings().copy(forwardHeadersStrategy = ""),
            settings().copy(origins = emptyList()),
            settings().copy(origins = listOf("*")),
            settings().copy(origins = listOf("http://127.0.0.1:5173", "https://admin.sequo.tg")),
            settings().copy(origins = listOf("http://127.0.0.1.evil.invalid:5173")),
            settings().copy(origins = listOf("http://user@127.0.0.1:5173")),
            settings().copy(datasourceUrl = "jdbc:h2:file:sequo_admin_dev"),
            settings().copy(datasourceUrl = "jdbc:postgresql://127.0.0.1:5432/sequo_db"),
            settings().copy(datasourceUrl = "jdbc:postgresql://remote.invalid:5432/sequo_admin_dev"),
            settings().copy(userId = ""),
            settings().copy(jwtIssuer = "sequo-api"),
            settings().copy(jwtAudience = "sequo-mobile"),
        )
        invalid.forEach { assertTrue(DevelopmentAdminAccessChecks.findings(it).isNotEmpty(), it.toString()) }
        assertTrue(DevelopmentAdminAccessChecks.findings(settings().copy(
            profiles = setOf("postgres-local"), datasourceUrl = "jdbc:postgresql://localhost:5432/sequo_admin_dev_ui")).isEmpty())
    }

    @Test
    fun productionLikeProfilesRejectStartupEvenWithDevelopmentDefaultBypass() {
        listOf("prod", "production", "stage", "staging", "docker").forEach { profile ->
            ApplicationContextRunner().withUserConfiguration(GuardConfiguration::class.java)
                .withInitializer { it.environment.setActiveProfiles("dev", profile) }
                .withPropertyValues(*properties().map { "${it.key}=${it.value}" }.toTypedArray(),
                    "sequo.security.allow-dev-defaults=true")
                .run { context ->
                    assertNotNull(context.startupFailure, profile)
                    var cause: Throwable? = context.startupFailure
                    val messages = buildString { while (cause != null) { append(cause?.message); cause = cause?.cause } }
                    assertTrue(messages.contains("Unsafe local admin access configuration"), profile)
                }
        }
    }

    @Test
    fun requestRequiresDirectLoopbackPeerHostApprovedOriginAndNoProxyHeaders() {
        val environment = MockEnvironment().apply { setActiveProfiles("dev"); properties().forEach { (key, value) -> setProperty(key, value) } }
        val policy = DevelopmentAdminAccessPolicy(environment)
        fun request() = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"; serverName = "127.0.0.1"; addHeader("Origin", "http://127.0.0.1:5173")
        }
        assertTrue(policy.allows(request()))
        assertFalse(policy.allows(request().apply { remoteAddr = "10.0.0.15" }))
        assertFalse(policy.allows(request().apply { serverName = "127.0.0.1.evil.invalid" }))
        assertFalse(policy.allows(request().apply { removeHeader("Origin") }))
        assertFalse(policy.allows(request().apply { removeHeader("Origin"); addHeader("Origin", "null") }))
        listOf("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP").forEach { header ->
            assertFalse(policy.allows(request().apply { addHeader(header, "127.0.0.1") }), header)
        }
    }

    @Test
    fun disabledProductionControllerNeverCallsSessionServiceOrRateLimiter() {
        val environment = MockEnvironment().apply { setActiveProfiles("prod") }
        val policy = DevelopmentAdminAccessPolicy(environment)
        val sessions = mock(DevelopmentAdminSessionService::class.java)
        val limiter = mock(AuthRateLimiter::class.java)
        val controller = DevelopmentAdminAccessController(policy, sessions, limiter)
        val request = MockHttpServletRequest().apply { remoteAddr = "127.0.0.1"; serverName = "127.0.0.1" }
        assertFalse(requireNotNull(controller.access(request).body).enabled)
        assertEquals(404, controller.session(request, DevelopmentAdminAccessController.SessionRequest("browser")).statusCode.value())
        verifyNoInteractions(sessions, limiter)
    }

    private fun properties() = mapOf(
        "sequo.admin.local-access.enabled" to "true",
        "sequo.admin.local-access.environment" to "LOCAL_FIXTURE",
        "sequo.admin.local-access.user-id" to "fixture-admin",
        "server.address" to "127.0.0.1",
        "server.forward-headers-strategy" to "NONE",
        "sequo.security.cors.allowed-origins" to "http://127.0.0.1:5173",
        "spring.datasource.url" to "jdbc:h2:mem:sequo_admin_dev_policy",
        "sequo.auth.jwt.issuer" to DevelopmentAdminAccessChecks.Issuer,
        "sequo.auth.jwt.audience" to DevelopmentAdminAccessChecks.Audience,
    )

    @Configuration(proxyBeanMethods = false)
    @Import(DevelopmentAdminAccessPolicy::class)
    class GuardConfiguration
}
