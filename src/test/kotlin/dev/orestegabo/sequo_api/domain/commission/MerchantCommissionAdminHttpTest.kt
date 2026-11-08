package dev.orestegabo.sequo_api.domain.commission

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:commission_admin_http;DB_CLOSE_DELAY=-1",
    "sequo.auth.jwt.secret=isolated-commission-admin-fixture-signing-key-2026",
])
class MerchantCommissionAdminHttpTest @Autowired constructor(
    private val users: UserRepository,
    private val jwt: JwtService,
    private val memberships: MerchantMembershipRepository,
    private val merchants: CommerceMerchantRepository,
    private val audit: MerchantCommissionAuditRepository,
    private val overrides: MerchantCommissionOverrideRepository,
    private val rateLimiter: InMemoryRateLimiter,
) {
    companion object {
        @JvmStatic @DynamicPropertySource fun postgres(registry: DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_COMMISSION_ADMIN_TEST_URL") ?: System.getenv("SEQUO_RELAY_JOURNEY_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) { "Use a disposable local commission fixture database." }
            registry.add("spring.datasource.url") { url }; registry.add("spring.datasource.username") { "sequo_fixture" }
            registry.add("spring.datasource.password") { "" }; registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.dialect.PostgreSQLDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }; registry.add("spring.flyway.enabled") { "true" }
        }
    }
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    @org.junit.jupiter.api.BeforeEach fun resetBudget() = rateLimiter.resetForTests()

    @Test
    fun existingUnguardedRoutesRemainCompatibleAndRecordActualActorsWithPolicyAndResetHistory() {
        val merchant = merchant()
        val admin = actor(RoleCode.ADMIN)
        val legacyMerchant = merchant()
        val originalTime = Instant.parse("2026-09-09T10:00:00Z")
        overrides.saveAndFlush(MerchantCommissionOverrideRecord(merchantId = legacyMerchant, commissionRateBps = 1100,
            reason = "Preserved legacy override", updatedByUserId = admin.id, createdAt = originalTime, updatedAt = originalTime))
        val legacy = read(legacyMerchant, admin.token)
        assertEquals(1100, legacy["commissionRateBps"].asInt())
        assertTrue(legacy["history"].isEmpty())
        assertTrue(legacy["operationId"].isNull)
        assertEquals(200, call("PUT", legacyMerchant, admin.token, body(950, legacy)).statusCode())
        val editedLegacy = read(legacyMerchant, admin.token)
        assertEquals(1, editedLegacy["history"].size())
        assertEquals(1100, editedLegacy["history"].single()["beforeRateBps"].asInt())
        assertEquals(originalTime, Instant.parse(editedLegacy["override"]["createdAt"].asText()))
        val initial = read(merchant, admin.token)
        assertEquals(1500, initial["commissionRateBps"].asInt())
        assertEquals(1500, initial["defaultRateBps"].asInt())
        assertEquals(500, initial["minRateBps"].asInt())
        assertEquals(1500, initial["maxRateBps"].asInt())
        assertTrue(initial["history"].isEmpty())
        val before = Instant.now().minusSeconds(1)
        val result = call("PUT", merchant, admin.token, mapOf("commissionRateBps" to 900, "reason" to "LOCAL_FIXTURE_ONLY",
            "updatedByUserId" to "forged-actor", "updatedAt" to "1999-01-01T00:00:00Z"))
        assertEquals(200, result.statusCode(), result.body())
        val stored = json.readTree(result.body())
        assertEquals(admin.id, stored["updatedByUserId"].asText())
        val after = read(merchant, admin.token)
        assertEquals(900, after["commissionRateBps"].asInt())
        assertEquals(stored["revision"], after["revision"])
        assertEquals(stored["operationId"], after["operationId"])
        val entry = after["history"].single()
        assertEquals("SET", entry["action"].asText())
        assertEquals(admin.id, entry["actorUserId"].asText())
        assertEquals(1500, entry["beforeRateBps"].asInt())
        assertEquals(900, entry["afterRateBps"].asInt())
        assertFalse(Instant.parse(entry["createdAt"].asText()).isBefore(before))
        assertEquals(204, call("DELETE", merchant, admin.token).statusCode())
        val reset = read(merchant, admin.token)
        assertEquals(1500, reset["commissionRateBps"].asInt())
        assertTrue(reset["override"].isNull)
        assertEquals(2, reset["history"].size())
        assertEquals("RESET", reset["history"].first()["action"].asText())
        assertEquals(admin.id, reset["history"].first()["actorUserId"].asText())
        assertEquals(900, reset["history"].first()["beforeRateBps"].asInt())
        assertEquals(1500, reset["history"].first()["afterRateBps"].asInt())
        assertNotEquals(initial["revision"], reset["revision"])
        assertEquals(reset, read(merchant, admin.token))
    }

    @Test
    fun sameKeyConcurrencyAndHistoricalRetriesNeverOverwriteNewerOrRecreatedPolicy() {
        val merchant = merchant()
        val admin = actor(RoleCode.ADMIN)
        val initial = read(merchant, admin.token)
        val firstKey = UUID.randomUUID().toString()
        val first = body(900, initial, firstKey)
        val concurrent = (1..2).map { CompletableFuture.supplyAsync { call("PUT", merchant, admin.token, first) } }.map { it.join() }
        concurrent.forEach { assertEquals(200, it.statusCode(), it.body()) }
        assertEquals(1, concurrent.map { json.readTree(it.body())["operationId"].asText() }.toSet().size)
        val firstState = read(merchant, admin.token)
        assertEquals(1, firstState["history"].size())
        assertEquals(firstKey, firstState["history"].single()["idempotencyKey"].asText())
        val changedRetry = call("PUT", merchant, admin.token, first + ("commissionRateBps" to 1000))
        conflict(changedRetry, "commission_retry_conflict")
        conflict(call("PUT", merchant, admin.token, body(800, initial)), "commission_stale_revision")
        assertEquals(200, call("PUT", merchant, admin.token, body(800, firstState)).statusCode())
        val secondState = read(merchant, admin.token)
        val historicalReplay = call("PUT", merchant, admin.token, first)
        assertEquals(200, historicalReplay.statusCode(), historicalReplay.body())
        assertEquals(900, json.readTree(historicalReplay.body())["commissionRateBps"].asInt())
        assertEquals(800, read(merchant, admin.token)["commissionRateBps"].asInt())
        assertEquals(2, read(merchant, admin.token)["history"].size())

        val resetKey = UUID.randomUUID().toString()
        val resetBody = body(null, secondState, resetKey)
        val reset = call("DELETE", merchant, admin.token, resetBody)
        assertEquals(204, reset.statusCode(), reset.body())
        val resetState = read(merchant, admin.token)
        assertTrue(resetState["override"].isNull)
        assertNotEquals(initial["revision"], resetState["revision"])
        conflict(call("PUT", merchant, admin.token, body(700, initial)), "commission_stale_revision")
        assertEquals(204, call("DELETE", merchant, admin.token, resetBody).statusCode())
        assertEquals(3, read(merchant, admin.token)["history"].size())
        assertEquals(200, call("PUT", merchant, admin.token, body(600, resetState)).statusCode())
        val recreated = read(merchant, admin.token)
        assertEquals(204, call("DELETE", merchant, admin.token, resetBody).statusCode())
        assertEquals(600, read(merchant, admin.token)["commissionRateBps"].asInt())
        assertEquals(recreated["operationId"], read(merchant, admin.token)["operationId"])
        assertEquals(4, read(merchant, admin.token)["history"].size())
        conflict(call("DELETE", merchant, admin.token, body(null, secondState)), "commission_stale_revision")
        assertEquals(4, audit.findTop50ByMerchantIdOrderByGenerationDesc(merchant).size)
    }

    @Test
    fun conflictingGuardedKeysRaceToOneChangeAndInitialRevisionsAreBoundToTheMerchant() {
        val merchant = merchant()
        val admin = actor(RoleCode.ADMIN)
        val secondAdmin = actor(RoleCode.SUPER_ADMIN)
        val initial = read(merchant, admin.token)
        val otherMerchant = merchant()
        val otherState = read(otherMerchant, admin.token)
        assertNotEquals(initial["revision"], otherState["revision"])
        conflict(call("PUT", otherMerchant, admin.token, body(800, initial)), "commission_stale_revision")
        val inputs = listOf(admin to 700, secondAdmin to 1000)
        val results = inputs.map { (actor, rate) -> CompletableFuture.supplyAsync {
            call("PUT", merchant, actor.token, body(rate, initial))
        } }.map { it.join() }
        assertEquals(1, results.count { it.statusCode() == 200 })
        assertEquals(1, results.count { it.statusCode() == 409 })
        val current = read(merchant, admin.token)
        assertEquals(1, current["history"].size())
        assertTrue(current["history"].single()["actorUserId"].asText() in setOf(admin.id, secondAdmin.id))
        assertEquals(current["commissionRateBps"], current["history"].single()["afterRateBps"])
        assertTrue(read(otherMerchant, admin.token)["history"].isEmpty())
    }

    @Test
    fun roleAndExpiredSessionDenialsAndIncompleteGuardsNeverCreateAnAuditOrChangePolicy() {
        val merchant = merchant()
        val admin = actor(RoleCode.ADMIN)
        val initial = read(merchant, admin.token)
        for (role in listOf(RoleCode.CUSTOMER, RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF,
            RoleCode.COURIER, RoleCode.RELAY_PARTNER, RoleCode.SUPPORT_AGENT)) {
            val denied = actor(role, merchant.takeIf { role in setOf(RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF) })
            for (method in listOf("GET", "PUT", "DELETE")) {
                assertEquals(403, call(method, merchant, denied.token, if (method == "PUT") body(900, initial) else null).statusCode())
            }
        }
        val user = users.findById(admin.id).orElseThrow()
        val expired = JwtService("isolated-commission-admin-fixture-signing-key-2026", -3600000, 3600000,
            "sequo-api", "sequo-mobile").generateAccessToken(UserSession(admin.id, user.email, user.provider, user.roles))
        for (token in listOf(null, "malformed-token", expired)) {
            for (method in listOf("GET", "PUT", "DELETE")) {
                assertTrue(call(method, merchant, token, if (method == "PUT") body(900, initial) else null).statusCode() in setOf(401, 403))
            }
        }
        for (invalid in listOf(mapOf("commissionRateBps" to 499), mapOf("commissionRateBps" to 1501),
            mapOf("commissionRateBps" to 900, "expectedRevision" to initial["revision"].asText()),
            mapOf("commissionRateBps" to 900, "idempotencyKey" to UUID.randomUUID().toString()))) {
            assertEquals(400, call("PUT", merchant, admin.token, invalid).statusCode())
        }
        assertEquals(initial, read(merchant, admin.token))
        assertTrue(audit.findTop50ByMerchantIdOrderByGenerationDesc(merchant).isEmpty())
        val superAdmin = actor(RoleCode.SUPER_ADMIN)
        assertEquals(200, call("PUT", merchant, superAdmin.token, body(950, initial)).statusCode())
        val current = read(merchant, superAdmin.token)
        assertEquals(superAdmin.id, current["history"].single()["actorUserId"].asText())
        assertEquals(204, call("DELETE", merchant, superAdmin.token, body(null, current)).statusCode())
    }

    private data class Actor(val id: String, val token: String)
    private fun actor(role: RoleCode, merchantId: String? = null): Actor {
        val user = users.save(User(email = "commission-${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL, roles = mutableSetOf(role)))
        val id = requireNotNull(user.id)
        merchantId?.let { memberships.save(MerchantMembership(userId = id, merchantId = it, role = role)) }
        return Actor(id, jwt.generateAccessToken(UserSession(id, user.email, user.provider, setOf(role), setOfNotNull(merchantId))))
    }
    private fun merchant(): String = "commission-http-${UUID.randomUUID()}".also { merchants.save(MerchantRecord(it, name = "Fixture commission merchant")) }
    private fun body(rate: Int?, state: JsonNode, key: String = UUID.randomUUID().toString()): Map<String, Any?> =
        mapOf("reason" to "LOCAL_FIXTURE_ONLY", "expectedRevision" to state["revision"].asText(), "idempotencyKey" to key) +
            (rate?.let { mapOf("commissionRateBps" to it) } ?: emptyMap())
    private fun conflict(response: HttpResponse<String>, code: String) {
        assertEquals(409, response.statusCode(), response.body())
        assertEquals(code, json.readTree(response.body())["code"].asText())
    }
    private fun read(merchant: String, token: String): JsonNode {
        val result = call("GET", merchant, token)
        assertEquals(200, result.statusCode(), result.body())
        assertTrue(result.headers().firstValue("Cache-Control").orElse("").contains("no-store"))
        return json.readTree(result.body())
    }
    private fun call(method: String, merchant: String, token: String?, body: Map<String, Any?>? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/commissions/merchant-overrides/$merchant"))
        token?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.header("Content-Type", "application/json") }
        request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
