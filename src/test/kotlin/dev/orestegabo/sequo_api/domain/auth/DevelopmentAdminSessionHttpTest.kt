package dev.orestegabo.sequo_api.domain.auth

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:sequo_admin_dev_http;DB_CLOSE_DELAY=-1",
    "server.address=127.0.0.1", "server.forward-headers-strategy=NONE",
    "sequo.admin.local-access.enabled=true", "sequo.admin.local-access.environment=LOCAL_FIXTURE",
    "sequo.admin.local-access.user-id=00000000-0000-0000-0000-000000000091",
    "sequo.security.cors.allowed-origins=http://127.0.0.1:5173",
    "sequo.auth.jwt.secret=local_admin_http_signing_key_for_isolated_fixtures",
    "sequo.auth.jwt.issuer=sequo-api-local-admin-fixture", "sequo.auth.jwt.audience=sequo-admin-local-fixture",
])
class DevelopmentAdminSessionHttpTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val devices: UserDeviceRepository,
    private val users: UserRepository,
    private val jwt: JwtService,
    private val limiter: AuthRateLimiter,
) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private val fixtureId = "00000000-0000-0000-0000-000000000091"
    private val origin = "http://127.0.0.1:5173"

    @BeforeEach
    fun seedOnlyExplicitSyntheticFixtureAdmin() {
        limiter.resetForTests()
        devices.deleteAll()
        users.deleteAll()
        val now = Timestamp.from(Instant.now())
        jdbc.update("insert into users (id,email,display_name,is_active,provider,status,created_at,updated_at) values (?,?,?,?,?,?,?,?)",
            fixtureId, "local-admin@fixture.invalid", "Local fixture administrator", true, "EMAIL", "ACTIVE", now, now)
        jdbc.update("insert into user_roles (user_id,role_code) values (?,?)", fixtureId, "ADMIN")
    }

    @Test
    fun issuesOrdinaryRoleScopedTokensWithNormalRotationAndLogoutWithoutPromotingAccounts() {
        val status = send("GET", "/api/admin-auth/development-access")
        assertEquals(200, status.statusCode())
        assertTrue(json.readTree(status.body())["enabled"].asBoolean())
        assertEquals("no-store", status.headers().firstValue("Cache-Control").orElseThrow())
        val issued = send("POST", "/api/admin-auth/development-session", """{"deviceId":"local-browser","deviceLabel":"Fixture"}""")
        assertEquals(200, issued.statusCode(), issued.body())
        assertEquals("no-store", issued.headers().firstValue("Cache-Control").orElseThrow())
        val body = json.readTree(issued.body())
        assertEquals(fixtureId, body["user"]["id"].asText())
        assertEquals(setOf("ADMIN"), body["user"]["roles"].map { it.asText() }.toSet())
        val access = body["accessToken"].asText()
        val refresh = body["refreshToken"].asText()
        val session = assertNotNull(jwt.parseAccessToken(access))
        assertEquals(fixtureId, session.userId)
        assertEquals(setOf(RoleCode.ADMIN), session.roles)
        assertEquals(AppSource.SEQUO_ADMIN, devices.findByDeviceId("local-browser")?.appSource)
        assertEquals(200, send("GET", "/api/admin/web/overview", token = access).statusCode())
        val customer = jwt.generateAccessToken(UserSession("other-customer", null, AuthProvider.EMAIL, setOf(RoleCode.CUSTOMER)))
        assertEquals(403, send("GET", "/api/admin/web/overview", token = customer).statusCode())
        assertEquals(403, send("GET", "/api/admin/web/overview").statusCode())

        // Even a shared signing key cannot make local fixture tokens valid in an ordinary deployment.
        val ordinary = JwtService("local_admin_http_signing_key_for_isolated_fixtures", 60_000, 60_000, "sequo-api", "sequo-mobile")
        assertNull(ordinary.parseAccessToken(access))
        val wrongDevice = send("POST", "/api/auth/refresh", """{"refreshToken":"$refresh","deviceId":"other-browser"}""")
        assertEquals(401, wrongDevice.statusCode())
        val rotated = send("POST", "/api/auth/refresh", """{"refreshToken":"$refresh","deviceId":"local-browser"}""")
        assertEquals(200, rotated.statusCode(), rotated.body())
        val rotatedBody = json.readTree(rotated.body())
        assertEquals(setOf(RoleCode.ADMIN), assertNotNull(jwt.parseAccessToken(rotatedBody["accessToken"].asText())).roles)
        assertEquals(401, send("POST", "/api/auth/refresh", """{"refreshToken":"$refresh","deviceId":"local-browser"}""").statusCode())
        val rotatedRefresh = rotatedBody["refreshToken"].asText()
        assertEquals(204, send("POST", "/api/auth/logout", """{"refreshToken":"$rotatedRefresh"}""").statusCode())
        assertEquals(204, send("POST", "/api/auth/logout", """{"refreshToken":"$rotatedRefresh"}""").statusCode())
        assertEquals(401, send("POST", "/api/auth/refresh", """{"refreshToken":"$rotatedRefresh","deviceId":"local-browser"}""").statusCode())
        assertEquals(1L, users.count())
        assertEquals(setOf(RoleCode.ADMIN), users.findById(fixtureId).orElseThrow().roles)
    }

    @Test
    fun missingOriginForwardedRequestsAndUnapprovedOriginsCannotIssueSessions() {
        assertFalse(json.readTree(send("GET", "/api/admin-auth/development-access", requestOrigin = null).body())["enabled"].asBoolean())
        assertEquals(404, send("POST", "/api/admin-auth/development-session", """{"deviceId":"missing-origin"}""", requestOrigin = null).statusCode())
        assertEquals(404, send("POST", "/api/admin-auth/development-session", """{"deviceId":"proxy"}""", extraHeader = "X-Forwarded-For" to "127.0.0.1").statusCode())
        assertEquals(403, send("POST", "/api/admin-auth/development-session", """{"deviceId":"foreign-origin"}""", requestOrigin = "https://unapproved.invalid").statusCode())
        assertEquals(0L, devices.count())
    }

    @Test
    fun invalidBrowserInputAndInactiveNonAdminOrMissingFixtureNeverPromoteOrCreateUsers() {
        assertEquals(400, send("POST", "/api/admin-auth/development-session", "{}").statusCode())
        assertEquals(400, send("POST", "/api/admin-auth/development-session", """{"deviceId":"../invalid"}""").statusCode())
        jdbc.update("update users set is_active=false where id=?", fixtureId)
        assertEquals(404, send("POST", "/api/admin-auth/development-session", """{"deviceId":"inactive"}""").statusCode())
        jdbc.update("update users set is_active=true where id=?", fixtureId)
        jdbc.update("update user_roles set role_code='CUSTOMER' where user_id=?", fixtureId)
        assertEquals(404, send("POST", "/api/admin-auth/development-session", """{"deviceId":"not-admin","roles":["SUPER_ADMIN"]}""").statusCode())
        assertEquals(setOf(RoleCode.CUSTOMER), users.findById(fixtureId).orElseThrow().roles)
        assertEquals(0L, devices.count())
        users.deleteAll()
        assertEquals(404, send("POST", "/api/admin-auth/development-session", """{"deviceId":"missing-user"}""").statusCode())
        assertEquals(0L, users.count())
    }

    @Test
    fun repeatedEntryIsRateLimitedWithoutDisablingOtherAuthentication() {
        repeat(20) { assertEquals(200, send("POST", "/api/admin-auth/development-session", """{"deviceId":"repeat-browser"}""").statusCode()) }
        val blocked = send("POST", "/api/admin-auth/development-session", """{"deviceId":"repeat-browser"}""")
        assertEquals(429, blocked.statusCode())
        assertTrue(blocked.headers().firstValue("Retry-After").isPresent)
    }

    private fun send(method: String, path: String, body: String? = null, token: String? = null,
        requestOrigin: String? = origin, extraHeader: Pair<String, String>? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        requestOrigin?.let { request.header("Origin", it) }
        token?.let { request.header("Authorization", "Bearer $it") }
        extraHeader?.let { request.header(it.first, it.second) }
        if (body != null) request.header("Content-Type", "application/json")
        return client.send(request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
}
