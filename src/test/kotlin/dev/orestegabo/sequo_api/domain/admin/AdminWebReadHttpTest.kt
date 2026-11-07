package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.JwtService
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.UserSession
import dev.orestegabo.sequo_api.domain.returns.ReturnRequestRecord
import dev.orestegabo.sequo_api.domain.returns.ReturnRequestRecordRepository
import dev.orestegabo.sequo_api.domain.returns.ReturnStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:admin_web_reads;DB_CLOSE_DELAY=-1",
        "sequo.auth.jwt.secret=admin_read_test_signing_key_for_local_fixtures",
    ],
)
class AdminWebReadHttpTest @Autowired constructor(
    private val jwt: JwtService,
    private val returns: ReturnRequestRecordRepository,
) {
    @LocalServerPort
    private var port = 0
    private val client = HttpClient.newHttpClient()

    @Test
    fun workspaceReadsRequireLiveAdminCredentials() {
        val admin = jwt.generateAccessToken(session(RoleCode.ADMIN))
        val nonAdmin = jwt.generateAccessToken(session(RoleCode.CUSTOMER))
        val expired = JwtService("admin_read_test_signing_key_for_local_fixtures", -60_000, 60_000, "sequo-api", "sequo-mobile")
            .generateAccessToken(session(RoleCode.ADMIN))
        val paths = listOf("overview", "users", "merchants", "orders", "deliveries", "payments",
            "commissions", "delivery-pricing", "business-parameters", "relay", "returns")
            .map { "/api/admin/web/$it" } + "/api/admin/monitoring/operations"

        for (path in paths) {
            assertEquals(200, get(path, admin).statusCode(), path)
            assertEquals(403, get(path, nonAdmin).statusCode(), path)
            assertTrue(get(path, expired).statusCode() in setOf(401, 403), path)
            assertTrue(get(path).statusCode() in setOf(401, 403), path)
        }
    }

    @Test
    fun returnSummaryUsesPersistedStatusAndOnlyExposesOperationalFields() {
        val now = Instant.parse("2026-10-01T10:00:00Z")
        returns.save(ReturnRequestRecord(
            id = "fixture-return",
            orderId = "fixture-order",
            customerId = "fixture-customer",
            status = ReturnStatus.DroppedAtRelay,
            reason = "Private return reason",
            requestedRefundCfa = 2750,
            returnPinHash = "fixture-private-hash",
            relayPointId = "fixture-relay",
            receiptIdempotencyKey = "fixture-receipt-key",
            refundIdempotencyKey = "fixture-refund-key",
            createdAt = now,
            updatedAt = now,
        ))
        val response = get("/api/admin/web/returns", jwt.generateAccessToken(session(RoleCode.SUPER_ADMIN)))
        assertEquals(200, response.statusCode(), response.body())
        val row = ObjectMapper().readTree(response.body()).single { it.get("id").asText() == "fixture-return" }
        assertEquals("DroppedAtRelay", row.get("status").asText())
        assertEquals(2750, row.get("requestedRefundCfa").asInt())
        assertEquals(setOf("id", "orderId", "status", "requestedRefundCfa", "relayPointId", "updatedAt"), row.fieldNames().asSequence().toSet())
    }

    private fun session(role: RoleCode) = UserSession("fixture-$role", null, AuthProvider.EMAIL, setOf(role))

    private fun get(path: String, token: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }
}
