package dev.orestegabo.sequo_api.domain.localfixture

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:sequo_admin_dev_fixture_http;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "server.address=127.0.0.1", "server.forward-headers-strategy=NONE",
    "sequo.admin.local-access.enabled=true", "sequo.admin.local-access.environment=LOCAL_FIXTURE",
    "sequo.admin.local-access.user-id=00000000-0000-0000-0000-000000000071", "sequo.admin.local-fixtures.enabled=true",
    "sequo.security.cors.allowed-origins=http://127.0.0.1:5173",
    "sequo.auth.jwt.issuer=sequo-api-local-admin-fixture", "sequo.auth.jwt.audience=sequo-admin-local-fixture",
    "sequo.notifications.outbox-worker.enabled=false", "sequo.notifications.provider-delivery-worker.enabled=false",
    "sequo.wallets.yas-togo.api-base-url=", "sequo.wallets.yas-togo.api-key=", "sequo.wallets.yas-togo.merchant-account-id=", "sequo.wallets.yas-togo.webhook-secret=",
    "sequo.wallets.moov-africa.api-base-url=", "sequo.wallets.moov-africa.api-key=", "sequo.wallets.moov-africa.merchant-account-id=", "sequo.wallets.moov-africa.webhook-secret=",
])
class LocalOperationalFixtureHttpTest @Autowired constructor(private val jdbc: JdbcTemplate, private val jwt: JwtService) {
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val admin = "00000000-0000-0000-0000-000000000071"
    private val client = HttpClient.newHttpClient()
    @Test fun fixedDatasetIsAuthenticatedLocalOnlyReplaySafeAndPreservesOperatorEdits() {
        jdbc.update("insert into users(id,email,display_name,is_active,provider,status,created_at,updated_at) values (?,?,?,true,'EMAIL','ACTIVE',current_timestamp,current_timestamp)",
            admin, "fixture-http-admin@fixture.invalid", "Local fixture admin")
        jdbc.update("insert into user_roles(user_id,role_code) values (?,?)", admin, "SUPER_ADMIN")
        jdbc.update("insert into local_operational_fixture_runs(namespace) values ('local-ops-v1')")
        val token = jwt.generateAccessToken(UserSession(admin, null, AuthProvider.EMAIL, setOf(RoleCode.SUPER_ADMIN)))
        assertTrue(call("", token).headers().firstValue("cache-control").orElse("").contains("no-store"))
        assertTrue(json.readTree(call("", token).body())["seededAt"].isNull)
        assertTrue(call("/seed", null, "{}").statusCode() in listOf(401, 403))
        assertEquals(404, call("/seed", token, "{}", origin = null).statusCode())
        assertEquals(404, call("/seed", token, "{}", forwarded = true).statusCode())
        val forged = jwt.generateAccessToken(UserSession("other-fixture", null, AuthProvider.EMAIL, setOf(RoleCode.SUPER_ADMIN)))
        assertEquals(404, call("/seed", forged, "{}").statusCode())
        assertEquals(400, call("/seed", token, "{\"amountCfa\":1}").statusCode())
        val first = call("/seed", token, "{}")
        assertEquals(200, first.statusCode(), first.body())
        val manifest = json.readTree(first.body())
        assertEquals("LOCAL_FIXTURE", manifest["environment"].asText())
        assertEquals(15, manifest["accounts"].size())
        assertTrue(manifest["accounts"].all { it["email"].asText().endsWith("@fixture.invalid") })
        assertEquals(26, manifest["scenarios"].size())
        assertEquals(26, manifest["scenarios"].map { it["id"].asText() }.toSet().size)
        assertTrue(manifest["scenarios"].single { it["id"].asText() == "local-ops-v1-scene-return-refund" }["summary"].asText().contains("NOT_SUBMITTED"))
        val before = jdbc.queryForObject("select count(*) from customer_orders", Long::class.java)
        jdbc.update("update users set is_active=false,status='SUSPENDED' where id='local-ops-v1-customer-koffi'")
        jdbc.update("update products set name='[TEST] Operator edit retained' where id='local-ops-v1-product-general'")
        val repeat = call("/seed", token, "{}")
        assertEquals(200, repeat.statusCode(), repeat.body())
        assertEquals(manifest, json.readTree(repeat.body()))
        assertEquals(before, jdbc.queryForObject("select count(*) from customer_orders", Long::class.java))
        assertEquals(false, jdbc.queryForObject("select is_active from users where id='local-ops-v1-customer-koffi'", Boolean::class.java))
        assertEquals("[TEST] Operator edit retained", jdbc.queryForObject("select name from products where id='local-ops-v1-product-general'", String::class.java))
        assertEquals(manifest, json.readTree(call("", token).body()))
        assertFalse(first.body().contains("accessToken")); assertFalse(first.body().contains("refreshToken"))
    }
    private fun call(path: String, token: String?, body: String? = null, origin: String? = "http://127.0.0.1:5173", forwarded: Boolean = false): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/admin/local-fixtures$path")).header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }; origin?.let { b.header("Origin", it) }
        if (forwarded) b.header("X-Forwarded-For", "127.0.0.1")
        if (body == null) b.GET() else b.POST(HttpRequest.BodyPublishers.ofString(body))
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }
}
