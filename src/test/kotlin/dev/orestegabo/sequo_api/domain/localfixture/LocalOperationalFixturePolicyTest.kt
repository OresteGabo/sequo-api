package dev.orestegabo.sequo_api.domain.localfixture

import dev.orestegabo.sequo_api.domain.auth.DevelopmentAdminAccessPolicy
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import kotlin.test.*

class LocalOperationalFixturePolicyTest {
    private fun environment() = MockEnvironment().apply {
        setActiveProfiles("local")
        setProperty("sequo.admin.local-access.enabled", "true")
        setProperty("sequo.admin.local-access.environment", "LOCAL_FIXTURE")
        setProperty("sequo.admin.local-access.user-id", "fixture-admin")
        setProperty("server.address", "127.0.0.1")
        setProperty("server.forward-headers-strategy", "NONE")
        setProperty("sequo.security.cors.allowed-origins", "http://127.0.0.1:5173")
        setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55924/sequo_admin_dev")
        setProperty("sequo.auth.jwt.issuer", "sequo-api-local-admin-fixture")
        setProperty("sequo.auth.jwt.audience", "sequo-admin-local-fixture")
        setProperty("sequo.notifications.outbox-worker.enabled", "false")
        setProperty("sequo.notifications.provider-delivery-worker.enabled", "false")
        setProperty("sequo.admin.local-fixtures.enabled", "true")
    }
    @Test fun startupRequiresStrictLocalIsolationAndNoProviderCredentialsOrSendingWorkers() {
        for (change in listOf<(MockEnvironment) -> Unit>(
            { it.setActiveProfiles("production") }, { it.setActiveProfiles("local", "staging") },
            { it.setProperty("sequo.admin.local-access.enabled", "false") },
            { it.setProperty("spring.datasource.url", "jdbc:postgresql://remote.example/sequo_admin_dev") },
            { it.setProperty("sequo.wallets.yas-togo.api-key", "some-configured-value") },
            { it.setProperty("sequo.wallets.moov-africa.webhook-secret", "some-configured-value") },
            { it.setProperty("sequo.notifications.provider-delivery-worker.enabled", "true") },
            { it.setProperty("sequo.payments.simulator.enabled", "true") },
        )) {
            val env = environment().also(change)
            assertFailsWith<IllegalStateException> { LocalOperationalFixturePolicy(DevelopmentAdminAccessPolicy(env), env) }
        }
        val env = environment().apply { setProperty("sequo.admin.local-fixtures.enabled", "false"); setProperty("sequo.admin.local-access.enabled", "false"); setActiveProfiles("production") }
        assertFalse(LocalOperationalFixturePolicy(DevelopmentAdminAccessPolicy(env), env).enabled)
    }
    @Test fun requestMustBeDirectLoopbackWithTheConfiguredBrowserOrigin() {
        val env = environment(); val p = LocalOperationalFixturePolicy(DevelopmentAdminAccessPolicy(env), env)
        val request = MockHttpServletRequest().apply { remoteAddr = "127.0.0.1"; serverName = "127.0.0.1"; addHeader("Origin", "http://127.0.0.1:5173") }
        assertTrue(p.allows(request)); request.addHeader("X-Forwarded-For", "127.0.0.1"); assertFalse(p.allows(request))
        request.removeHeader("X-Forwarded-For"); request.removeHeader("Origin"); assertFalse(p.allows(request))
        request.addHeader("Origin", "http://127.0.0.1:5173"); request.remoteAddr = "192.0.2.4"; assertFalse(p.allows(request))
    }
    @Test fun storedManifestRoundTripsPreciseTimesAndSyntheticCredentialsWithoutJacksonDateModules() {
        val m = LocalFixtureManifest(seededAt = Instant.parse("2026-11-08T12:34:56.123456Z"),
            accounts = listOf(LocalFixtureAccount("local-ops-v1-person", "[TEST] Person", "person@fixture.invalid", listOf("CUSTOMER"), "Practice")),
            scenarios = listOf(LocalFixtureScenario("local-ops-v1-case", "[TEST] Case", "No funds move", "returns", mapOf("returnId" to "local-ops-v1-return"), listOf("Read"), mapOf("returnPin" to "123456"))))
        assertEquals(m, LocalFixtureManifestCodec.decode(LocalFixtureManifestCodec.encode(m)))
        assertFails { LocalFixtureManifestCodec.decode("{\"environment\":\"PRODUCTION\"}") }
    }
}
