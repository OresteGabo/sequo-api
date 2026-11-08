package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.pricing.*
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:admin_delivery_pricing;DB_CLOSE_DELAY=-1",
])
class AdminDeliveryPricingHttpTest @Autowired constructor(private val users: UserRepository, private val jwt: JwtService,
    private val prices: DeliveryPricingSettingsRepository, private val changes: AdminPartyChangeRepository,
    private val pricing: DeliveryPricingService) {
    @LocalServerPort private var port = 0
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private lateinit var admin: User
    private lateinit var customer: User
    @BeforeEach fun fixtures() {
        changes.deleteAll(); prices.deleteAll(); users.deleteAll()
        admin = users.save(User(email = "price-admin@fixture.invalid", roles = mutableSetOf(RoleCode.ADMIN)))
        customer = users.save(User(email = "price-customer@fixture.invalid", roles = mutableSetOf(RoleCode.CUSTOMER)))
        prices.save(DeliveryPricingSettingsRecord("prior-profile", "DEFAULT", 400, 100, 5, true, Instant.EPOCH, Instant.EPOCH))
    }
    @Test fun boundaryPricesUpdateForNewQuotesWithImmutableHistoryAndExactRetry() {
        assertEquals(listOf(400,400,500), listOf(4.999,5.0,5.001).map { pricing.calculate(DeliveryPricingInput(it)).finalDeliveryFeeCfa })
        val revision = read()["revision"].asText()
        val body = command(revision)
        val first = send("PUT", body)
        assertEquals(200, first.statusCode(), first.body())
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow())
        assertEquals(listOf(500,500,580), listOf(4.999,5.0,5.001).map { pricing.calculate(DeliveryPricingInput(it)).finalDeliveryFeeCfa })
        val before = prices.findById("prior-profile").orElseThrow()
        assertEquals(400, before.minimumDeliveryFeeCfa); assertEquals(100, before.extraKmFeeCfa)
        val replay = send("PUT", body)
        assertEquals(200, replay.statusCode(), replay.body())
        assertEquals(tree(first)["operation"]["id"], tree(replay)["operation"]["id"])
        assertEquals(2, prices.count()); assertEquals(1, changes.count())
        assertEquals(409, send("PUT", body + ("minimumDeliveryFeeCfa" to 700)).statusCode())
        assertEquals(409, send("PUT", body + ("idempotencyKey" to "second-stale-key")).statusCode())
        val history = read()["history"]
        assertEquals(admin.id, history[0]["actorUserId"].asText())
        assertTrue(history[0]["reason"].asText().contains("LOCAL_FIXTURE_ONLY"))
    }
    @Test fun invalidOrUnapprovedWritesAndUnauthorizedReadsFailClosed() {
        val body = command(read()["revision"].asText())
        assertEquals(400, send("PUT", body + ("minimumDeliveryFeeCfa" to -1)).statusCode())
        assertEquals(400, send("PUT", body + ("approvalReference" to " ")).statusCode())
        assertEquals(403, send("PUT", body, token(customer)).statusCode())
        assertTrue(send("GET", null, null).statusCode() in setOf(401,403))
        assertEquals(403, send("GET", null, token(customer)).statusCode())
        val expired = JwtService("sequo_auth_dev_secret_key_2026_v1", -60_000, 60_000, "sequo-api", "sequo-mobile")
            .generateAccessToken(UserSession(requireNotNull(admin.id), null, AuthProvider.EMAIL, setOf(RoleCode.ADMIN)))
        assertTrue(send("GET", null, expired).statusCode() in setOf(401,403))
        assertEquals(1, prices.count()); assertEquals(0, changes.count())
    }
    @Test fun concurrentDistinctChangesAgainstOneRevisionHaveOnlyOneWinner() {
        val body = command(read()["revision"].asText())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { n -> pool.submit(Callable { start.await(); send("PUT", body +
                mapOf("minimumDeliveryFeeCfa" to 500+n, "idempotencyKey" to "concurrent-price-$n")).statusCode() }) }
            start.countDown()
            assertEquals(listOf(200,409), futures.map { it.get() }.sorted())
            assertEquals(2, prices.count()); assertEquals(1, changes.count())
        } finally { pool.shutdownNow() }
    }
    private fun command(revision: String) = mapOf<String,Any>("minimumDeliveryFeeCfa" to 500, "extraKmFeeCfa" to 80,
        "includedKm" to 5, "approvalReference" to "LOCAL_FIXTURE_ONLY approved pricing exercise", "reason" to "Test new quote pricing",
        "expectedRevision" to revision, "idempotencyKey" to "local-price-stable-key")
    private fun token(user: User) = jwt.generateAccessToken(UserSession(requireNotNull(user.id), null, AuthProvider.EMAIL, user.roles))
    private fun read() = tree(send("GET"))
    private fun tree(response: HttpResponse<String>) = json.readTree(response.body())
    private fun send(method: String, body: Map<String,Any>? = null, auth: String? = token(admin)): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/admin/web/delivery-pricing/manage"))
            .header("Content-Type", "application/json")
        if (auth != null) b.header("Authorization", "Bearer $auth")
        return http.send(b.method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
            ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
}
