package dev.orestegabo.sequo_api.domain.reference

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo.reference.HumanReference
import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import dev.orestegabo.sequo_api.domain.relay.RelayPointRepository
import dev.orestegabo.sequo_api.domain.returns.ReturnJourneyFixtures
import dev.orestegabo.sequo_api.domain.settlement.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.url=jdbc:h2:mem:core_reference_projection;DB_CLOSE_DELAY=-1"])
class CorePublicReferenceProjectionHttpTest @Autowired constructor(
    private val context: ApplicationContext,
    private val registry: PublicReferenceRegistry,
    private val jdbc: JdbcTemplate,
    private val orders: CustomerOrderRecordRepository,
    private val users: UserRepository,
    private val points: RelayPointRepository,
    private val limiter: InMemoryRateLimiter,
) {
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()

    @BeforeEach fun isolate() {
        limiter.resetForTests()
        points.findAll().forEach { it.status = "INACTIVE"; points.save(it) }
    }

    @Test fun legacyOrderReadAllocatesStableDisplayMetadataWithoutChangingThePaidOrderOrScope() {
        val fixture = ReturnJourneyFixtures.order(context, Instant.now().minusSeconds(3600))
        val stranger = ReturnJourneyFixtures.order(context, Instant.now().minusSeconds(3600))
        jdbc.update("delete from public_entity_references where entity_kind = ? and internal_id = ?", "ORDER", fixture.orderId)
        val before = orders.findById(fixture.orderId).orElseThrow()
        val version = before.version
        val updatedAt = before.updatedAt
        val paymentReference = before.paymentReference
        assertNull(registry.findReference(PublicReferenceKind.ORDER, fixture.orderId))
        val first = get("/api/orders/${fixture.orderId}", fixture.owner.token)
        val self = first["order"]["publicReference"].asText()
        assertTrue(HumanReference.isValid(self, PublicReferenceKind.ORDER))
        assertEquals(fixture.orderId, registry.resolve(PublicReferenceKind.ORDER, self))
        assertTrue(HumanReference.isValid(first["order"]["checkoutReference"].asText(), PublicReferenceKind.CHECKOUT))
        assertTrue(HumanReference.isValid(first["order"]["customerReference"].asText(), PublicReferenceKind.USER))
        first["merchantSubOrders"].forEach {
            assertTrue(HumanReference.isValid(it["publicReference"].asText(), PublicReferenceKind.MERCHANT_ORDER))
            assertEquals(self, it["orderReference"].asText())
        }
        assertEquals(self, get("/api/orders/${fixture.orderId}", fixture.owner.token)["order"]["publicReference"].asText())
        assertEquals(404, call("GET", "/api/orders/${fixture.orderId}", stranger.owner.token).statusCode())
        val after = orders.findById(fixture.orderId).orElseThrow()
        assertEquals(fixture.orderId, after.id)
        assertEquals(version, after.version)
        assertEquals(updatedAt, after.updatedAt)
        assertEquals(paymentReference, after.paymentReference)
    }

    @Test fun returnLookupUsesCurrentRelayScopeAndRedactedActorsDoNotReappearAsReferences() {
        val first = ReturnJourneyFixtures.relayWithOperator(context)
        val second = ReturnJourneyFixtures.relayWithOperator(context)
        val fixture = ReturnJourneyFixtures.order(context, Instant.now().minusSeconds(3600))
        val stranger = ReturnJourneyFixtures.order(context, Instant.now().minusSeconds(3600))
        val id = "reference-return-${UUID.randomUUID()}"
        val created = post("/api/returns", fixture.owner.token, ReturnJourneyFixtures.body(id, fixture.orderId))
        val reference = created["publicReference"].asText()
        assertTrue(HumanReference.isValid(reference, PublicReferenceKind.RETURN))
        assertTrue(HumanReference.isValid(created["orderReference"].asText(), PublicReferenceKind.ORDER))
        assertEquals(403, call("GET", "/api/returns/$id", stranger.owner.token).statusCode())
        fun lookup(point: String) = json.writeValueAsString(mapOf("kind" to "RETURN", "reference" to reference, "relayPointId" to point))
        // An unassigned eligible return can be taken to either currently available bound relay.
        assertEquals(id, post("/api/references/resolve", first.operator.token, lookup(first.id))["id"].asText())
        assertEquals(id, post("/api/references/resolve", second.operator.token, lookup(second.id))["id"].asText())
        assertEquals(403, call("POST", "/api/references/resolve", first.operator.token, lookup(second.id)).statusCode())
        val pin = created["returnPin"].asText()
        assertTrue(pin.matches(Regex("[0-9]{6}")))
        post("/api/returns/$id/relay-dropoff", first.operator.token, json.writeValueAsString(mapOf(
            "relayPointId" to first.id, "rawReturnPin" to pin, "idempotencyKey" to "reference-dropoff-${UUID.randomUUID()}")))
        val relay = get("/api/returns/$id", first.operator.token)
        assertEquals(reference, relay["publicReference"].asText())
        assertEquals("", relay["customerId"].asText())
        assertTrue(relay.path("customerReference").isNull)
        assertTrue(relay.path("returnPin").isNull)
        val intake = relay["history"].single { it["action"].asText() == "RELAY_DROPOFF" }
        assertTrue(HumanReference.isValid(intake["actorReference"].asText(), PublicReferenceKind.USER))
        assertEquals(first.operator.id, registry.resolve(PublicReferenceKind.USER, intake["actorReference"].asText()))
        val owner = get("/api/returns/$id", fixture.owner.token)
        owner["history"].forEach {
            assertTrue(it.path("actorUserId").isNull)
            assertTrue(it.path("actorReference").isNull)
        }
        assertEquals(404, call("POST", "/api/references/resolve", second.operator.token, lookup(second.id)).statusCode())
        assertEquals(403, call("GET", "/api/returns/$id", second.operator.token).statusCode())
    }

    @Test fun legacyNonMissionEarningGetsALedgerReferenceWithoutInventingAMissionOrTransfer() {
        val rider = users.save(User(email = "reference-rider-${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL,
            roles = mutableSetOf(RoleCode.COURIER)))
        val riderId = requireNotNull(rider.id)
        val courierId = "reference-courier-${UUID.randomUUID()}"
        context.getBean(SettlementCourierRecordRepository::class.java).save(CourierRecord(courierId, userId = riderId,
            workforceType = "Freelancer", vehicleType = "Moto"))
        val id = "legacy-earned-${UUID.randomUUID()}"
        context.getBean(SettlementLedgerEntryRecordRepository::class.java).save(SettlementLedgerEntryRecord(
            id, SettlementLedgerAccount.CourierPayable, SettlementLedgerDirection.Credit, 75,
            courierId = courierId, sourceType = SettlementSourceType.Adjustment, sourceId = "legacy-other-source",
            description = "Isolated legacy recorded earning fixture, no transfer.", createdAt = Instant.now(),
            workforceType = dev.orestegabo.sequo_api.domain.delivery.DeliveryWorkforceType.Freelancer))
        val token = ReturnJourneyFixtures.token(context, riderId)
        val entry = get("/api/rider/payouts/history", token).single { it["id"].asText() == id }
        assertTrue(HumanReference.isValid(entry["publicReference"].asText(), PublicReferenceKind.LEDGER))
        assertEquals(id, registry.resolve(PublicReferenceKind.LEDGER, entry["publicReference"].asText()))
        assertTrue(entry.path("missionReference").isNull)
        assertNull(registry.findReference(PublicReferenceKind.FINANCIAL, id))
        val summary = get("/api/rider/payouts/summary", token)
        assertEquals(0, summary["paidCfa"].asInt())
        assertEquals("NOT_INTEGRATED", summary["transferStatus"].asText())
        val foreign = ReturnJourneyFixtures.order(context, Instant.now().minusSeconds(3600))
        assertEquals(403, call("GET", "/api/rider/payouts/history", foreign.owner.token).statusCode())
    }

    private fun get(path: String, token: String) = parse(call("GET", path, token))
    private fun post(path: String, token: String, body: String) = parse(call("POST", path, token, body))
    private fun parse(response: java.net.http.HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), "Expected an authorized fixture response: ${response.body()}")
        return json.readTree(response.body())
    }
    private fun call(method: String, path: String, token: String, body: String? = null) =
        ReturnJourneyFixtures.request(port, method, path, token, body)
}
