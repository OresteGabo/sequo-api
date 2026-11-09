package dev.orestegabo.sequo_api.domain.localfixture

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentService
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentServiceResult
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRepository
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookEventRepository
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookReconciliationStatus
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import kotlin.test.*

@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:sequo_admin_dev_incoming_http;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "server.address=127.0.0.1", "server.forward-headers-strategy=NONE",
    "sequo.admin.local-access.enabled=true", "sequo.admin.local-access.environment=LOCAL_FIXTURE",
    "sequo.admin.local-access.user-id=00000000-0000-0000-0000-000000000076", "sequo.admin.local-fixtures.enabled=true",
    "sequo.security.cors.allowed-origins=http://127.0.0.1:5173",
    "sequo.auth.jwt.issuer=sequo-api-local-admin-fixture", "sequo.auth.jwt.audience=sequo-admin-local-fixture",
    "sequo.notifications.outbox-worker.enabled=false", "sequo.notifications.provider-delivery-worker.enabled=false",
    "sequo.merchant-fulfillment.response-deadline.scheduler.enabled=false",
    "sequo.wallets.yas-togo.api-base-url=", "sequo.wallets.yas-togo.api-key=", "sequo.wallets.yas-togo.merchant-account-id=", "sequo.wallets.yas-togo.webhook-secret=",
    "sequo.wallets.moov-africa.api-base-url=", "sequo.wallets.moov-africa.api-key=", "sequo.wallets.moov-africa.merchant-account-id=", "sequo.wallets.moov-africa.webhook-secret=",
])
class LocalMerchantIncomingFixtureHttpTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val jwt: JwtService,
    private val foundation: LocalOperationalFixtureFoundation,
    private val runs: LocalOperationalFixtureRunRepository,
    private val fulfillment: MerchantFulfillmentService,
    private val checkouts: PendingPaymentCheckoutRepository,
    private val orders: CustomerOrderRecordRepository,
    private val receipts: PaymentWebhookEventRepository,
) {
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private val admin = "00000000-0000-0000-0000-000000000076"
    private val context = LocalOperationalFixtureContext(admin, Instant.now())
    private lateinit var token: String

    @BeforeAll fun installOnlyTheTrustedSyntheticFoundation() {
        jdbc.update("insert into users(id,email,display_name,is_active,provider,status,created_at,updated_at) values (?,?,?,true,'EMAIL','ACTIVE',current_timestamp,current_timestamp)",
            admin, "incoming-fixture-admin@fixture.invalid", "Local fixture admin")
        jdbc.update("insert into user_roles(user_id,role_code) values (?,?)", admin, "SUPER_ADMIN")
        val accounts = foundation.seed(context)
        runs.saveAndFlush(LocalOperationalFixtureRun("local-ops-v1", context.createdAt,
            LocalFixtureManifestCodec.encode(LocalFixtureManifest(seededAt = context.createdAt, accounts = accounts,
                scenarios = listOf(LocalFixtureScenario("local-ops-v1-existing", "Existing practice", "Retained fixture metadata", "orders", emptyMap(), emptyList()))))))
        token = jwt.generateAccessToken(UserSession(admin, null, AuthProvider.EMAIL, setOf(RoleCode.SUPER_ADMIN)))
    }

    @Test fun localRoleManifestAndFixedInputGuardsRejectBeforeCreatingAnyPaymentEvidence() {
        val before = counts()
        assertTrue(call("android-wait", "{\"attempt\":3}", token = null).statusCode() in setOf(401, 403))
        assertEquals(404, call("android-wait", "{\"attempt\":3}", origin = null).statusCode())
        assertEquals(404, call("android-wait", "{\"attempt\":3}", forwarded = true).statusCode())
        val ordinary = jwt.generateAccessToken(UserSession("local-ops-v1-admin-ordinary", null, AuthProvider.EMAIL, setOf(RoleCode.ADMIN)))
        assertEquals(404, call("android-wait", "{\"attempt\":3}", token = ordinary).statusCode())
        val otherSuper = jwt.generateAccessToken(UserSession("local-ops-v1-admin-backup", null, AuthProvider.EMAIL, setOf(RoleCode.SUPER_ADMIN)))
        assertEquals(404, call("android-wait", "{\"attempt\":3}", token = otherSuper).statusCode())
        for (body in listOf("{}", "{\"attempt\":0}", "{\"attempt\":4}", "{\"attempt\":1.0}", "{\"attempt\":\"1\"}",
            "{\"attempt\":true}", "{\"attempt\":null}", "{\"attempt\":1,\"amountCfa\":1}",
            "{\"attempt\":1,\"orderId\":\"guessed\"}", "{\"attempt\":1,\"receivedAt\":\"2099-01-01T00:00:00Z\"}")) {
            val response = call("android-wait", body)
            assertEquals(400, response.statusCode(), "$body: ${response.body()}")
        }
        assertEquals(400, call("arbitrary-scene", "{\"attempt\":1}").statusCode())
        jdbc.update("update local_operational_fixture_runs set seeded_at=null where namespace='local-ops-v1'")
        try {
            val incomplete = call("android-wait", "{\"attempt\":3}")
            assertEquals(409, incomplete.statusCode(), incomplete.body())
        } finally {
            jdbc.update("update local_operational_fixture_runs set seeded_at=? where namespace='local-ops-v1'", Timestamp.from(context.createdAt))
        }
        assertEquals(before, counts())
    }

    @Test fun freshSceneReconcilesNormallyAndReplaysRetainHandledOrdersDeadlinesAndChangedAccounts() {
        val firstResponse = call("android-accept", "{\"attempt\":1}")
        assertEquals(200, firstResponse.statusCode(), firstResponse.body())
        assertTrue(firstResponse.headers().firstValue("cache-control").orElse("").contains("no-store"))
        val first = json.readTree(firstResponse.body())
        assertFalse(first["reused"].asBoolean())
        assertEquals("LOCAL_FIXTURE", first["environment"].asText())
        assertEquals("COMPLETED", first["checkoutStatus"].asText())
        val part = first["subOrder"]
        assertEquals("MERCHANT_PENDING", part["status"].asText())
        assertEquals("PENDING", part["responseState"].asText())
        assertEquals("Validated", part["orderPaymentStatus"].asText())
        assertEquals(Duration.ofSeconds(60), Duration.between(Instant.parse(part["createdAt"].asText()), Instant.parse(part["alertUntilAt"].asText())))
        assertEquals(Duration.ofMinutes(15), Duration.between(Instant.parse(part["createdAt"].asText()), Instant.parse(part["responseDeadlineAt"].asText())))
        assertNotNull(part["serverNow"])
        val checkoutId = first["checkoutId"].asText()
        val receipt = receipts.findByProviderAndEventId("yas_togo", "LOCAL_FIXTURE:$checkoutId")!!
        assertEquals(PaymentWebhookReconciliationStatus.FULFILLED, receipt.reconciliationStatus)
        assertEquals("LOCAL_FIXTURE:$checkoutId", receipt.providerReference)
        assertNotNull(orders.findByCheckoutId(checkoutId))
        assertIs<MerchantFulfillmentServiceResult.Success>(fulfillment.accept(part["id"].asText(), part["merchantId"].asText()))
        val afterAccept = counts()
        jdbc.update("update users set is_active=false where id='local-ops-v1-owner-yawa'")
        try {
            val replay = readSuccess("android-accept", 1)
            assertTrue(replay["reused"].asBoolean())
            assertEquals("ACCEPTED", replay["subOrder"]["status"].asText())
            assertEquals(part["id"], replay["subOrder"]["id"])
            assertEquals(part["createdAt"], replay["subOrder"]["createdAt"])
            assertEquals(part["alertUntilAt"], replay["subOrder"]["alertUntilAt"])
            assertEquals(part["responseDeadlineAt"], replay["subOrder"]["responseDeadlineAt"])
            assertEquals(409, call("android-accept", "{\"attempt\":2}").statusCode())
            assertEquals(afterAccept, counts())
            assertFalse(checkouts.existsById(LocalMerchantIncomingScene.ANDROID_ACCEPT.checkoutId(2)))
        } finally { jdbc.update("update users set is_active=true where id='local-ops-v1-owner-yawa'") }
        val refused = readSuccess("ios-refuse", 1)
        assertIs<MerchantFulfillmentServiceResult.Success>(fulfillment.reject(refused["subOrder"]["id"].asText(),
            refused["subOrder"]["merchantId"].asText(), "LOCAL_FIXTURE explicit refusal"))
        val retry = readSuccess("ios-refuse", 1)
        assertTrue(retry["reused"].asBoolean())
        assertEquals("REJECTED", retry["subOrder"]["status"].asText())
        assertEquals(refused["subOrder"]["responseDeadlineAt"], retry["subOrder"]["responseDeadlineAt"])
        assertFalse(firstResponse.body().contains("accessToken"))
        assertFalse(firstResponse.body().contains("refreshToken"))
    }

    @Test fun concurrentRetriesUseOneFixedSlotAndNeverRewriteTheExistingManifest() {
        val manifest = jdbc.queryForObject("select manifest_json from local_operational_fixture_runs where namespace='local-ops-v1'", String::class.java)
        val start = CountDownLatch(1)
        val requests = (1..2).map {
            CompletableFuture.supplyAsync { start.await(); call("ios-wait", "{\"attempt\":1}") }
        }
        start.countDown()
        val responses = requests.map { it.join() }
        responses.forEach { assertEquals(200, it.statusCode(), it.body()) }
        val results = responses.map { json.readTree(it.body()) }
        assertEquals(setOf(false, true), results.map { it["reused"].asBoolean() }.toSet())
        assertEquals(1, results.map { it["checkoutId"].asText() }.toSet().size)
        assertEquals(1, results.map { it["subOrder"]["id"].asText() }.toSet().size)
        assertEquals(results[0]["subOrder"]["alertUntilAt"], results[1]["subOrder"]["alertUntilAt"])
        assertEquals(1L, jdbc.queryForObject("select count(*) from customer_orders where checkout_id=?", Long::class.java,
            LocalMerchantIncomingScene.IOS_WAIT.checkoutId(1)))
        assertEquals(manifest, jdbc.queryForObject("select manifest_json from local_operational_fixture_runs where namespace='local-ops-v1'", String::class.java))
    }

    private fun readSuccess(scene: String, attempt: Int): JsonNode {
        val response = call(scene, "{\"attempt\":$attempt}")
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }

    private fun counts(): List<Long> = listOf("pending_payment_checkouts", "customer_orders", "merchant_sub_orders", "payment_webhook_events")
        .map { jdbc.queryForObject("select count(*) from $it", Long::class.java)!! }

    private fun call(scene: String, body: String, token: String? = this.token,
        origin: String? = "http://127.0.0.1:5173", forwarded: Boolean = false): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/admin/local-fixtures/vendor-incoming/$scene"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        token?.let { request.header("Authorization", "Bearer $it") }
        origin?.let { request.header("Origin", it) }
        if (forwarded) request.header("X-Forwarded-For", "127.0.0.1")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
