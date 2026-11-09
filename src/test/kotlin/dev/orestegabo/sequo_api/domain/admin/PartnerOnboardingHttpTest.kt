package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.MerchantStorefrontRepository
import dev.orestegabo.sequo_api.domain.relay.RelayPointRepository
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["spring.datasource.url=jdbc:h2:mem:partner_onboarding;DB_CLOSE_DELAY=-1", "sequo.security.cors.allowed-origins=https://www.sequoservice.com,https://sequoservice.com,https://admin.sequoservice.com"])
class PartnerOnboardingHttpTest @Autowired constructor(private val users: UserRepository, private val jwt: JwtService, private val auth: AuthService,
    private val applications: PartnerApplicationRepository, private val operations: PartnerOnboardingRepository,
    private val merchants: CommerceMerchantRepository, private val couriers: SettlementCourierRecordRepository, private val points: RelayPointRepository,
    private val storefronts: MerchantStorefrontRepository, private val access: AdminAccessChangeRepository, private val devices: UserDeviceRepository) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient(); private val json = ObjectMapper()
    private fun user(role: RoleCode = RoleCode.SUPER_ADMIN) = users.saveAndFlush(User(email = "${UUID.randomUUID()}@fixture.invalid", name = "Reviewer", roles = mutableSetOf(role)))
    private fun details(kind: String = "RIDER", email: String = "${UUID.randomUUID()}@fixture.invalid"): Map<String, Any?> = mapOf("displayName" to "Applicant", "email" to email,
        "city" to "Lomé", "neighborhood" to "Tokoin", "businessName" to if(kind == "RIDER") null else "Shop ${UUID.randomUUID()}", "vehicleType" to if(kind == "RIDER") "Moto" else null,
        "latitude" to if(kind == "HUB") 6.1725 else null, "longitude" to if(kind == "HUB") 1.2314 else null)
    private fun call(path: String, actor: User? = null, method: String = "GET", body: Any? = null, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$path"))
        if (actor != null || token != null) builder.header("Authorization", "Bearer ${token ?: jwt.generateAccessToken(auth.accountSession(actor!!))}")
        if (body != null) builder.header("Content-Type", "application/json")
        return client.send(builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun tree(r: HttpResponse<String>) = json.readTree(r.body())
    private fun submit(kind: String, details: Map<String, Any?>) = call("partner-applications", method = "POST", body = mapOf("kind" to kind, "details" to details, "acknowledged" to true, "idempotencyKey" to UUID.randomUUID().toString())).also { assertEquals(200, it.statusCode(), it.body()) }
    private fun preview(actor: User, kind: String, details: Map<String, Any?>) = call("admin/web/onboarding/preview", actor, "POST", mapOf("kind" to kind, "details" to details)).also { assertEquals(200, it.statusCode(), it.body()) }
    private fun request(details: Map<String, Any?>, revision: String? = null) = mutableMapOf<String, Any?>("details" to details, "reason" to "Reviewed application and confirmed identity/contact", "idempotencyKey" to UUID.randomUUID().toString(), "expectedUserUpdatedAt" to revision)

    @Test fun publicFormCorsIsAllowedWithoutOpeningPrivateReviewAccess() {
        fun preflight(origin: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/partner-applications")).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", origin).header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type").build(), HttpResponse.BodyHandlers.ofString())
        val allowed = preflight("https://www.sequoservice.com")
        assertEquals(200, allowed.statusCode())
        assertEquals("https://www.sequoservice.com", allowed.headers().firstValue("access-control-allow-origin").orElse(""))
        assertEquals(403, preflight("https://unrelated.invalid").statusCode())
    }

    @Test fun publicSubmissionStoresPendingDetailsWithoutCreatingAccountsOrGrantingAuthority() {
        val count = users.count(); val d = details(); val key = UUID.randomUUID().toString()
        val body = mapOf("kind" to "RIDER", "details" to d, "acknowledged" to true, "idempotencyKey" to key)
        val response = call("partner-applications", method = "POST", body = body)
        assertEquals(200, response.statusCode(), response.body())
        val id = tree(response)["id"].asText()
        assertEquals("RECEIVED", tree(response)["state"].asText())
        assertFalse(response.body().contains(d["email"].toString()))
        assertFalse(response.body().contains("roles"))
        assertEquals(count, users.count())
        assertEquals(PartnerApplicationState.PENDING, applications.findById(id).orElseThrow().state)
        assertEquals(id, tree(call("partner-applications", method = "POST", body = body))["id"].asText())
        assertEquals(1, operations.findTop50ByApplicationIdOrderByCreatedAtAsc(id).size)
        assertNull(operations.findTop50ByApplicationIdOrderByCreatedAtAsc(id).single().actorUserId)
        assertEquals(400, call("partner-applications", method = "POST", body = body + ("acknowledged" to false)).statusCode())
        assertTrue(call("admin/web/partner-applications?kind=RIDER").statusCode() in setOf(401,403))
        assertEquals(403, call("admin/web/partner-applications?kind=RIDER", user(RoleCode.ADMIN)).statusCode())
        assertEquals(200, call("admin/web/partner-applications?kind=RIDER", user(RoleCode.STAFF_MANAGER)).statusCode())
        assertEquals(400, call("partner-applications", method = "POST", body = body + ("kind" to "SUPER_ADMIN")).statusCode())
    }
    @Test fun oneStepRegistrationCreatesAccountRoleAndEachProfileWithExactRetryAndHistory() {
        for ((kind, role) in listOf("VENDOR" to RoleCode.MERCHANT_OWNER, "RIDER" to RoleCode.COURIER, "HUB" to RoleCode.RELAY_PARTNER)) {
            val actor = user(if(kind == "HUB") RoleCode.STAFF_MANAGER else RoleCode.SUPER_ADMIN)
            val d = details(kind); assertTrue(tree(preview(actor, kind, d))["existingAccount"].isNull)
            val body = request(d); val response = call("admin/web/onboarding/$kind", actor, "POST", body)
            assertEquals(200, response.statusCode(), response.body())
            val record = tree(response); val uid = record["userId"].asText(); val entity = record["entityId"].asText()
            assertEquals(setOf(RoleCode.CUSTOMER, role), users.findById(uid).orElseThrow().roles)
            assertEquals(actor.id, record["actorUserId"].asText())
            assertTrue(record["userReference"].asText().length == 11 && record["entityReference"].asText().length == 11)
            assertTrue(access.findTop50ByUserIdOrderByCreatedAtDesc(uid).any { it.actorUserId == actor.id })
            assertTrue(devices.findTop100ByUserIdOrderByCreatedAtDesc(uid).isEmpty())
            when(kind) {
                "VENDOR" -> { assertEquals(uid, merchants.findById(entity).orElseThrow().ownerUserId); assertEquals("Lomé · Tokoin", storefronts.findById(entity).orElseThrow().area) }
                "RIDER" -> assertEquals(uid, couriers.findById(entity).orElseThrow().userId)
                "HUB" -> assertEquals(uid, points.findById(entity).orElseThrow().operatorUserId)
            }
            assertEquals(entity, tree(call("admin/web/onboarding/$kind", actor, "POST", body))["entityId"].asText())
            assertEquals(409, call("admin/web/onboarding/$kind", actor, "POST", body + ("reason" to "Changed intent")).statusCode())
        }
    }
    @Test fun existingAccountsAreMatchedAndIdentityFieldsAreNotOverwritten() {
        val actor = user(RoleCode.STAFF_MANAGER); val target = user(RoleCode.CUSTOMER); val oldToken = jwt.generateAccessToken(auth.accountSession(target))
        val d = details(email = target.email!!); val p = tree(preview(actor, "RIDER", d))["existingAccount"]
        assertEquals(target.id, p["id"].asText())
        val body = request(d, p["updatedAt"].asText())
        val response = call("admin/web/onboarding/RIDER", actor, "POST", body)
        assertEquals(200, response.statusCode(), response.body())
        assertEquals(target.id, tree(response)["userId"].asText())
        val current = users.findById(target.id!!).orElseThrow()
        assertEquals(target.name, current.name)
        assertTrue(RoleCode.COURIER in current.roles)
        assertTrue(call("auth/me", token = oldToken).statusCode() in setOf(401,403))
        assertEquals(409, call("admin/web/onboarding/RIDER", actor, "POST", request(d)).statusCode())
    }
    @Test fun invalidHubApprovalRollsBackUserRoleProfileAndAuditTogether() {
        val actor = user(); val d = details("HUB") + ("latitude" to null) + ("longitude" to null)
        val id = tree(submit("HUB", d))["id"].asText(); val a = applications.findById(id).orElseThrow()
        val body = request(d).apply { put("expectedApplicationUpdatedAt", a.updatedAt.toString()) }
        val usersBefore = users.count(); val pointsBefore = points.count(); val accessBefore = access.count()
        assertEquals(400, call("admin/web/partner-applications/$id/approve", actor, "POST", body).statusCode())
        assertEquals(usersBefore, users.count()); assertEquals(pointsBefore, points.count()); assertEquals(accessBefore, access.count())
        assertEquals(PartnerApplicationState.PENDING, applications.findById(id).orElseThrow().state)
        assertNull(users.findByEmail(d["email"].toString()))
    }
    @Test fun approvalPreservesApplicantSubmissionAndRecordsCorrectionsAndReviewer() {
        val actor = user(RoleCode.STAFF_MANAGER); val d = details(); val id = tree(submit("RIDER", d))["id"].asText()
        val original = applications.findById(id).orElseThrow(); val corrected = d + ("vehicleType" to "Bicycle")
        val body = request(corrected).apply { put("expectedApplicationUpdatedAt", original.updatedAt.toString()) }
        val result = call("admin/web/partner-applications/$id/approve", actor, "POST", body)
        assertEquals(200, result.statusCode(), result.body())
        val app = applications.findById(id).orElseThrow()
        assertEquals(PartnerApplicationState.APPROVED, app.state); assertEquals(actor.id, app.reviewerUserId)
        assertTrue(app.submittedJson.contains("Moto"))
        assertEquals("Bicycle", tree(result)["details"]["vehicleType"].asText())
        assertEquals(tree(result)["id"], tree(call("admin/web/partner-applications/$id/approve", actor, "POST", body))["id"])
        assertEquals(409, call("admin/web/partner-applications/$id/reject", actor, "POST", mapOf("reason" to "Too late", "idempotencyKey" to UUID.randomUUID().toString(), "expectedApplicationUpdatedAt" to app.updatedAt.toString())).statusCode())
    }
    @Test fun rejectCreatesNoAccountAndAnApprovedOrRejectedApplicationCannotBeReviewedAgain() {
        val actor = user(RoleCode.STAFF_MANAGER); val d = details("VENDOR"); val id = tree(submit("VENDOR", d))["id"].asText()
        val before = users.count(); val app = applications.findById(id).orElseThrow()
        val body = mapOf("reason" to "Outside the launch area", "idempotencyKey" to UUID.randomUUID().toString(), "expectedApplicationUpdatedAt" to app.updatedAt.toString())
        assertEquals(200, call("admin/web/partner-applications/$id/reject", actor, "POST", body).statusCode())
        assertEquals(before, users.count()); assertEquals(PartnerApplicationState.REJECTED, applications.findById(id).orElseThrow().state)
        assertEquals(200, call("admin/web/partner-applications/$id/reject", actor, "POST", body).statusCode())
        assertEquals(409, call("admin/web/partner-applications/$id/approve", actor, "POST", request(d).apply { put("expectedApplicationUpdatedAt", applications.findById(id).orElseThrow().updatedAt.toString()) }).statusCode())
    }
    @Test fun operationsForgedRolesProtectedTargetsAndIdentityMergesAreDenied() {
        val ops = user(RoleCode.ADMIN); val d = details(); val forged = jwt.generateAccessToken(UserSession(ops.id!!, null, AuthProvider.EMAIL, setOf(RoleCode.SUPER_ADMIN)))
        assertEquals(403, call("admin/web/onboarding/RIDER", ops, "POST", request(d)).statusCode())
        assertEquals(403, call("admin/web/onboarding/RIDER", ops, "POST", request(d), forged).statusCode())
        assertEquals(403, call("admin/web/partner-applications?kind=RIDER", ops, token = forged).statusCode())
        val manager = user(RoleCode.STAFF_MANAGER); val owner = user()
        assertEquals(403, call("admin/web/onboarding/preview", manager, "POST", mapOf("kind" to "RIDER", "details" to details(email = owner.email!!))).statusCode())
        assertEquals(409, call("admin/web/onboarding/preview", owner, "POST", mapOf("kind" to "RIDER", "details" to details(email = owner.email!!))).statusCode())
        val first = user(RoleCode.CUSTOMER); val second = users.saveAndFlush(User(phoneNumber = "+22890123456", name = "Other contact"))
        assertEquals(409, call("admin/web/onboarding/preview", owner, "POST", mapOf("kind" to "RIDER", "details" to (details(email = first.email!!) + ("phoneNumber" to second.phoneNumber)))).statusCode())
        assertEquals(409, call("admin/web/onboarding/preview", owner, "POST", mapOf("kind" to "RIDER", "details" to (details(email = first.email!!) + ("phoneNumber" to "+22890888888")))).statusCode())
    }
    @Test fun concurrentApproveAndRejectProduceOneTerminalDecisionAndNoDuplicateProfile() {
        val actor = user(); val d = details(); val id = tree(submit("RIDER", d))["id"].asText(); val at = applications.findById(id).orElseThrow().updatedAt.toString()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val actions = listOf(Callable { call("admin/web/partner-applications/$id/approve", actor, "POST", request(d).apply { put("expectedApplicationUpdatedAt", at) }) }, Callable { call("admin/web/partner-applications/$id/reject", actor, "POST", mapOf("reason" to "Reviewed alternative decision", "idempotencyKey" to UUID.randomUUID().toString(), "expectedApplicationUpdatedAt" to at)) })
            assertEquals(listOf(200,409), pool.invokeAll(actions).map { it.get().statusCode() }.sorted())
            assertEquals(2, operations.findTop50ByApplicationIdOrderByCreatedAtAsc(id).size)
            assertTrue(applications.findById(id).orElseThrow().state != PartnerApplicationState.PENDING)
        } finally { pool.shutdownNow() }
    }
}
