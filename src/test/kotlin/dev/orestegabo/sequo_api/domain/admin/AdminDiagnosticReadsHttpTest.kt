package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.notification.*
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:admin_diagnostic_reads;DB_CLOSE_DELAY=-1",
])
class AdminDiagnosticReadsHttpTest @Autowired constructor(
    private val users: UserRepository,
    private val outbox: NotificationOutboxRepository,
    private val requests: AccountDeletionRequestRepository,
    private val jwt: JwtService,
) {
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private lateinit var admin: User

    @BeforeEach
    fun fixture() {
        outbox.deleteAll(); requests.deleteAll(); users.deleteAll()
        admin = users.save(User(email = "diagnostic-admin@fixture.invalid", roles = mutableSetOf(RoleCode.ADMIN)))
    }

    @Test
    fun metadataReadsRedactPayloadCredentialsAndPrivateDeletionTextWithoutChangingQueues() {
        val event = outbox.save(NotificationOutbox(eventId = "diagnostic-event", eventType = NotificationEventType.ORDER_CREATED,
            aggregateType = "ORDER", aggregateId = "fixture-order", payload = "private-payload-token-and-phone", lockedBy = "private-worker-identity"))
        val request = requests.save(AccountDeletionRequestRecord(userId = admin.id!!, reason = "private-deletion-reason",
            confirmation = "private-confirmation-text"))
        val paths = listOf("notification-outbox", "notification-outbox/${event.id}", "account-deletion-requests", "account-deletion-requests/${request.id}")
        paths.forEach { path ->
            val response = get(path)
            assertEquals(200, response.statusCode(), response.body())
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow())
            listOf("private-payload", "private-worker", "private-deletion", "private-confirmation").forEach { sentinel ->
                assertFalse(response.body().contains(sentinel), "$path/$sentinel")
            }
        }
        assertEquals("PENDING", json.readTree(get("notification-outbox/${event.id}").body())["status"].asText())
        assertEquals("REQUESTED", json.readTree(get("account-deletion-requests/${request.id}").body())["status"].asText())
        assertEquals(NotificationOutboxStatus.PENDING, outbox.findById(event.id!!).orElseThrow().status)
        assertEquals(AccountDeletionRequestStatus.REQUESTED, requests.findById(request.id!!).orElseThrow().status)
        assertEquals(404, get("notification-outbox/guessed-event").statusCode())
        assertEquals(404, get("account-deletion-requests/guessed-request").statusCode())
    }

    @Test
    fun queuesAndDetailsAreAdminOnlyAndListLimitIsBounded() {
        repeat(3) { outbox.save(NotificationOutbox(eventId = "event-$it", eventType = NotificationEventType.ORDER_CREATED, aggregateType = "ORDER", aggregateId = "order-$it")) }
        val selected = outbox.findAll().first().id!!
        val request = requests.save(AccountDeletionRequestRecord(userId = admin.id!!, confirmation = "DELETE"))
        val paths = listOf("notification-outbox", "notification-outbox/$selected", "account-deletion-requests", "account-deletion-requests/${request.id}")
        for (path in paths) {
            assertTrue(get(path, null).statusCode() in setOf(401, 403), path)
            for (role in listOf(RoleCode.CUSTOMER, RoleCode.COURIER, RoleCode.RELAY_PARTNER, RoleCode.MERCHANT_OWNER, RoleCode.SUPPORT_AGENT)) {
                assertEquals(403, get(path, token(setOf(role))).statusCode(), "$path/$role")
            }
        }
        assertEquals(1, json.readTree(get("notification-outbox?limit=1").body()).size())
        assertEquals(3, json.readTree(get("notification-outbox?limit=999999").body()).size())
    }

    private fun token(roles: Set<RoleCode> = setOf(RoleCode.ADMIN)) = jwt.generateAccessToken(UserSession(admin.id!!, null, AuthProvider.EMAIL, roles))
    private fun get(path: String, bearer: String? = token()): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/admin/web/$path"))
        bearer?.let { request.header("Authorization", "Bearer $it") }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }
}
