package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionMerchantRepository
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import dev.orestegabo.sequo_api.domain.relay.RelayPointRepository
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
import java.util.concurrent.TimeUnit
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:admin_user_access;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
])
class AdminUserAccessHttpTest @Autowired constructor(
    private val users: UserRepository, private val jwt: JwtService, private val auth: AuthService,
    private val memberships: AdminMembershipRepository, private val merchants: MerchantCommissionMerchantRepository,
    private val changes: AdminAccessChangeRepository, private val scopes: AdminAccessScopeRepository,
    private val devices: UserDeviceRepository, private val sessions: RefreshSessionRepository,
    private val points: RelayPointRepository,
) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private lateinit var superA: User
    private lateinit var superB: User
    private lateinit var manager: User
    private lateinit var admin: User
    private lateinit var customer: User
    private lateinit var staff: User
    private lateinit var ownerA: User
    private lateinit var ownerB: User
    private lateinit var relay: User
    private lateinit var merchant: MerchantRecord
    @BeforeEach fun fixture() {
        changes.deleteAll(); memberships.deleteAll(); points.deleteAll(); merchants.deleteAll()
        devices.deleteAll(); sessions.deleteAll(); users.deleteAll(); scopes.deleteAll(); scopes.saveAndFlush(AdminAccessScopeRecord("GLOBAL"))
        fun user(name: String, vararg roles: RoleCode) = users.saveAndFlush(User(email = "$name@fixture.invalid", name = name, roles = roles.toMutableSet()))
        superA = user("super-a", RoleCode.SUPER_ADMIN, RoleCode.CUSTOMER)
        superB = user("super-b", RoleCode.SUPER_ADMIN, RoleCode.CUSTOMER)
        admin = user("admin", RoleCode.ADMIN, RoleCode.CUSTOMER)
        manager = user("manager", RoleCode.STAFF_MANAGER, RoleCode.CUSTOMER)
        customer = user("customer", RoleCode.CUSTOMER)
        staff = user("staff", RoleCode.MERCHANT_STAFF, RoleCode.CUSTOMER)
        ownerA = user("owner-a", RoleCode.MERCHANT_OWNER, RoleCode.CUSTOMER)
        ownerB = user("owner-b", RoleCode.MERCHANT_OWNER, RoleCode.CUSTOMER)
        relay = user("relay", RoleCode.RELAY_PARTNER, RoleCode.CUSTOMER)
        merchant = merchants.saveAndFlush(MerchantRecord("access-merchant", ownerUserId = ownerA.id, name = "Fixture merchant"))
    }

    @Test fun threeTierBoundaryProtectsAdministratorsAndAllowsAuditedPersonnelManagement() {
        assertEquals(200, call("users/${staff.id}/access", token(admin)).statusCode())
        val grantPath = "users/${customer.id}/roles/COURIER"
        assertEquals(403, call(grantPath, token(admin), "PUT", guarded(userRevision(customer), "ops-no-personnel", "Operations cannot appoint personnel")).statusCode())
        val fakeManager = jwt.generateAccessToken(UserSession(admin.id!!, null, AuthProvider.EMAIL, setOf(RoleCode.STAFF_MANAGER)))
        assertEquals(403, call(grantPath, fakeManager, "PUT", guarded(userRevision(customer), "forged-manager-claim", "Current database authority is required")).statusCode())
        assertEquals(403, call("enrollment/merchants", token(admin), "POST", mapOf("userId" to ownerA.id, "name" to "New approved merchant", "expectedUserUpdatedAt" to userRevision(ownerA), "reason" to "Operations cannot enroll staff", "idempotencyKey" to "ops-no-enrollment")).statusCode())
        assertEquals(403, call("couriers/missing/workforce", token(admin), "PUT", mapOf("workforceType" to "Freelancer", "reason" to "Operations cannot change staffing", "expectedUpdatedAt" to userRevision(customer), "idempotencyKey" to "ops-no-workforce")).statusCode())
        assertEquals(403, call("merchant-memberships/${merchant.id}/${staff.id}/MERCHANT_STAFF", token(admin), "PUT", guarded(merchantRevision(), "ops-no-membership", "Cannot alter staff") + ("active" to true)).statusCode())
        for (role in listOf("ADMIN", "STAFF_MANAGER", "SUPER_ADMIN", "SUPPORT_AGENT")) {
            assertEquals(403, call("users/${customer.id}/roles/$role", token(manager), "PUT", guarded(userRevision(customer), "manager-no-$role", "Cannot appoint administrators")).statusCode())
        }
        assertEquals(200, call(grantPath, token(manager), "PUT", guarded(userRevision(customer), "manager-courier", "Approved rider onboarding")).statusCode())
        val oldToken = token(users.findById(customer.id!!).orElseThrow())
        val suspended = call("users/${customer.id}/access", token(manager), "PUT", guarded(userRevision(customer), "manager-suspend", "Reviewed personnel suspension") + mapOf("active" to false, "status" to "SUSPENDED"))
        assertEquals(200, suspended.statusCode(), suspended.body())
        assertTrue(inbox(oldToken).statusCode() in setOf(401, 403))
        assertEquals(manager.id, tree(suspended)["operation"]["actorUserId"].asText())
        for (target in listOf(admin, manager, superA)) {
            val status = call("users/${target.id}/access", token(manager), "PUT", guarded(userRevision(target), "manager-protected-${target.id}", "Cannot suspend administrators") + mapOf("active" to false, "status" to "SUSPENDED")).statusCode()
            assertEquals(if (target.id == manager.id) 409 else 403, status)
        }
        assertEquals(403, call("users/${admin.id}/roles/COURIER", token(manager), "PUT", guarded(userRevision(admin), "manager-admin-target", "Cannot modify admin roles")).statusCode())
        val ownerGrant = call("users/${staff.id}/roles/STAFF_MANAGER", token(superA), "PUT", guarded(userRevision(staff), "owner-new-manager", "Owner approved staff manager"))
        assertEquals(200, ownerGrant.statusCode(), ownerGrant.body())
        assertEquals(200, call("users/${relay.id}/access", token(staff)).statusCode())
        val staleOwner = token(superB)
        val demote = call("users/${superB.id}/roles/SUPER_ADMIN", token(superA), "DELETE", guarded(userRevision(superB), "owner-revoke-owner", "Owner access revoked"))
        assertEquals(200, demote.statusCode(), demote.body())
        assertTrue(call("users/${relay.id}/access", staleOwner).statusCode() in setOf(401, 403))
    }

    @Test fun rolesAreSuperAdminOnlyAuditedReplaySafeAndStaleProtected() {
        val path = "users/${customer.id}/roles/SUPPORT_AGENT"
        val body = guarded(userRevision(customer), "role-grant-customer", "Approved support assignment")
        assertEquals(403, call(path, token(admin), "PUT", body).statusCode())
        val counterfeit = jwt.generateAccessToken(UserSession(requireNotNull(customer.id), null, AuthProvider.EMAIL, setOf(RoleCode.SUPER_ADMIN)))
        assertEquals(403, call(path, counterfeit, "PUT", body).statusCode())
        val first = call(path, token(superA), "PUT", body)
        assertEquals(200, first.statusCode(), first.body())
        val operation = tree(first)["operation"]
        assertEquals(superA.id, operation["actorUserId"].asText())
        assertEquals("GRANT_ROLE", operation["action"].asText())
        assertTrue(tree(first)["record"]["user"]["roles"].any { it.asText() == "SUPPORT_AGENT" })
        val version = users.findById(customer.id!!).orElseThrow().authorizationVersion
        assertNotNull(version)
        val retry = call(path, token(superA), "PUT", body)
        assertEquals(200, retry.statusCode(), retry.body())
        assertEquals(operation["id"].asText(), tree(retry)["operation"]["id"].asText())
        assertEquals(version, users.findById(customer.id!!).orElseThrow().authorizationVersion)
        assertEquals(1L, changes.count())
        assertEquals(409, call(path, token(superB), "PUT", body).statusCode())
        assertEquals(409, call(path, token(superA), "PUT", body + ("reason" to "Changed intent")).statusCode())
        assertEquals(409, call("users/${customer.id}/roles/COURIER", token(superA), "PUT", body + ("idempotencyKey" to "stale-second-grant")).statusCode())
        assertFalse(first.body().contains("authorizationVersion")); assertFalse(first.body().contains("recordVersion"))
    }

    @Test fun disablingAccountInvalidatesSidAndSidlessTokensAndAllRefreshDevicesWithoutInventingIdentity() {
        val oldSidless = token(customer)
        val issued = auth.issueTokensForExistingUser(customer.id!!, AuthProvider.EMAIL, DeviceBinding("refresh-device", AppSource.SEQUO_APP, null))!!
        repeat(105) { index -> devices.save(UserDevice(userId = customer.id!!, deviceId = "bulk-$index", appSource = AppSource.SEQUO_APP, refreshTokenHash = "local-hash-$index", expiresAt = Instant.now().plusSeconds(3600))) }
        sessions.saveAndFlush(RefreshSession(userId = customer.id!!, tokenHash = "local-legacy-refresh-hash", expiresAt = Instant.now().plusSeconds(3600)))
        assertEquals(200, inbox(oldSidless).statusCode())
        val response = call("users/${customer.id}/access", token(superA), "PUT", guarded(userRevision(customer), "suspend-existing-user", "Access suspended") + mapOf("active" to false, "status" to "SUSPENDED"))
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(inbox(oldSidless).statusCode() in setOf(401, 403))
        assertTrue(inbox(issued.accessToken).statusCode() in setOf(401, 403))
        assertNull(auth.refreshTokens(issued.refreshToken, "refresh-device"))
        assertTrue(devices.findAll().all { it.revokedAt != null })
        assertTrue(sessions.findAll().all { it.revokedAt != null })
        assertEquals(200, inbox(token(admin)).statusCode(), "Untouched legacy token contract is retained")
        val revision = tree(response)["record"]["user"]["updatedAt"].asText()
        val restore = call("users/${customer.id}/access", token(superA), "PUT", guarded(revision, "restore-existing-user", "Reviewed restoration") + mapOf("active" to true, "status" to "ACTIVE"))
        assertEquals(200, restore.statusCode(), restore.body())
        val fresh = auth.issueTokensForExistingUser(customer.id!!, AuthProvider.EMAIL, DeviceBinding("reauth-device", AppSource.SEQUO_APP, null))!!
        assertEquals(200, inbox(fresh.accessToken).statusCode())
        assertTrue(inbox(oldSidless).statusCode() in setOf(401, 403), "Restoring access does not revive old credentials")
        val pending = users.saveAndFlush(User(email = "pending@fixture.invalid", status = UserStatus.PENDING))
        assertEquals(400, call("users/${pending.id}/access", token(superA), "PUT", guarded(userRevision(pending), "no-pending-promotion", "Not identity verification") + mapOf("active" to true, "status" to "ACTIVE")).statusCode())
    }

    @Test fun membershipRequiresSeparateGlobalRoleAndPersistsScopesWithExplicitOwnerProtection() {
        val revision = merchantRevision()
        val path = "merchant-memberships/${merchant.id}/${customer.id}/MERCHANT_STAFF"
        assertEquals(400, call(path, token(manager), "PUT", guarded(revision, "no-role-promotion", "Scope only") + ("active" to true)).statusCode())
        assertEquals(setOf(RoleCode.CUSTOMER), users.findById(customer.id!!).orElseThrow().roles)
        val oldStaff = token(staff)
        val oldStaffRevision = userRevision(staff)
        val add = membership(staff, RoleCode.MERCHANT_STAFF, true, "add-staff-scope")
        assertEquals(200, add.statusCode(), add.body())
        assertNotEquals(oldStaffRevision, userRevision(staff), "Membership changes advance the account access revision as well")
        assertEquals("no-store", add.headers().firstValue("Cache-Control").orElseThrow())
        val freshStaff = token(staff)
        assertTrue(jwt.parseAccessToken(freshStaff)!!.merchantScopeIds.contains(merchant.id))
        assertTrue(inbox(oldStaff).statusCode() in setOf(401, 403))
        assertEquals(403, call("merchant-memberships?merchantId=${merchant.id}", freshStaff).statusCode())
        assertEquals(409, call("users/${staff.id}/roles/MERCHANT_STAFF", token(superA), "DELETE", guarded(userRevision(staff), "remove-role-too-soon", "Remove scope first")).statusCode())
        assertEquals(200, membership(ownerA, RoleCode.MERCHANT_OWNER, true, "add-owner-a").statusCode())
        val sole = membership(ownerA, RoleCode.MERCHANT_OWNER, false, "remove-sole-owner")
        assertEquals(409, sole.statusCode(), sole.body()); assertEquals("sole_owner_review_required", tree(sole)["code"].asText())
        assertEquals(409, call("users/${ownerA.id}/access", token(superA), "PUT", guarded(userRevision(ownerA), "disable-sole-owner", "Requires replacement") + mapOf("active" to false, "status" to "SUSPENDED")).statusCode())
        assertEquals(200, membership(ownerB, RoleCode.MERCHANT_OWNER, true, "add-owner-b").statusCode())
        assertEquals(200, membership(ownerA, RoleCode.MERCHANT_OWNER, false, "remove-replaced-owner").statusCode())
        assertEquals(ownerA.id, merchants.findById(merchant.id).orElseThrow().ownerUserId, "Legal ownership identity is not rewritten")
        assertEquals(200, membership(staff, RoleCode.MERCHANT_STAFF, false, "remove-staff-scope").statusCode())
        assertFalse(jwt.parseAccessToken(token(staff))!!.merchantScopeIds.contains(merchant.id))
        val history = tree(call("merchant-memberships?merchantId=${merchant.id}", token(admin)))["history"]
        assertTrue(history.any { it["afterValue"].asText().contains("role=MERCHANT_STAFF;active=false") })
    }

    @Test fun accessReadsAndWritesRejectForeignRolesExpiredAndMissingSessionsAndSelfEscalation() {
        val path = "users/${customer.id}/access"
        assertEquals(200, call(path, token(admin)).statusCode())
        assertEquals(404, call("users/guessed-user/access", token(admin)).statusCode())
        assertEquals(404, call("merchant-memberships?merchantId=guessed-merchant", token(admin)).statusCode())
        for (credential in listOf(null, token(customer), token(staff), token(relay))) assertTrue(call(path, credential).statusCode() in setOf(401, 403))
        val expired = JwtService("sequo_auth_dev_secret_key_2026_v1", -60000, 60000, "sequo-api", "sequo-mobile").generateAccessToken(auth.accountSession(superA))
        assertTrue(call(path, expired).statusCode() in setOf(401, 403))
        val self = call("users/${superA.id}/roles/SUPER_ADMIN", token(superA), "DELETE", guarded(userRevision(superA), "self-role-denied", "Needs another actor"))
        assertEquals(409, self.statusCode(), self.body())
        assertEquals("self_access_change_denied", tree(self)["code"].asText())
        assertEquals(0L, changes.count())
    }

    @Test fun concurrentMutualSuperAdminRevocationPreservesOneActiveSuperAndRevokedActorCannotContinue() {
        val tokenA = token(superA); val tokenB = token(superB)
        val revisionA = userRevision(superA); val revisionB = userRevision(superB)
        val outcomes = parallel(
            { call("users/${superB.id}/roles/SUPER_ADMIN", tokenA, "DELETE", guarded(revisionB, "super-a-revokes-b", "Authority review")) },
            { call("users/${superA.id}/roles/SUPER_ADMIN", tokenB, "DELETE", guarded(revisionA, "super-b-revokes-a", "Authority review")) },
        )
        assertEquals(1, outcomes.count { it.statusCode() == 200 }, outcomes.joinToString { "${it.statusCode()} ${it.body()}" })
        assertTrue(outcomes.filter { it.statusCode() != 200 }.all { it.statusCode() in setOf(401, 403) })
        assertEquals(1, users.findAll().count { it.active && it.status.canAuthenticate() && RoleCode.SUPER_ADMIN in it.roles })
        assertEquals(1L, changes.count())
        val revoked = if (RoleCode.SUPER_ADMIN in users.findById(superA.id!!).orElseThrow().roles) superB else superA
        val staleCredential = if (revoked.id == superA.id) tokenA else tokenB
        assertTrue(call("users/${customer.id}/roles/ADMIN", staleCredential, "PUT", guarded(userRevision(customer), "revoked-actor-retry", "Must fail")).statusCode() in setOf(401, 403))
    }

    @Test fun concurrentDuplicateIntentKeepsOneOperationAndStaleDetachedSaveCannotUndoAccessDecision() {
        val credential = token(superA); val body = guarded(userRevision(customer), "concurrent-one-intent", "Support assignment")
        val stale = users.findById(customer.id!!).orElseThrow()
        val outcomes = parallel(*Array(2) { { call("users/${customer.id}/roles/SUPPORT_AGENT", credential, "PUT", body) } })
        assertTrue(outcomes.all { it.statusCode() == 200 }, outcomes.joinToString { it.body() })
        assertEquals(tree(outcomes[0])["operation"]["id"].asText(), tree(outcomes[1])["operation"]["id"].asText())
        assertEquals(1L, changes.count())
        val version = users.findById(customer.id!!).orElseThrow().authorizationVersion
        stale.name = "Late stale profile save"
        assertFails { users.saveAndFlush(stale) }
        val actual = users.findById(customer.id!!).orElseThrow()
        assertEquals(version, actual.authorizationVersion)
        assertTrue(RoleCode.SUPPORT_AGENT in actual.roles)
    }

    @Test fun boundRelayOperatorRequiresAuditedUnbindBeforeAccessRemoval() {
        points.saveAndFlush(RelayPointRecord("bound-site", operatorUserId = relay.id, name = "Actual assigned site"))
        for ((path, method, extra) in listOf(
            Triple("users/${relay.id}/roles/RELAY_PARTNER", "DELETE", emptyMap<String, Any>()),
            Triple("users/${relay.id}/access", "PUT", mapOf("active" to false, "status" to "SUSPENDED")),
        )) {
            val response = call(path, token(superA), method, guarded(userRevision(relay), "bound-${method.lowercase()}-guard", "Unbind first") + extra)
            assertEquals(409, response.statusCode(), response.body()); assertEquals("relay_operator_unbind_required", tree(response)["code"].asText())
        }
        assertEquals(relay.id, points.findById("bound-site").orElseThrow().operatorUserId)
        assertNull(users.findById(relay.id!!).orElseThrow().authorizationVersion)
        assertEquals(0L, changes.count())
    }

    @Test fun refreshAndAdminChangeRaceCannotLeaveOldCredentialsUsable() {
        val issued = auth.issueTokensForExistingUser(customer.id!!, AuthProvider.EMAIL, DeviceBinding("racing-refresh", AppSource.SEQUO_APP, null))!!
        val body = guarded(userRevision(customer), "race-refresh-access", "Temporary suspension") + mapOf("active" to false, "status" to "SUSPENDED")
        val executor = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val refreshed = executor.submit(Callable { start.await(); auth.refreshTokens(issued.refreshToken, "racing-refresh") })
            val changed = executor.submit(Callable { start.await(); call("users/${customer.id}/access", token(superA), "PUT", body) })
            start.countDown()
            assertEquals(200, changed.get(20, TimeUnit.SECONDS).statusCode())
            val raced = refreshed.get(20, TimeUnit.SECONDS)
            assertTrue(inbox(issued.accessToken).statusCode() in setOf(401, 403))
            assertNull(auth.refreshTokens(issued.refreshToken, "racing-refresh"))
            if (raced != null) {
                assertTrue(inbox(raced.accessToken).statusCode() in setOf(401, 403))
                assertNull(auth.refreshTokens(raced.refreshToken, "racing-refresh"))
            }
            assertTrue(devices.findAll().all { it.revokedAt != null })
        } finally { executor.shutdownNow() }
    }

    private fun token(user: User) = jwt.generateAccessToken(auth.accountSession(users.findById(user.id!!).orElseThrow()))
    private fun userRevision(user: User) = tree(call("users/${user.id}/access", token(users.findAll().first { it.active && it.status.canAuthenticate() && RoleCode.SUPER_ADMIN in it.roles })))["user"]["updatedAt"].asText()
    private fun merchantRevision() = tree(call("merchant-memberships?merchantId=${merchant.id}", token(admin)))["updatedAt"].asText()
    private fun membership(user: User, role: RoleCode, active: Boolean, key: String) = call("merchant-memberships/${merchant.id}/${user.id}/$role", token(manager), "PUT", guarded(merchantRevision(), key, "Reviewed merchant scope") + ("active" to active))
    private fun guarded(revision: String, key: String, reason: String) = mapOf("expectedUpdatedAt" to revision, "idempotencyKey" to key, "reason" to reason)
    private fun tree(response: HttpResponse<String>): JsonNode = json.readTree(response.body())
    private fun inbox(token: String) = send("/api/notifications/inbox", token)
    private fun call(path: String, token: String? = null, method: String = "GET", body: Any? = null) = send("/api/admin/web/$path", token, method, body)
    private fun send(path: String, token: String?, method: String = "GET", body: Any? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        return client.send(builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun parallel(vararg actions: () -> HttpResponse<String>): List<HttpResponse<String>> {
        val executor = Executors.newFixedThreadPool(actions.size); val start = CountDownLatch(1)
        return try { val tasks = actions.map { action -> executor.submit(Callable { start.await(); action() }) }; start.countDown(); tasks.map { it.get(25, TimeUnit.SECONDS) } }
        finally { executor.shutdownNow() }
    }
}
