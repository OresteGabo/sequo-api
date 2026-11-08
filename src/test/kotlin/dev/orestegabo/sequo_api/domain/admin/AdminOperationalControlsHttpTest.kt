package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import dev.orestegabo.sequo_api.domain.relay.RelayPointRepository
import dev.orestegabo.sequo_api.domain.settlement.*
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:admin_operational_controls;DB_CLOSE_DELAY=-1",
])
class AdminOperationalControlsHttpTest @Autowired constructor(
    private val users: UserRepository, private val jwt: JwtService,
    private val orders: CustomerOrderRecordRepository, private val lines: CustomerOrderLineRecordRepository,
    private val events: CustomerOrderEventRecordRepository, private val checkouts: PendingPaymentCheckoutRepository,
    private val couriers: SettlementCourierRecordRepository, private val points: RelayPointRepository,
    private val changes: AdminPartyChangeRepository, private val missions: DeliveryMissionRepository,
    private val ledger: SettlementLedgerEntryRecordRepository, private val lockers: RelayLockerRepository,
    private val jdbc: JdbcTemplate,
) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private val at = Instant.parse("2026-11-08T09:00:00Z")
    private lateinit var admin: User
    private lateinit var secondAdmin: User
    private lateinit var customer: User
    private lateinit var rider: User
    private lateinit var relayA: User
    private lateinit var relayB: User

    @BeforeEach
    fun fixture() {
        changes.deleteAll(); ledger.deleteAll(); missions.deleteAll(); events.deleteAll(); lines.deleteAll()
        orders.deleteAll(); checkouts.deleteAll(); lockers.deleteAll(); points.deleteAll(); couriers.deleteAll(); users.deleteAll()
        fun user(role: RoleCode, name: String) = users.save(User(email = "$name@fixture.invalid", name = name, roles = mutableSetOf(role)))
        admin = user(RoleCode.ADMIN, "admin"); secondAdmin = user(RoleCode.SUPER_ADMIN, "super-admin")
        customer = user(RoleCode.CUSTOMER, "customer"); rider = user(RoleCode.COURIER, "rider")
        relayA = user(RoleCode.RELAY_PARTNER, "relay-a"); relayB = user(RoleCode.RELAY_PARTNER, "relay-b")
        couriers.save(CourierRecord("actual-courier", userId = rider.id, workforceType = "FREELANCER", vehicleType = "Moto",
            walletProvider = "private-wallet-provider", walletAccountRef = "private-wallet-account", createdAt = at, updatedAt = at))
        points.saveAll(listOf(RelayPointRecord("site-a", name = "Site A", city = "Fixture city", createdAt = at, updatedAt = at),
            RelayPointRecord("site-b", name = "Site B", createdAt = at, updatedAt = at)))
        seedOrder()
    }

    @Test
    fun detailReturnsPaidSnapshotsOptionsDestinationAndActorHistoryWithoutCredentialsOrRawPayloads() {
        val response = get("orders/actual-order")
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow())
        val body = tree(response)
        assertEquals("RECORDED", body["snapshotState"].asText())
        assertEquals(2, body["lines"][0]["quantity"].asInt())
        assertEquals(3000, body["lines"][0]["lineTotalCfa"].asInt())
        assertEquals("large", body["lines"][0]["selections"][0]["optionIds"][0].asText())
        assertEquals("customer", body["homeDestination"]["recipientName"].asText())
        assertEquals(admin.id, body["history"][0]["actorUserId"].asText())
        listOf("private-payment-reference", "private-provider-reference", "private-pin-token", "raw-secret-payload").forEach {
            assertFalse(response.body().contains(it), it)
        }
        // No current catalog/profile is consulted to reconstruct the paid snapshot.
        assertEquals("Original product", body["lines"][0]["productName"].asText())
    }

    @Test
    fun missingOrMismatchedLegacyCheckoutKeepsStoredLinesAndExplicitUnknownOptions() {
        jdbc.update("update pending_payment_checkouts set request_payload=? where checkout_id=?", "{\"legacy\":true}", "actual-checkout")
        val legacy = tree(get("orders/actual-order"))
        assertEquals("UNAVAILABLE_LEGACY", legacy["snapshotState"].asText())
        assertTrue(legacy["homeDestination"].isNull)
        assertTrue(legacy["lines"][0]["selections"].isNull)
        assertEquals(3000, legacy["lines"][0]["lineTotalCfa"].asInt())
        assertEquals(404, get("orders/guessed-order").statusCode())
    }

    @Test
    fun directoriesAndDetailFailClosedForMissingExpiredOrNonAdminRoles() {
        val paths = listOf("orders/actual-order", "couriers", "couriers/actual-courier", "relay-sites", "relay-sites/site-a", "relay-sites/site-a/slots")
        val expired = JwtService("sequo_auth_dev_secret_key_2026_v1", -60_000, 60_000, "sequo-api", "sequo-mobile")
            .generateAccessToken(UserSession(requireNotNull(admin.id), null, AuthProvider.EMAIL, setOf(RoleCode.ADMIN)))
        for (path in paths) {
            assertEquals(200, get(path).statusCode(), path)
            assertTrue(get(path, null).statusCode() in setOf(401, 403), path)
            assertTrue(get(path, expired).statusCode() in setOf(401, 403), path)
            for (role in listOf(RoleCode.CUSTOMER, RoleCode.COURIER, RoleCode.RELAY_PARTNER, RoleCode.MERCHANT_OWNER, RoleCode.SUPPORT_AGENT)) {
                assertEquals(403, get(path, token(customer, setOf(role))).statusCode(), "$path/$role")
            }
        }
        assertFalse(get("couriers").body().contains("private-wallet"))
        assertEquals("NOT_INTEGRATED", tree(get("couriers/actual-courier"))["payOverview"]["transferStatus"].asText())
    }

    @Test
    fun workforceChangeIsFreshAuditedReplaySafeAndLeavesMissionAndLedgerSnapshotsUntouched() {
        val mission = missions.save(DeliveryMission(deliveryCode = "workforce-mission", orderId = "actual-order", courierId = "actual-courier",
            deliveryMode = DeliveryMissionRecordMode.STANDARD, destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            assignmentWorkforceType = DeliveryWorkforceType.Freelancer, courierFeeCfa = 400, customerDeliveryFeeCfa = 320, shortfallCfa = 80))
        ledger.save(SettlementLedgerEntryRecord("recorded-pay", SettlementLedgerAccount.CourierPayable, SettlementLedgerDirection.Credit,
            400, courierId = "actual-courier", sourceType = SettlementSourceType.DeliveryMission, sourceId = requireNotNull(mission.id),
            description = "Recorded liability", createdAt = at, workforceType = DeliveryWorkforceType.Freelancer))
        val revision = tree(get("couriers/actual-courier"))["courier"]["updatedAt"].asText()
        val body = workforce(revision, "workforce-intent")
        val first = send("PUT", "couriers/actual-courier/workforce", body)
        assertEquals(200, first.statusCode(), first.body())
        val result = tree(first)
        assertEquals("SequoSalaried", result["record"]["courier"]["workforceType"].asText())
        assertEquals(admin.id, result["operation"]["actorUserId"].asText())
        assertEquals(200, send("PUT", "couriers/actual-courier/workforce", body).statusCode())
        assertEquals(1L, changes.count())
        assertEquals(409, send("PUT", "couriers/actual-courier/workforce", workforce(revision, "new-stale-intent")).statusCode())
        assertEquals(409, send("PUT", "couriers/actual-courier/workforce", body.replace("Approved future workforce", "Changed intent")).statusCode())
        assertEquals(409, send("PUT", "couriers/actual-courier/workforce", body, token(secondAdmin)).statusCode())
        assertEquals(DeliveryWorkforceType.Freelancer, missions.findById(requireNotNull(mission.id)).orElseThrow().assignmentWorkforceType)
        assertEquals(400, missions.findById(requireNotNull(mission.id)).orElseThrow().courierFeeCfa)
        assertEquals(DeliveryWorkforceType.Freelancer, ledger.findById("recorded-pay").orElseThrow().workforceType)
        assertEquals(400, tree(get("couriers/actual-courier"))["payOverview"]["availableBalanceCfa"].asInt())
    }

    @Test
    fun unresolvedLegacyWorkforceBlocksChangeAndNoHistoryIsFabricated() {
        missions.save(DeliveryMission(deliveryCode = "legacy-workforce", orderId = "actual-order", courierId = "actual-courier",
            deliveryMode = DeliveryMissionRecordMode.STANDARD, destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS))
        val revision = tree(get("couriers/actual-courier"))["courier"]["updatedAt"].asText()
        val response = send("PUT", "couriers/actual-courier/workforce", workforce(revision, "legacy-intent"))
        assertEquals(409, response.statusCode(), response.body())
        assertEquals("legacy_workforce_review_required", tree(response)["code"].asText())
        assertEquals("FREELANCER", couriers.findById("actual-courier").orElseThrow().workforceType)
        assertEquals(0L, changes.count())
    }

    @Test
    fun relayMembershipRequiresEligibleExistingActorSiteAndOneActiveSiteAndRetainsHistoryOnRemoval() {
        val revision = tree(get("relay-sites/site-a"))["site"]["updatedAt"].asText()
        assertEquals(409, send("PUT", "relay-sites/site-a/operator", operator(revision, customer.id!!, "ineligible")).statusCode())
        assertEquals(0L, changes.count())
        val body = operator(revision, relayA.id!!, "assign-site-a")
        val first = send("PUT", "relay-sites/site-a/operator", body)
        assertEquals(200, first.statusCode(), first.body())
        assertEquals(relayA.id, tree(first)["record"]["site"]["operatorUserId"].asText())
        assertEquals(200, send("PUT", "relay-sites/site-a/operator", body).statusCode())
        assertEquals(1L, changes.count())
        // Reusing an existing key for another target is a conflict, not another binding.
        val siteB = tree(get("relay-sites/site-b"))["site"]["updatedAt"].asText()
        assertEquals(409, send("PUT", "relay-sites/site-b/operator", operator(siteB, relayA.id!!, "assign-site-a")).statusCode())
        assertEquals(409, send("PUT", "relay-sites/site-b/operator", operator(siteB, relayA.id!!, "assign-site-b")).statusCode())
        val after = tree(first)["record"]["site"]["updatedAt"].asText()
        val removal = """{"reason":"Explicit operator removal","expectedUpdatedAt":"$after","idempotencyKey":"remove-site-a"}"""
        val removed = send("DELETE", "relay-sites/site-a/operator", removal)
        assertEquals(200, removed.statusCode(), removed.body())
        assertTrue(tree(removed)["record"]["site"]["operatorUserId"].isNull)
        assertEquals(200, send("DELETE", "relay-sites/site-a/operator", removal).statusCode())
        assertEquals(2L, changes.count())
        assertEquals(200, send("PUT", "relay-sites/site-a/operator", body).statusCode()) // old replay never reassigns
        assertEquals(null, points.findById("site-a").orElseThrow().operatorUserId)
        assertEquals(setOf(RoleCode.RELAY_PARTNER), users.findById(relayA.id!!).orElseThrow().roles)
    }

    @Test
    fun concurrentBindingOfOneOperatorToTwoSitesHasOnlyOneWinner() {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = listOf("site-a", "site-b").map { site ->
                val revision = tree(get("relay-sites/$site"))["site"]["updatedAt"].asText()
                pool.submit(Callable { start.await(); send("PUT", "relay-sites/$site/operator", operator(revision, relayB.id!!, "concurrent-$site")).statusCode() })
            }
            start.countDown()
            assertEquals(listOf(200, 409), futures.map { it.get(10, TimeUnit.SECONDS) }.sorted())
            assertEquals(1, points.findByOperatorUserId(relayB.id!!).count { it.status == "ACTIVE" })
            assertEquals(1L, changes.count())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun mutationsDenyOtherRolesStaleInputAndInactiveAdministratorsAndLogicalSlotsAreRealReads() {
        val revision = tree(get("couriers/actual-courier"))["courier"]["updatedAt"].asText()
        assertEquals(403, send("PUT", "couriers/actual-courier/workforce", workforce(revision, "denied"), token(customer)).statusCode())
        assertEquals(400, send("PUT", "couriers/actual-courier/workforce", "{}").statusCode())
        jdbc.update("update users set is_active=false where id=?", admin.id)
        assertEquals(403, send("PUT", "couriers/actual-courier/workforce", workforce(revision, "inactive-admin")).statusCode())
        assertEquals(0L, changes.count())
        lockers.save(RelayLockerRecord("real-slot", "site-a", relayLockerGridId = "fixture-grid", lockerCode = "A01", status = HubLockerStatus.AVAILABLE))
        val slots = tree(get("relay-sites/site-a/slots"))
        assertEquals(1, slots.size())
        assertEquals("real-slot", slots[0]["id"].asText())
        assertEquals(0, tree(get("relay-sites/site-b/slots")).size())
        assertEquals(404, get("relay-sites/guessed-site/slots").statusCode())
    }

    @Test
    fun manualDispatchRequiresPaidParentAndRealEligibleCourierAndKeepsLegacyReadable() {
        val legacy = mission("legacy-manual", "unverified-order")
        val body = """{"courierId":"actual-courier"}"""
        val deniedLegacy = missionRequest("${legacy.id}/assign", body)
        assertEquals(400, deniedLegacy.statusCode(), deniedLegacy.body())
        assertEquals("order_unverified_for_assignment", tree(deniedLegacy)["code"].asText())
        assertEquals(200, missionRequest("${legacy.id}", null, method = "GET").statusCode())
        val valid = mission("paid-manual")
        jdbc.update("update customer_orders set payment_status='Pending' where id='actual-order'")
        assertEquals("order_not_paid_active", tree(missionRequest("${valid.id}/assign", body))["code"].asText())
        assertEquals(400, missionRequest("${valid.id}/offer", null).statusCode())
        jdbc.update("update customer_orders set payment_status='Validated' where id='actual-order'")
        assertEquals("courier_assignment_required", tree(missionRequest("${valid.id}/offer", null))["code"].asText())
        assertEquals("courier_not_found", tree(missionRequest("${valid.id}/assign", """{"courierId":"guessed-courier"}"""))["code"].asText())
        jdbc.update("update users set is_active=false where id=?", rider.id)
        assertEquals("courier_unavailable", tree(missionRequest("${valid.id}/assign", body))["code"].asText())
        jdbc.update("update users set is_active=true where id=?", rider.id)
        jdbc.update("update couriers set vehicle_type='UNKNOWN' where id='actual-courier'")
        assertEquals("courier_vehicle_unknown", tree(missionRequest("${valid.id}/assign", body))["code"].asText())
        jdbc.update("update couriers set vehicle_type='Moto' where id='actual-courier'")
        assertEquals(403, missionRequest("${valid.id}/assign", body, token(customer)).statusCode())
        assertEquals(200, missionRequest("${valid.id}/assign", body).statusCode())
    }

    @Test
    fun assignmentReplayDoesNotApplyNewWorkforceOrResetAcceptedTimestamp() {
        val mission = mission("manual-replay")
        val body = """{"courierId":"actual-courier","reason":"Reviewed courier assignment","idempotencyKey":"reviewed-manual-assignment"}"""
        val first = missionRequest("${mission.id}/assign", body)
        assertEquals(200, first.statusCode(), first.body())
        val assignedAt = tree(first)["assignedAt"].asText()
        jdbc.update("update couriers set workforce_type='SEQUO_SALARIED' where id='actual-courier'")
        jdbc.update("update delivery_missions set status='ACCEPTED_BY_COURIER',accepted_at=? where id=?", java.sql.Timestamp.from(at), mission.id)
        val repeat = missionRequest("${mission.id}/assign", body)
        assertEquals(200, repeat.statusCode(), repeat.body())
        assertEquals(assignedAt, tree(repeat)["assignedAt"].asText())
        assertEquals("Freelancer", tree(repeat)["assignmentWorkforceType"].asText())
        assertEquals(400, tree(repeat)["courierFeeCfa"].asInt())
        assertEquals(at, missions.findById(mission.id!!).orElseThrow().acceptedAt)
        // A different action cannot reuse the same key, even when the courier ID matches.
        assertEquals(409, missionRequest("${mission.id}/reassign", body).statusCode())
        assertEquals(at, missions.findById(mission.id!!).orElseThrow().acceptedAt)
        val history = tree(missionRequest("${mission.id}/admin-actions", null, method = "GET"))
        assertEquals(1, history.size())
        assertEquals(admin.id, history[0]["actorUserId"].asText())
        assertEquals("Reviewed courier assignment", history[0]["reason"].asText())
        assertEquals(409, missionRequest("${mission.id}/assign", body.replace("Reviewed courier assignment", "Changed reason")).statusCode())
        assertEquals(409, missionRequest("${mission.id}/assign", body, token(secondAdmin)).statusCode())
    }

    @Test
    fun unpauseCapturesRealActorReasonAndOneCanonicalRetryWithoutInventingLegacyReasons() {
        assertEquals(200, missionRequest("couriers/pause", """{"courierId":"actual-courier","reason":"Review availability"}""").statusCode())
        val payload = """{"courierId":"actual-courier","reason":"Availability reviewed","idempotencyKey":"reviewed-unpause"}"""
        val response = missionRequest("couriers/unpause", payload)
        assertEquals(200, response.statusCode(), response.body())
        val replay = missionRequest("couriers/unpause", payload)
        assertEquals(200, replay.statusCode())
        assertEquals(tree(response)["updatedAt"], tree(replay)["updatedAt"])
        val history = tree(missionRequest("couriers/actual-courier/admin-actions", null, method = "GET"))
        assertEquals(1, history.size())
        assertEquals(admin.id, history[0]["actorUserId"].asText())
        assertEquals("Availability reviewed", history[0]["reason"].asText())
        assertEquals(403, missionRequest("couriers/actual-courier/admin-actions", null, token(customer), method = "GET").statusCode())
        assertEquals(409, missionRequest("couriers/unpause", payload.replace("Availability reviewed", "Other reason")).statusCode())
    }

    @Test
    fun replacementResolutionUsesTheNewCouriersWorkforceAndCannotUseBusyCourier() {
        val other = users.save(User(email = "replacement@fixture.invalid", roles = mutableSetOf(RoleCode.COURIER)))
        couriers.save(CourierRecord("replacement-courier", userId = other.id, workforceType = "SEQUO_SALARIED", vehicleType = "Moto"))
        val original = mission("manual-resolution")
        assertEquals(200, missionRequest("${original.id}/assign", """{"courierId":"actual-courier"}""").statusCode())
        assertEquals(200, missionRequest("${original.id}/offer", null).statusCode())
        assertEquals(200, missionRequest("${original.id}/force-problem", """{"reason":"Reassign before collection"}""").statusCode())
        val replacement = missionRequest("${original.id}/resolve-problem", """{"action":"REQUEUE_FOR_DISPATCH","reason":"Use replacement","replacementCourierId":"replacement-courier"}""")
        assertEquals(200, replacement.statusCode(), replacement.body())
        assertEquals("SequoSalaried", tree(replacement)["assignmentWorkforceType"].asText())
        assertEquals(0, tree(replacement)["courierFeeCfa"].asInt())
        assertEquals(0, tree(replacement)["shortfallCfa"].asInt())
        val second = mission("manual-busy")
        assertEquals("courier_capacity_exhausted", tree(missionRequest("${second.id}/assign", """{"courierId":"replacement-courier"}"""))["code"].asText())
    }

    @Test
    fun manualAndPolicyAssignmentsCannotBothConsumeTheSameLastCapacity() {
        val first = mission("manual-concurrent")
        val second = mission("policy-concurrent")
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val a = pool.submit(Callable { start.await(); missionRequest("${first.id}/assign", """{"courierId":"actual-courier"}""").statusCode() })
            val b = pool.submit(Callable { start.await(); missionRequest("${second.id}/assign-policy", null).statusCode() })
            start.countDown()
            assertEquals(1, listOf(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)).count { it == 200 })
            assertEquals(1, listOf(first.id, second.id).count { missions.findById(it!!).orElseThrow().courierId == "actual-courier" })
        } finally { pool.shutdownNow() }
    }

    private fun mission(code: String, orderId: String = "actual-order") = missions.save(DeliveryMission(deliveryCode = code,
        orderId = orderId, deliveryMode = DeliveryMissionRecordMode.STANDARD, destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
        customerDeliveryFeeCfa = 320, courierFeeCfa = 400, shortfallCfa = 80, freelancerFeeEstimateCfa = 400))

    private fun missionRequest(path: String, body: String?, bearer: String = token(), method: String = "POST"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/delivery/missions/$path")).header("Authorization", "Bearer $bearer")
        body?.let { request.header("Content-Type", "application/json") }
        return client.send(request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun seedOrder() {
        val payload = """{"checkoutId":"actual-checkout","customerId":"${customer.id}","serviceLevel":"Regular","route":"FastDelivery","lines":[{"productId":"snapshot-product","sellerId":"snapshot-seller","sellerName":"Original seller","productName":"Original product","category":"GeneralGoods","quantity":2,"unitPriceCfa":1500,"photoEvidenceType":"GENERIC_CATALOG_IMAGE","selections":[{"groupId":"size","optionIds":["large"]}],"optionLabel":"Size: large"}],"deliveryDistanceKm":4,"referralCreditCfa":0,"paymentProvider":"yas_togo","paymentReference":"private-payment-reference","countryCode":"TG","homeDestination":{"addressId":"snapshot-address","label":"Home","recipientName":"customer","phoneNumber":"000000000","city":"Fixture city","latitude":6.1,"longitude":1.2},"raw":"raw-secret-payload"}"""
        checkouts.save(PendingPaymentCheckoutRecord("actual-checkout", customer.id!!, paymentProvider = "yas_togo", paymentReference = "private-payment-reference",
            amountCfa = 3400, requestPayload = payload, pricingPayload = "{}", status = PendingPaymentCheckoutStatus.COMPLETED, createdAt = at, updatedAt = at))
        orders.save(CustomerOrderRecord("actual-order", "actual-checkout", customerId = customer.id!!, serviceLevel = OrderServiceLevel.Regular,
            route = OrderRoute.FastDelivery, fulfillmentPriority = FulfillmentPriority.Standard, requiresConsolidation = false,
            customerFacingStatus = "Preparing", itemSubtotalCfa = 3000, deliveryFeeCfa = 400, totalCfa = 3400, paymentProvider = "yas_togo",
            paymentReference = "private-payment-reference", providerReference = "private-provider-reference", paymentStatus = PaymentValidationStatus.Validated,
            orderStatus = CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT, createdAt = at, updatedAt = at))
        lines.save(CustomerOrderLineRecord("snapshot-line", "actual-order", productId = "snapshot-product", sellerId = "snapshot-seller", sellerName = "Original seller",
            productName = "Original product", category = OrderProductCategory.GeneralGoods, quantity = 2, unitPriceCfa = 1500, negotiatedUnitPriceCfa = null,
            effectiveUnitPriceCfa = 1500, lineTotalCfa = 3000, photoEvidenceType = "GENERIC_CATALOG_IMAGE", lineIndex = 0, createdAt = at))
        events.save(CustomerOrderEventRecord("order-history", "actual-order", eventType = CustomerOrderEventType.ACCEPTED_FOR_FULFILLMENT,
            sourceType = "Fixture", sourceId = "actual-checkout", actorUserId = admin.id, metadata = "private-pin-token", createdAt = at))
    }

    private fun workforce(revision: String, key: String) = """{"workforceType":"SequoSalaried","reason":"Approved future workforce","expectedUpdatedAt":"$revision","idempotencyKey":"$key"}"""
    private fun operator(revision: String, user: String, key: String) = """{"operatorUserId":"$user","reason":"Approved operator binding","expectedUpdatedAt":"$revision","idempotencyKey":"$key"}"""
    private fun token(user: User = admin, roles: Set<RoleCode> = user.roles) = jwt.generateAccessToken(UserSession(user.id!!, null, AuthProvider.EMAIL, roles))
    private fun tree(response: HttpResponse<String>): JsonNode = json.readTree(response.body())
    private fun get(path: String, bearer: String? = token()) = send("GET", path, null, bearer)
    private fun send(method: String, path: String, body: String?, bearer: String? = token()): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/admin/web/$path"))
        bearer?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.header("Content-Type", "application/json") }
        return client.send(request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
}
