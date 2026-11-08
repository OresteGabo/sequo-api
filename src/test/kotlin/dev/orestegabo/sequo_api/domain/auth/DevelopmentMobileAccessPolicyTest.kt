package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.domain.localfixture.LocalOperationalFixturePolicy
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.*

class DevelopmentMobileAccessPolicyTest {
    @Test fun disabledByDefaultAndNoBrowserFlagCanEnableIt() {
        val environment = MockEnvironment()
        val access = DevelopmentAdminAccessPolicy(environment)
        val policy = DevelopmentMobileAccessPolicy(access, LocalOperationalFixturePolicy(access, environment), environment)
        assertFalse(policy.enabled)
        assertFalse(policy.allows(request()))
    }
    @Test fun optInCannotBypassMissingGuardFixturesWorkersProvidersOrProductionProfile() {
        assertFailsWith<IllegalStateException> { create(MockEnvironment().withProperty("sequo.auth.local-mobile-access.enabled", "true")) }
        assertFailsWith<IllegalStateException> { create(environment().withProperty("sequo.admin.local-fixtures.enabled", "false")) }
        assertFailsWith<IllegalStateException> { create(environment().withProperty("sequo.notifications.outbox-worker.enabled", "true")) }
        assertFailsWith<IllegalStateException> { create(environment().withProperty("sequo.wallets.moov-africa.api-key", "not-a-real-key")) }
        for (profile in listOf("production", "staging", "test")) {
            val environment = environment(); environment.setActiveProfiles("dev", profile)
            environment.setProperty("sequo.security.allow-dev-defaults", "true")
            assertFailsWith<IllegalStateException> { create(environment) }
        }
        for ((key, value) in listOf("server.address" to "0.0.0.0", "spring.datasource.url" to "jdbc:postgresql://127.0.0.1/sequo_production",
            "sequo.auth.jwt.audience" to "sequo-mobile", "server.forward-headers-strategy" to "FRAMEWORK")) {
            assertFailsWith<IllegalStateException> { create(environment().withProperty(key, value)) }
        }
    }
    @Test fun nativeRequestsRequireLiteralLoopbackPeerAndHostWithNoForwarding() {
        val policy = create(environment())
        assertTrue(policy.allows(request()))
        assertTrue(policy.allows(request().apply { addHeader("Origin", "http://127.0.0.1:5173") }))
        assertFalse(policy.allows(request().apply { serverName = "localhost" }))
        assertFalse(policy.allows(request().apply { serverName = "10.0.2.2" }))
        assertFalse(policy.allows(request().apply { remoteAddr = "192.168.1.2" }))
        assertFalse(policy.allows(request().apply { addHeader("Origin", "https://foreign.invalid") }))
        for (header in listOf("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP")) {
            assertFalse(policy.allows(request().apply { addHeader(header, "127.0.0.1") }))
        }
    }
    private fun create(e: MockEnvironment): DevelopmentMobileAccessPolicy {
        val access = DevelopmentAdminAccessPolicy(e)
        return DevelopmentMobileAccessPolicy(access, LocalOperationalFixturePolicy(access, e), e)
    }
    private fun request() = MockHttpServletRequest().apply { remoteAddr = "127.0.0.1"; serverName = "127.0.0.1" }
    private fun environment() = MockEnvironment().apply {
        setActiveProfiles("local")
        setProperty("sequo.auth.local-mobile-access.enabled", "true")
        setProperty("sequo.admin.local-access.enabled", "true")
        setProperty("sequo.admin.local-fixtures.enabled", "true")
        setProperty("sequo.admin.local-access.environment", "LOCAL_FIXTURE")
        setProperty("sequo.admin.local-access.user-id", "fixture-admin")
        setProperty("server.address", "127.0.0.1")
        setProperty("server.forward-headers-strategy", "NONE")
        setProperty("spring.datasource.url", "jdbc:h2:mem:sequo_admin_dev_mobile_policy")
        setProperty("sequo.security.cors.allowed-origins", "http://127.0.0.1:5173")
        setProperty("sequo.auth.jwt.issuer", DevelopmentAdminAccessChecks.Issuer)
        setProperty("sequo.auth.jwt.audience", DevelopmentAdminAccessChecks.Audience)
        setProperty("sequo.notifications.outbox-worker.enabled", "false")
        setProperty("sequo.notifications.provider-delivery-worker.enabled", "false")
    }
}
