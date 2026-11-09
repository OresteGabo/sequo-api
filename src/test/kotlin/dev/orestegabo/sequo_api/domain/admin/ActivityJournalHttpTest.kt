package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.ResponseEntity
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.net.http.*
import java.util.UUID
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:activity_journal;DB_CLOSE_DELAY=-1",
])
@Import(ActivityJournalHttpTest.Fixture::class)
class ActivityJournalHttpTest @Autowired constructor(
    private val users: UserRepository, private val auth: AuthService, private val jwt: JwtService,
    private val records: ActivityJournalRepository, private val writer: ActivityJournalWriter,
    private val transactions: PlatformTransactionManager, private val authAudit: AdminAuthAuditService,
) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    @Test fun authenticatedMutationsRecordActorTargetAndOutcomeWithoutSecrets() {
        val user = user(RoleCode.ADMIN)
        val target = UUID.randomUUID().toString()
        val token = token(user)
        assertEquals(200, call("/api/activity-fixture/$target?password=QUERY_SECRET", token, "POST", "{\"code\":\"BODY_SECRET\"}").statusCode())
        val entries = records.findAll().filter { it.actorUserId == user.id }
        assertEquals(setOf("STARTED", "HTTP_SUCCEEDED"), entries.map { it.phase }.toSet())
        assertEquals(1, entries.map { it.operationId }.distinct().size)
        assertTrue(entries.all { it.action == "POST /api/activity-fixture/{userId}" && it.targetReference == "userId=$target" })
        assertTrue(entries.all { it.actorRoles.contains("ADMIN") })
        val history = call("/api/admin/web/activity?actorUserId=${user.id}", token)
        assertEquals(200, history.statusCode())
        assertTrue(history.headers().firstValue("cache-control").orElse("").contains("no-store"))
        assertEquals(2, json.readTree(history.body()).size())
        val operation = entries.first().operationId
        assertEquals(2, json.readTree(call("/api/admin/web/activity?operationId=$operation&since=2000-01-01T00:00:00Z&until=2100-01-01T00:00:00Z", token).body()).size())
        assertEquals(2, json.readTree(call("/api/admin/web/activity?targetReference=userId%3D$target", token).body()).size())
        assertEquals(0, json.readTree(call("/api/admin/web/activity?operationId=$operation&page=1", token).body()).size())
        assertFalse(history.body().contains("SECRET"))
        assertFalse(history.body().contains(token))
        // Reads do not create noise in the mutation history.
        assertEquals(2, records.findAll().count { it.actorUserId == user.id })
    }

    @Test fun rejectedMutationsAreNotPresentedAsSuccessfulChangesAndHistoryIsAdminOnly() {
        val user = user(RoleCode.CUSTOMER)
        val token = token(user)
        assertEquals(403, call("/api/activity-fixture/denied/reject", token, "DELETE").statusCode())
        val entries = records.findAll().filter { it.actorUserId == user.id }
        assertEquals(setOf("STARTED", "HTTP_FAILED"), entries.map { it.phase }.toSet())
        assertEquals(403, entries.single { it.phase == "HTTP_FAILED" }.httpStatus)
        assertEquals(403, call("/api/admin/web/activity", token).statusCode())
        assertTrue(call("/api/admin/web/activity", null).statusCode() in setOf(401, 403))
        assertEquals(400, call("/api/admin/web/activity?limit=101", token(user(RoleCode.SUPER_ADMIN))).statusCode())
    }

    @Test fun auditSurvivesBusinessRollbackAndAuthEventsArePersisted() {
        val operation = UUID.randomUUID().toString()
        assertFailsWith<IllegalStateException> {
            TransactionTemplate(transactions).executeWithoutResult {
                writer.append(ActivityJournalRecord(operationId = operation, phase = "STARTED", actorUserId = "durable-actor", action = "TEST"))
                error("business rollback")
            }
        }
        assertEquals(1, records.findAll().count { it.operationId == operation })
        authAudit.record(AdminAuthAuditEvent(AdminAuthAuditEventType.EMAIL_OTP_VERIFIED, userId = "auth-actor", requestId = operation))
        assertTrue(records.findAll().any { it.operationId == operation && it.phase == "AUTH_EVENT" && it.actorUserId == "auth-actor" })
    }

    private fun user(role: RoleCode) = users.saveAndFlush(User(email = "${UUID.randomUUID()}@fixture.invalid", name = "Audit fixture", roles = mutableSetOf(role)))
    private fun token(user: User) = jwt.generateAccessToken(auth.accountSession(user))
    private fun call(path: String, token: String?, method: String = "GET", body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (token != null) request.header("Authorization", "Bearer $token")
        if (body != null) request.header("Content-Type", "application/json")
        return client.send(request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    @TestConfiguration class Fixture { @Bean fun fixtureController() = FixtureController() }
    @RestController class FixtureController {
        @PostMapping("/api/activity-fixture/{userId}") fun success() = ResponseEntity.ok().build<Void>()
        @DeleteMapping("/api/activity-fixture/{userId}/reject") fun rejected() = ResponseEntity.status(403).build<Void>()
    }
}
