package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commerce.MerchantStorefrontRepository
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionConfigurationService
import dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType
import dev.orestegabo.sequo_api.domain.settlement.SettlementLedgerEntryRecordRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:admin_enrollment;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
])
class AdminEnrollmentHttpTest @Autowired constructor(
    private val users: UserRepository, private val jwt: JwtService, private val auth: AuthService,
    private val merchants: AdminEnrollmentMerchantRepository, private val couriers: AdminEnrollmentCourierRepository,
    private val changes: AdminEnrollmentChangeRepository, private val memberships: MerchantMembershipRepository,
    private val storefronts: MerchantStorefrontRepository, private val commission: MerchantCommissionConfigurationService,
    private val ledger: SettlementLedgerEntryRecordRepository,
) {
    companion object {
        @JvmStatic @org.springframework.test.context.DynamicPropertySource
        fun localPostgres(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_ADMIN_ENROLLMENT_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) { "Use a disposable local enrollment test database." }
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "sequo_fixture" }; registry.add("spring.datasource.password") { "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.dialect.PostgreSQLDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }; registry.add("spring.flyway.enabled") { "true" }
        }
    }
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private fun user(vararg roles: RoleCode, status: UserStatus = UserStatus.ACTIVE, active: Boolean = true) =
        users.saveAndFlush(User(email = "enrollment-${UUID.randomUUID()}@fixture.invalid", name = "Local enrollment account",
            roles = roles.toMutableSet(), status = status, active = active))
    private fun token(user: User, roles: Set<RoleCode> = user.roles) = jwt.generateAccessToken(UserSession(requireNotNull(user.id), user.email, user.provider, roles, authorizationVersion = user.authorizationVersion))
    private fun body(target: User, kind: String, key: String = "enroll-${UUID.randomUUID()}", extra: Map<String, Any?> = emptyMap()) =
        mapOf("userId" to target.id, "expectedUserUpdatedAt" to target.updatedAt.toString(), "reason" to "LOCAL_FIXTURE_ONLY approved enrollment", "idempotencyKey" to key) +
            (if (kind == "merchants") mapOf("name" to "Fixture shop ${UUID.randomUUID()}") else mapOf("workforceType" to "Freelancer", "vehicleType" to "Moto")) + extra
    private fun call(path: String, access: String?, method: String = "GET", body: Any? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/admin/web/enrollment/$path"))
        access?.let { request.header("Authorization", "Bearer $it") }
        if (body != null) request.header("Content-Type", "application/json")
        return client.send(request.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun merchantEnrollmentCreatesExplicitScopeWithoutPromotingIdentityOrInventingStoreClaims() {
        val admin = user(RoleCode.ADMIN); val owner = user(RoleCode.CUSTOMER, RoleCode.MERCHANT_OWNER)
        val oldToken = token(owner)
        val request = body(owner, "merchants")
        val result = call("merchants", token(admin), "POST", request)
        assertEquals(200, result.statusCode(), result.body()); assertEquals("no-store", result.headers().firstValue("Cache-Control").orElse(""))
        val parsed = json.readTree(result.body()); val id = parsed["operation"]["entityId"].asText()
        val merchant = merchants.findById(id).orElseThrow()
        assertEquals(owner.id, merchant.ownerUserId); assertEquals(commission.policyState(id).defaultRateBps, merchant.commissionRateBps)
        assertNull(merchant.walletAccountRef)
        val scope = memberships.findAllByUserIdAndActiveTrue(owner.id!!).single()
        assertEquals(id, scope.merchantId); assertEquals(RoleCode.MERCHANT_OWNER, scope.role)
        val store = storefronts.findById(id).orElseThrow()
        assertNull(store.rating); assertNull(store.eta); assertNull(store.distanceKm); assertNull(store.openStatus)
        val managed = users.findById(owner.id!!).orElseThrow()
        assertEquals(owner.roles, managed.roles); assertNotNull(managed.authorizationVersion)
        // A pre-enrollment scope token cannot keep operating after the explicit scope change.
        val me = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/auth/me")).header("Authorization", "Bearer $oldToken").GET().build()
        assertTrue(client.send(me, HttpResponse.BodyHandlers.ofString()).statusCode() in setOf(401, 403))
        val refreshedSession = auth.accountSession(managed)
        assertTrue(id in refreshedSession.merchantScopeIds)
        val freshMe = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/auth/me"))
            .header("Authorization", "Bearer ${jwt.generateAccessToken(refreshedSession)}").GET().build()
        assertEquals(200, client.send(freshMe, HttpResponse.BodyHandlers.ofString()).statusCode())
        val replay = call("merchants", token(admin), "POST", request)
        assertEquals(200, replay.statusCode(), replay.body())
        assertEquals(id, json.readTree(replay.body())["operation"]["entityId"].asText())
        assertEquals(1, merchants.findByOwnerUserId(owner.id!!).size)
        assertEquals(1, changes.findTop50ByUserIdOrderByCreatedAtDesc(owner.id!!).size)
        val readback = call("users/${owner.id}", token(admin))
        assertEquals(200, readback.statusCode(), readback.body())
        assertEquals(admin.id, json.readTree(readback.body())["history"][0]["actorUserId"].asText())
        assertFalse(readback.body().contains("walletAccountRef")); assertFalse(readback.body().contains("returnPin"))
    }

    @Test fun riderEnrollmentSupportsBothWorkforcesAndPreservesExistingProfileOnFreshKey() {
        val admin = user(RoleCode.SUPER_ADMIN)
        for (workforce in DeliveryWorkforceType.entries) {
            val rider = user(RoleCode.COURIER, RoleCode.CUSTOMER)
            val request = body(rider, "couriers", extra = mapOf("workforceType" to workforce.name, "vehicleType" to "Bicycle"))
            val result = call("couriers", token(admin), "POST", request)
            assertEquals(200, result.statusCode(), result.body())
            val profile = requireNotNull(couriers.findByUserId(rider.id!!))
            assertEquals(if (workforce == DeliveryWorkforceType.SequoSalaried) "SEQUO_SALARIED" else "FREELANCER", profile.workforceType)
            assertEquals("Bicycle", profile.vehicleType); assertNull(profile.walletAccountRef)
            assertEquals(0, ledger.findByCourierIdOrderByCreatedAtDesc(profile.id).size)
            val summaryRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/rider/payouts/summary"))
                .header("Authorization", "Bearer ${token(rider)}").GET().build()
            val summaryResponse = client.send(summaryRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, summaryResponse.statusCode(), summaryResponse.body())
            val summary = json.readTree(summaryResponse.body())
            assertEquals(profile.id, summary["riderId"].asText())
            assertEquals(profile.workforceType, summary["workforceType"].asText())
            assertEquals("NOT_INTEGRATED", summary["transferStatus"].asText())
            assertEquals(0, summary["paidCfa"].asInt()); assertEquals(0, summary["processingCfa"].asInt())
            val replay = call("couriers", token(admin), "POST", request)
            assertEquals(200, replay.statusCode(), replay.body())
            val duplicate = call("couriers", token(admin), "POST", request + ("idempotencyKey" to "new-${UUID.randomUUID()}"))
            assertEquals(409, duplicate.statusCode(), duplicate.body())
            assertEquals(profile.id, couriers.findByUserId(rider.id!!)?.id)
            assertEquals(rider.roles, users.findById(rider.id!!).orElseThrow().roles)
        }
        val legacyUser = user(RoleCode.COURIER)
        val legacyId = requireNotNull(legacyUser.id)
        couriers.saveAndFlush(dev.orestegabo.sequo_api.domain.party.CourierRecord(legacyId, workforceType = "FREELANCER"))
        val refused = call("couriers", token(admin), "POST", body(legacyUser, "couriers"))
        assertEquals(409, refused.statusCode(), refused.body())
        assertEquals("legacy_courier_link_review_required", json.readTree(refused.body())["code"].asText())
        assertNull(couriers.findByUserId(legacyId))
        assertNull(couriers.findById(legacyId).orElseThrow().userId)
        assertTrue(changes.findTop50ByUserIdOrderByCreatedAtDesc(legacyId).isEmpty())
    }

    @Test fun enrollmentRequiresCurrentStaffAuthorityAndSeparatelyGrantedTargetRole() {
        val admin = user(RoleCode.ADMIN); val customer = user(RoleCode.CUSTOMER)
        val target = user(RoleCode.COURIER); val payload = body(target, "couriers")
        assertTrue(call("couriers", null, "POST", payload).statusCode() in setOf(401, 403))
        assertEquals(403, call("couriers", token(customer), "POST", payload).statusCode())
        assertEquals(403, call("couriers", token(customer, setOf(RoleCode.ADMIN)), "POST", payload).statusCode())
        assertEquals(403, call("users/${target.id}", token(customer)).statusCode())
        val expired = JwtService("sequo_auth_dev_secret_key_2026_v1", -60000, 60000, "sequo-api", "sequo-mobile")
            .generateAccessToken(auth.accountSession(admin))
        assertTrue(call("couriers", expired, "POST", payload).statusCode() in setOf(401, 403))
        for (kind in listOf("merchants", "couriers")) {
            val missingRole = call(kind, token(admin), "POST", body(customer, kind))
            assertEquals(400, missingRole.statusCode(), missingRole.body())
        }
        for (ineligible in listOf(user(RoleCode.COURIER, active = false), user(RoleCode.COURIER, status = UserStatus.SUSPENDED))) {
            assertEquals(400, call("couriers", token(admin), "POST", body(ineligible, "couriers")).statusCode())
            assertNull(couriers.findByUserId(ineligible.id!!))
        }
        assertEquals(409, call("couriers", token(admin), "POST", body(admin, "couriers")).statusCode())
        assertEquals(404, call("users/guessed-user", token(admin)).statusCode())
        assertEquals(setOf(RoleCode.CUSTOMER), users.findById(customer.id!!).orElseThrow().roles)
    }

    @Test fun staleAndChangedIntentCannotCreateOrChangeProfiles() {
        val admin = user(RoleCode.ADMIN); val second = user(RoleCode.ADMIN); val owner = user(RoleCode.MERCHANT_OWNER)
        val stale = body(owner, "merchants", extra = mapOf("expectedUserUpdatedAt" to "2000-01-01T00:00:00Z"))
        assertEquals(409, call("merchants", token(admin), "POST", stale).statusCode())
        assertTrue(merchants.findByOwnerUserId(owner.id!!).isEmpty())
        val request = body(owner, "merchants")
        assertEquals(200, call("merchants", token(admin), "POST", request).statusCode())
        assertEquals(409, call("merchants", token(admin), "POST", request + ("name" to "Different reviewed name")).statusCode())
        assertEquals(409, call("merchants", token(second), "POST", request).statusCode())
        assertEquals(1, merchants.findByOwnerUserId(owner.id!!).size)
        assertEquals(1, changes.findTop50ByUserIdOrderByCreatedAtDesc(owner.id!!).size)
    }

    @Test fun concurrentExactEnrollmentCreatesOneProfileAndOneAuditRecord() {
        val admin = user(RoleCode.ADMIN); val owner = user(RoleCode.MERCHANT_OWNER)
        val request = body(owner, "merchants"); val access = token(admin)
        val calls = (1..2).map { CompletableFuture.supplyAsync { call("merchants", access, "POST", request) } }.map { it.join() }
        calls.forEach { assertEquals(200, it.statusCode(), it.body()) }
        assertEquals(1, calls.map { json.readTree(it.body())["operation"]["id"].asText() }.distinct().size)
        assertEquals(1, merchants.findByOwnerUserId(owner.id!!).size)
        val rider = user(RoleCode.COURIER)
        val competing = (1..2).map { CompletableFuture.supplyAsync { call("couriers", access, "POST", body(rider, "couriers")) } }.map { it.join() }
        assertEquals(listOf(200, 409), competing.map { it.statusCode() }.sorted())
        assertEquals(1, changes.findTop50ByUserIdOrderByCreatedAtDesc(rider.id!!).size)
    }
}
