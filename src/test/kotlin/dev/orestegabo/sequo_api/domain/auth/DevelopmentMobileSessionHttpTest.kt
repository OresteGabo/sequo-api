package dev.orestegabo.sequo_api.domain.auth

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.localfixture.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.*

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:sequo_admin_dev_mobile_http;DB_CLOSE_DELAY=-1",
    "server.address=127.0.0.1", "server.forward-headers-strategy=NONE",
    "sequo.admin.local-access.enabled=true", "sequo.admin.local-access.environment=LOCAL_FIXTURE",
    "sequo.admin.local-access.user-id=local-mobile-admin", "sequo.admin.local-fixtures.enabled=true",
    "sequo.auth.local-mobile-access.enabled=true", "sequo.security.cors.allowed-origins=http://127.0.0.1:5173",
    "sequo.auth.jwt.issuer=sequo-api-local-admin-fixture", "sequo.auth.jwt.audience=sequo-admin-local-fixture",
    "sequo.notifications.outbox-worker.enabled=false", "sequo.notifications.provider-delivery-worker.enabled=false",
    "sequo.wallets.yas-togo.api-base-url=", "sequo.wallets.yas-togo.api-key=", "sequo.wallets.yas-togo.merchant-account-id=", "sequo.wallets.yas-togo.webhook-secret=",
    "sequo.wallets.moov-africa.api-base-url=", "sequo.wallets.moov-africa.api-key=", "sequo.wallets.moov-africa.merchant-account-id=", "sequo.wallets.moov-africa.webhook-secret=",
])
class DevelopmentMobileSessionHttpTest @Autowired constructor(
    private val jdbc: JdbcTemplate, private val foundation: LocalOperationalFixtureFoundation,
    private val runs: LocalOperationalFixtureRunRepository, private val jwt: JwtService,
    private val devices: UserDeviceRepository, private val limiter: AuthRateLimiter,
) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private val context = LocalOperationalFixtureContext("local-mobile-admin", Instant.now())

    @Test fun fixedFamiliesUseRealRoleScopedRevocableSessionsAndNoIdentitySelection() {
        install()
        val expected = listOf(
            Triple("SEQUO_APP", "ama", "CUSTOMER"), Triple("SEQUO_APP", "koffi", "CUSTOMER"),
            Triple("SEQUO_VENDOR", "yawa", "MERCHANT_OWNER"), Triple("SEQUO_VENDOR", "mawuli", "MERCHANT_OWNER"),
            Triple("SEQUO_RIDER", "senam", "COURIER"), Triple("SEQUO_RIDER", "komlan", "COURIER"),
            Triple("SEQUO_HUB", "dede", "RELAY_PARTNER"), Triple("SEQUO_HUB", "esso", "RELAY_PARTNER"))
        for ((family, key, role) in expected) {
            val access = call("GET", "/development-access?appSource=$family")
            assertEquals(200, access.statusCode()); assertEquals("no-store", access.headers().firstValue("cache-control").orElseThrow())
            val capability = json.readTree(access.body())
            assertTrue(capability["enabled"].asBoolean()); assertEquals("LOCAL_FIXTURE", capability["mode"].asText())
            assertEquals(2, capability["accounts"].size()); assertFalse(access.body().contains("@fixture.invalid"))
            val issued = call("POST", "/development-session", request(family, key))
            assertEquals(200, issued.statusCode(), issued.body())
            val session = json.readTree(issued.body())
            assertEquals(setOf(role), session["user"]["roles"].map { it.asText() }.toSet())
            val token = session["accessToken"].asText()
            assertEquals(session["user"]["id"].asText(), assertNotNull(jwt.parseAccessToken(token)).userId)
            assertEquals(200, call("GET", "/me", token = token).statusCode())
            assertEquals(403, call("GET", "/../admin/web/overview", token = token).statusCode())
            val appSource = if (family == "SEQUO_VENDOR") AppSource.SEQUO_HUB else AppSource.valueOf(family)
            assertEquals(appSource, devices.findByDeviceId("mobile-$key")!!.appSource)
            if (family == "SEQUO_VENDOR") assertEquals(1, session["user"]["merchantScopeIds"].size())
            val refresh = session["refreshToken"].asText()
            assertEquals(401, call("POST", "/refresh", """{"refreshToken":"$refresh","deviceId":"wrong"}""").statusCode())
            val rotated = call("POST", "/refresh", """{"refreshToken":"$refresh","deviceId":"mobile-$key"}""")
            assertEquals(200, rotated.statusCode(), rotated.body())
            assertEquals(401, call("POST", "/refresh", """{"refreshToken":"$refresh","deviceId":"mobile-$key"}""").statusCode())
            val rotatedRefresh = json.readTree(rotated.body())["refreshToken"].asText()
            assertEquals(204, call("POST", "/logout", """{"refreshToken":"$rotatedRefresh"}""").statusCode())
            assertEquals(401, call("POST", "/refresh", """{"refreshToken":"$rotatedRefresh","deviceId":"mobile-$key"}""").statusCode())
        }
        assertEquals(15L, jdbc.queryForObject("select count(*) from users", Long::class.java))
        assertEquals(404, call("POST", "/development-session", request("SEQUO_ADMIN", "ama")).statusCode())
        assertEquals(404, call("POST", "/development-session", request("SEQUO_RIDER", "ama")).statusCode())
        assertEquals(404, call("POST", "/development-session", """{"appSource":"SEQUO_APP","accountKey":"arbitrary","userId":"local-mobile-admin","roles":["SUPER_ADMIN"],"deviceId":"forged"}""").statusCode())
    }

    @Test fun noManifestAlteredIdentityScopeAndProxyCannotMintAndRateLimitRemainsActive() {
        install()
        val stored = runs.findById("local-ops-v1").orElseThrow()
        val raw = stored.manifestJson
        jdbc.update("update local_operational_fixture_runs set manifest_json=null where namespace='local-ops-v1'")
        assertFalse(json.readTree(call("GET", "/development-access?appSource=SEQUO_RIDER").body())["enabled"].asBoolean())
        assertEquals(404, call("POST", "/development-session", request("SEQUO_RIDER", "senam")).statusCode())
        jdbc.update("update local_operational_fixture_runs set manifest_json=? where namespace='local-ops-v1'", raw)
        assertEquals(404, call("POST", "/development-session", request("SEQUO_RIDER", "senam"), forwarded = true).statusCode())
        assertEquals(403, call("POST", "/development-session", request("SEQUO_RIDER", "senam"), origin = "https://unapproved.invalid").statusCode())
        jdbc.update("update users set display_name='Altered account' where id='local-ops-v1-rider-senam'")
        assertEquals(404, call("POST", "/development-session", request("SEQUO_RIDER", "senam")).statusCode())
        jdbc.update("update users set display_name=? where id='local-ops-v1-rider-senam'", foundation.accounts(context).single { it.id.endsWith("rider-senam") }.name)
        jdbc.update("update merchant_memberships set active=false where user_id='local-ops-v1-owner-yawa'")
        assertEquals(404, call("POST", "/development-session", request("SEQUO_VENDOR", "yawa")).statusCode())
        jdbc.update("update merchant_memberships set active=true where user_id='local-ops-v1-owner-yawa'")
        jdbc.update("update users set is_active=false where id='local-ops-v1-customer-koffi'")
        assertEquals(404, call("POST", "/development-session", request("SEQUO_APP", "koffi")).statusCode())
        jdbc.update("update users set is_active=true where id='local-ops-v1-customer-koffi'")
        limiter.resetForTests()
        repeat(20) { assertEquals(200, call("POST", "/development-session", request("SEQUO_APP", "ama")).statusCode()) }
        val blocked = call("POST", "/development-session", request("SEQUO_APP", "ama"))
        assertEquals(429, blocked.statusCode()); assertTrue(blocked.headers().firstValue("Retry-After").isPresent)
    }

    private fun install() {
        limiter.resetForTests()
        val accounts = foundation.seed(context)
        runs.saveAndFlush(LocalOperationalFixtureRun("local-ops-v1", context.createdAt,
            LocalFixtureManifestCodec.encode(LocalFixtureManifest(seededAt = context.createdAt, accounts = accounts))))
    }
    private fun request(family: String, key: String) = """{"appSource":"$family","accountKey":"$key","deviceId":"mobile-$key","deviceLabel":"Local native fixture"}"""
    private fun call(method: String, path: String, body: String? = null, token: String? = null, origin: String? = null,
        forwarded: Boolean = false): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/auth$path"))
        origin?.let { b.header("Origin", it) }; token?.let { b.header("Authorization", "Bearer $it") }
        if (forwarded) b.header("X-Forwarded-For", "127.0.0.1")
        if (body != null) b.header("Content-Type", "application/json")
        return client.send(b.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
}
