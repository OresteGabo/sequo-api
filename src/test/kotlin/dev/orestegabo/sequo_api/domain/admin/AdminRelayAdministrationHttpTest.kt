package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.localfixture.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import dev.orestegabo.sequo_api.domain.relay.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.*
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:sequo_admin_dev_relay_setup;DB_CLOSE_DELAY=-1",
    "server.address=127.0.0.1", "server.forward-headers-strategy=NONE",
    "sequo.admin.local-access.enabled=true", "sequo.admin.local-access.environment=LOCAL_FIXTURE",
    "sequo.admin.local-access.user-id=00000000-0000-0000-0000-000000000073", "sequo.admin.local-fixtures.enabled=true",
    "sequo.security.cors.allowed-origins=http://127.0.0.1:5173",
    "sequo.auth.jwt.secret=relay_administration_journey_signing_key_for_tests",
    "sequo.auth.jwt.issuer=sequo-api-local-admin-fixture", "sequo.auth.jwt.audience=sequo-admin-local-fixture",
    "sequo.notifications.outbox-worker.enabled=false", "sequo.notifications.provider-delivery-worker.enabled=false",
    "sequo.wallets.yas-togo.api-base-url=", "sequo.wallets.yas-togo.api-key=", "sequo.wallets.yas-togo.merchant-account-id=", "sequo.wallets.yas-togo.webhook-secret=",
    "sequo.wallets.moov-africa.api-base-url=", "sequo.wallets.moov-africa.api-key=", "sequo.wallets.moov-africa.merchant-account-id=", "sequo.wallets.moov-africa.webhook-secret=",
])
class AdminRelayAdministrationHttpTest @Autowired constructor(
    private val foundation: LocalOperationalFixtureFoundation, private val journeys: LocalOperationalJourneySeeder,
    private val jwt: JwtService, private val jdbc: JdbcTemplate, private val availability: RelayAvailabilityService,
    private val merchant: MerchantFulfillmentService, private val delivery: DeliveryMissionService,
    private val assignment: GuardedDeliveryAssignmentService, private val orders: CustomerOrderRecordRepository,
    private val lockers: RelayLockerRepository, private val points: RelayPointRepository,
    private val parcels: RelayParcelRecordRepository, private val custody: RelayCustodyEventRecordRepository,
    private val products: CommerceProductRepository, private val adminChanges: RelayAdministrationOperationRepository,
) {
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val http = HttpClient.newHttpClient()
    private val adminId = "00000000-0000-0000-0000-000000000073"
    private lateinit var adminToken: String
    private fun request(method: String, path: String, body: Any? = null, token: String? = adminToken): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        return http.send(builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun ok(response: HttpResponse<String>): JsonNode { assertEquals(200, response.statusCode(), response.body()); return json.readTree(response.body()) }
    private fun setup(id: String) = ok(request("GET", "/api/admin/web/relay-sites/$id/setup"))
    private fun success(result: DeliveryMissionServiceResult) = assertIs<DeliveryMissionServiceResult.Success>(result).mission

    @Test fun setupAndSequoDepositUseRealEligibilityLocksActorsHistoryAndFrozenReplay() {
        val at = Instant.now()
        jdbc.update("insert into users(id,email,display_name,is_active,provider,status,created_at,updated_at) values (?,?,?,true,'EMAIL','ACTIVE',?,?)",
            adminId, "relay-setup-admin@fixture.invalid", "Relay setup administrator", Timestamp.from(at), Timestamp.from(at))
        jdbc.update("insert into user_roles(user_id,role_code) values (?,?)", adminId, "SUPER_ADMIN")
        adminToken = jwt.generateAccessToken(UserSession(adminId, null, AuthProvider.EMAIL, setOf(RoleCode.SUPER_ADMIN)))
        val context = LocalOperationalFixtureContext(adminId, at)
        foundation.seed(context); journeys.seedRelayOrders(context)
        val customer = jwt.generateAccessToken(UserSession(context.customerId, null, AuthProvider.EMAIL, setOf(RoleCode.CUSTOMER)))
        val root = "/api/admin/web/relay-sites"
        val siteBody = mapOf("name" to "Recorded new relay", "latitude" to 6.18, "longitude" to 1.22, "city" to "Fixture city",
            "reason" to "Verified partner location", "idempotencyKey" to "new-relay-site")
        assertTrue(request("POST", root, siteBody, null).statusCode() in listOf(401,403))
        assertEquals(403, request("POST", root, siteBody, customer).statusCode())
        assertEquals(400, request("POST", root, siteBody + ("latitude" to 200)).statusCode())
        val created = ok(request("POST", root, siteBody)); val siteId = created["record"]["site"]["id"].asText()
        assertEquals(adminId, created["operation"]["actorUserId"].asText())
        assertNull(created["record"]["site"]["operatorUserId"].textValue())
        assertTrue(availability.available().none { it.id == siteId })
        assertEquals(created["operation"]["id"], ok(request("POST", root, siteBody))["operation"]["id"])
        assertEquals(409, request("POST", root, siteBody + ("name" to "Changed retry")).statusCode())
        var current = setup(siteId)
        ok(request("PUT", "$root/$siteId/operator", mapOf("operatorUserId" to context.id("operator-backup"),
            "expectedUpdatedAt" to current["site"]["updatedAt"].asText(), "reason" to "Existing eligible operator assigned", "idempotencyKey" to "bind-new-site")))
        assertTrue(availability.available().none { it.id == siteId })
        current = setup(siteId)
        val slotBody = mapOf("lockerCode" to "A1", "expectedUpdatedAt" to current["site"]["updatedAt"].asText(), "reason" to "Storage shelf physically labelled", "idempotencyKey" to "slot-A1-retry")
        val added = ok(request("POST", "$root/$siteId/slots", slotBody))
        assertTrue(availability.available().any { it.id == siteId })
        assertEquals(1, added["record"]["slots"].size())
        assertEquals(added["operation"]["id"], ok(request("POST", "$root/$siteId/slots", slotBody))["operation"]["id"])
        val slot = added["record"]["slots"][0]; val slotId = slot["id"].asText()
        val maintenance = mapOf("status" to "MAINTENANCE", "expectedUpdatedAt" to slot["updatedAt"].asText(), "reason" to "Shelf requires inspection", "idempotencyKey" to "slot-maintenance")
        val maintained = ok(request("PUT", "$root/$siteId/slots/$slotId/availability", maintenance))
        assertTrue(availability.available().none { it.id == siteId })
        assertEquals(maintained["operation"]["id"], ok(request("PUT", "$root/$siteId/slots/$slotId/availability", maintenance))["operation"]["id"])
        assertEquals(409, request("PUT", "$root/$siteId/slots/$slotId/availability", maintenance + mapOf("status" to "AVAILABLE", "idempotencyKey" to "stale-slot")).statusCode())
        assertEquals(400, request("PATCH", "$root/$siteId/details", siteBody + mapOf("latitude" to 6.2, "expectedUpdatedAt" to setup(siteId)["site"]["updatedAt"].asText(), "idempotencyKey" to "relocate-used-site")).statusCode())
        val revision = setup(siteId)["site"]["updatedAt"].asText()
        val competing = listOf("B1","B2").map { code -> CompletableFuture.supplyAsync { request("POST", "$root/$siteId/slots", mapOf("lockerCode" to code,
            "expectedUpdatedAt" to revision, "reason" to "Verified additional logical slot", "idempotencyKey" to "create-$code")) } }.map { it.join().statusCode() }
        assertEquals(listOf(200,409), competing.sorted())
        assertEquals("no-store", request("GET", "$root/$siteId/setup").headers().firstValue("cache-control").orElseThrow())

        val orderId = context.relayOrderIds.getValue("fresh")
        val part = merchant.listForOrder(orderId).single()
        assertIs<MerchantFulfillmentServiceResult.Success>(merchant.accept(part.id, part.merchantId))
        assertIs<MerchantFulfillmentServiceResult.Success>(merchant.startPreparation(part.id, part.merchantId))
        assertIs<MerchantFulfillmentServiceResult.Success>(merchant.markPacked(part.id, part.merchantId, 1))
        val destination = availability.requireAvailable(context.relayPointIds[1])
        val mission = delivery.create(CreateDeliveryMissionCommand("verified-sequo-receipt", orderId, part.id,
            DeliveryMissionRecordMode.RELAY, DeliveryMissionRecordDestination.RELAY_POINT, customerDeliveryFeeCfa = 400, courierFeeCfa = 400,
            relayDestination = destination))
        success(assignment.assign(mission.id, context.courierIds[0], adminId, "Normal courier assignment", "receipt-assign"))
        success(assignment.offer(mission.id, adminId, "Normal courier offer", "receipt-offer"))
        val target = context.relayPointIds[1]; val targetSlot = lockers.findById(context.id("slot-2-1")).orElseThrow()
        fun depositBody(key: String = "sequo-receipt") = mapOf("missionId" to mission.id, "lockerId" to targetSlot.id,
            "expectedMissionUpdatedAt" to delivery.get(mission.id)!!.updatedAt.toString(), "expectedSlotUpdatedAt" to lockers.findById(targetSlot.id).orElseThrow().updatedAt.toString(),
            "physicalReceiptConfirmed" to true, "reason" to "Package physically received by SEQUO at selected relay", "idempotencyKey" to key)
        assertEquals(400, request("POST", "$root/$target/deposits", depositBody() + ("physicalReceiptConfirmed" to false)).statusCode())
        assertEquals(400, request("POST", "$root/$target/deposits", depositBody()).statusCode())
        success(delivery.acceptForCourier(mission.id, context.id("rider-senam")))
        success(delivery.transitionIdempotent(mission.id, context.id("rider-senam"), DeliveryMissionEvent.CourierPicksUpFromSeller, "recorded-real-pickup", proof = "Normal rider pickup proof"))
        val body = depositBody()
        assertEquals(403, request("POST", "$root/$target/deposits", body, customer).statusCode())
        assertEquals(400, request("POST", "$root/$siteId/deposits", body).statusCode())
        jdbc.update("update customer_orders set payment_status='Pending' where id=?", orderId)
        assertEquals(400, request("POST", "$root/$target/deposits", body).statusCode())
        jdbc.update("update customer_orders set payment_status='Validated' where id=?", orderId)
        val product = products.findById(context.generalProductId).orElseThrow(); product.category = "Food"; products.saveAndFlush(product)
        assertEquals(400, request("POST", "$root/$target/deposits", body).statusCode())
        products.findById(context.generalProductId).orElseThrow().also { it.category = "GenericSealedItem"; products.saveAndFlush(it) }
        val deposited = ok(request("POST", "$root/$target/deposits", body))
        val parcelId = deposited["parcelId"].asText()
        assertEquals("DEPOSITED_AT_RELAY", deposited["mission"]["status"].asText())
        assertEquals(context.courierIds[0], deposited["mission"]["courierId"].asText())
        assertEquals(adminId, jdbc.queryForObject("select relay_deposit_proof_actor_id from delivery_missions where id=?", String::class.java, mission.id))
        assertEquals(adminId, custody.findByRelayParcelIdOrderByCreatedAtAsc(parcelId).single { it.type == RelayCustodyEventType.Deposit }.actorUserId)
        assertEquals(HubLockerStatus.OCCUPIED, lockers.findById(targetSlot.id).orElseThrow().status)
        assertEquals(1, parcels.findByDeliveryMissionId(mission.id).size)
        assertEquals(deposited["operation"]["id"], ok(request("POST", "$root/$target/deposits", body))["operation"]["id"])
        assertEquals(409, request("POST", "$root/$target/deposits", body + ("reason" to "Changed repeated intent")).statusCode())
        val occupied = lockers.findById(targetSlot.id).orElseThrow()
        assertEquals(400, request("PUT", "$root/$target/slots/${targetSlot.id}/availability", mapOf("status" to "AVAILABLE", "expectedUpdatedAt" to occupied.updatedAt.toString(), "reason" to "Attempted occupied reset", "idempotencyKey" to "occupied-reset")).statusCode())
        val legacySwitch = mapOf("relayPointId" to target, "status" to "AVAILABLE")
        assertEquals(400, request("POST", "/api/hub/lockers/${targetSlot.id}/availability", legacySwitch).statusCode())
        // Even a legacy inconsistent AVAILABLE row must not conceal active parcel custody.
        jdbc.update("update relay_lockers set status='AVAILABLE' where id=?", targetSlot.id)
        assertEquals(400, request("POST", "/api/hub/lockers/${targetSlot.id}/availability", legacySwitch).statusCode())
        assertEquals(400, request("POST", "/api/hub/lockers/${targetSlot.id}/availability", legacySwitch + ("status" to "OCCUPIED")).statusCode())
        assertEquals(400, request("POST", "/api/hub/lockers/unknown-logical-slot/availability", legacySwitch).statusCode())
        assertFalse(lockers.existsById("unknown-logical-slot"))
        jdbc.update("update relay_lockers set status='OCCUPIED' where id=?", targetSlot.id)
        assertEquals(1, adminChanges.findTop50ByRelayPointIdOrderByCreatedAtDesc(target).count { it.action == "SEQUO_DEPOSIT" })
        val raw = mapOf("parcelId" to "forged-parcel", "relayPointId" to target, "orderId" to orderId, "category" to "GeneralGoods", "depositCode" to "forged",
            "createdAt" to "2000-01-01T00:00:00Z", "availableLockers" to listOf(mapOf("id" to targetSlot.id, "relayPointId" to target, "active" to true, "occupied" to false)))
        assertEquals(400, request("POST", "/api/relay/parcels", raw).statusCode())
        assertFalse(parcels.existsById("forged-parcel"))
    }
}
