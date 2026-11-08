package dev.orestegabo.sequo_api.domain.relay

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.url=jdbc:h2:mem:relay_operator_http;DB_CLOSE_DELAY=-1"])
class RelayOperatorHttpTest @Autowired constructor(
    private val users: UserRepository,
    private val jwt: JwtService,
    private val points: RelayPointRepository,
    private val credentials: RelayPickupCodeRecordRepository,
    private val missions: dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionService,
) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper().findAndRegisterModules()

    private fun token(role: RoleCode): Pair<String, String> {
        val user = users.save(User(email = "${UUID.randomUUID()}@fixture.invalid", passwordHash = "unused",
            name = "Relay fixture", provider = AuthProvider.EMAIL, roles = mutableSetOf(role)))
        val id = requireNotNull(user.id)
        return id to jwt.generateAccessToken(UserSession(id, user.email, user.provider, setOf(role)))
    }

    private fun call(method: String, path: String, token: String? = null, body: Any? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        if (body != null) request.header("Content-Type", "application/json")
        return client.send(request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
            ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun overdueDecisionsExposeStoredActorsAndRequireAuthorizedCurrentCustody() {
        val operator = token(RoleCode.RELAY_PARTNER)
        val other = token(RoleCode.RELAY_PARTNER)
        val admin = token(RoleCode.ADMIN)
        val site = "site-${UUID.randomUUID()}"
        val otherSite = "site-${UUID.randomUUID()}"
        points.save(RelayPointRecord(id = site, operatorUserId = operator.first, name = "Decision fixture"))
        points.save(RelayPointRecord(id = otherSite, operatorUserId = other.first, name = "Other decision fixture"))
        val parcel = "parcel-${UUID.randomUUID()}"
        val path = "/api/relay/parcels/$parcel"
        val creation = call("POST", "/api/relay/parcels", operator.second, mapOf(
            "parcelId" to parcel, "relayPointId" to site, "orderId" to "isolated-order", "category" to "GeneralGoods",
            "depositCode" to UUID.randomUUID().toString(), "createdAt" to Instant.now().minusSeconds(31L * 86400).toString(),
            "availableLockers" to listOf(mapOf("id" to "slot-$parcel", "relayPointId" to site, "active" to true, "occupied" to false))))
        assertEquals(200, creation.statusCode(), creation.body())
        val review = mapOf("eventId" to UUID.randomUUID().toString(), "idempotencyKey" to UUID.randomUUID().toString(),
            "metadata" to "Keep parcel pending SEQUO collection.")
        for (denied in listOf(other.second, token(RoleCode.CUSTOMER).second, "expired.invalid.token", null)) {
            assertEquals(403, call("POST", "$path/review", denied, review).statusCode())
        }
        val responses = (1..2).map { CompletableFuture.supplyAsync { call("POST", "$path/review", operator.second, review) } }.map { it.join() }
        responses.forEach { assertEquals(200, it.statusCode(), it.body()) }
        fun stored() = json.readTree(call("GET", path, admin.second).body())
        val history = stored()["history"]
        val savedReview = history.single { it["type"].asText() == "Review" }
        assertEquals(review["eventId"], savedReview["id"].asText())
        assertEquals(operator.first, savedReview["actorUserId"].asText())
        assertEquals(review["metadata"], savedReview["message"].asText())
        assertEquals(2, history.size())
        val disposition = mapOf("eventId" to UUID.randomUUID().toString(), "idempotencyKey" to UUID.randomUUID().toString(),
            "metadata" to "Physical return to seller, fixture receipt.")
        assertEquals(403, call("POST", "$path/return-to-seller", operator.second, disposition).statusCode())
        val returned = (1..2).map { CompletableFuture.supplyAsync { call("POST", "$path/return-to-seller", admin.second, disposition) } }.map { it.join() }
        returned.forEach { assertEquals(200, it.statusCode(), it.body()) }
        val readback = stored()
        assertEquals("ReturnedToSeller", readback["status"].asText())
        val savedReturn = readback["history"].single { it["type"].asText() == "ReturnDropoff" }
        assertEquals(admin.first, savedReturn["actorUserId"].asText())
        assertEquals(disposition["eventId"], savedReturn["id"].asText())
        assertEquals(3, readback["history"].size())
        assertEquals(400, call("POST", "$path/review", operator.second, review + ("idempotencyKey" to UUID.randomUUID().toString())).statusCode())
        assertEquals(400, call("POST", "$path/return-to-seller", admin.second, disposition + ("idempotencyKey" to UUID.randomUUID().toString())).statusCode())
        assertEquals(3, stored()["history"].size())
    }

    @Test fun twoOperatorsCannotReadOrMutateEachOthersSitesAndCredentialsAreSingleUse() {
        val a = token(RoleCode.RELAY_PARTNER)
        val b = token(RoleCode.RELAY_PARTNER)
        val admin = token(RoleCode.ADMIN).second
        val customer = token(RoleCode.CUSTOMER).second
        val courierAccount = token(RoleCode.COURIER)
        val courier = courierAccount.second
        val unassigned = token(RoleCode.RELAY_PARTNER).second
        val siteA = "site-${UUID.randomUUID()}"
        val siteB = "site-${UUID.randomUUID()}"
        points.save(RelayPointRecord(id = siteA, operatorUserId = a.first, name = "A"))
        points.save(RelayPointRecord(id = siteB, operatorUserId = b.first, name = "B"))
        fun deposit(site: String, actor: String, category: RelayParcelCategory = RelayParcelCategory.GeneralGoods): Pair<String, HttpResponse<String>> {
            val id = "parcel-${UUID.randomUUID()}"
            return id to call("POST", "/api/relay/parcels", actor, mapOf("parcelId" to id, "relayPointId" to site, "orderId" to "isolated-order", "category" to category.name,
                "depositCode" to "deposit-$id", "availableLockers" to listOf(mapOf("id" to "slot-$id", "relayPointId" to site, "active" to true, "occupied" to false)), "createdAt" to Instant.now().toString()))
        }
        val (parcelA, createdA) = deposit(siteA, a.second)
        val (parcelB, createdB) = deposit(siteB, b.second)
        assertEquals(200, createdA.statusCode(), createdA.body())
        assertEquals(200, createdB.statusCode(), createdB.body())
        assertEquals(403, deposit(siteB, a.second).second.statusCode())
        assertEquals(403, deposit(siteA, b.second).second.statusCode())
        assertEquals(403, deposit(siteA, courier).second.statusCode())
        val mission = missions.create(dev.orestegabo.sequo_api.domain.delivery.CreateDeliveryMissionCommand(
            deliveryCode = "RELAY-${UUID.randomUUID()}", orderId = "isolated-order",
            deliveryMode = dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordMode.RELAY,
            destinationType = dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordDestination.RELAY_POINT))
        missions.assignCourier(mission.id, courierAccount.first)
        val courierParcelId = UUID.randomUUID().toString()
        val courierDeposit = mapOf("parcelId" to courierParcelId, "relayPointId" to siteA, "deliveryMissionId" to mission.id,
            "category" to "GeneralGoods", "depositCode" to UUID.randomUUID().toString(), "createdAt" to Instant.now().toString(),
            "availableLockers" to listOf(mapOf("id" to "logical-slot", "relayPointId" to siteA, "active" to true, "occupied" to false)))
        assertEquals(400, call("POST", "/api/relay/parcels", courier, courierDeposit).statusCode())
        assertEquals(400, call("POST", "/api/relay/parcels", token(RoleCode.COURIER).second,
            courierDeposit + ("parcelId" to UUID.randomUUID().toString())).statusCode())
        assertEquals(404, call("GET", "/api/relay/parcels/$courierParcelId", a.second).statusCode())
        // A generic delivery route cannot bypass actual pickup verification, even for the site's operator.
        assertEquals(403, call("POST", "/api/delivery/missions/${mission.id}/relay-release", a.second,
            mapOf("pickupCodeValidated" to true, "identityValidated" to true, "proofMetadata" to "forged proof")).statusCode())
        assertEquals(403, call("POST", "/api/delivery/missions/${mission.id}/problem", b.second,
            mapOf("reason" to "cross-site problem")).statusCode())
        for (actor in listOf(a.second, b.second)) {
            assertEquals(403, call("POST", "/api/delivery/missions/guessed-mission/relay-release", actor,
                mapOf("pickupCodeValidated" to true, "identityValidated" to true, "proofMetadata" to "forged proof")).statusCode())
            assertEquals(403, call("POST", "/api/delivery/missions/guessed-mission/problem", actor,
                mapOf("reason" to "forged problem")).statusCode())
        }
        for (category in listOf(RelayParcelCategory.Food, RelayParcelCategory.Perishable, RelayParcelCategory.Sensitive))
            assertEquals(400, deposit(siteA, a.second, category).second.statusCode())
        assertTrue(call("GET", "/api/relay/parcels/sites", a.second).body().contains(siteA))
        assertFalse(call("GET", "/api/relay/parcels/sites", a.second).body().contains(siteB))
        assertEquals("[]", call("GET", "/api/relay/parcels/sites", unassigned).body())
        for ((own, other, site, otherSite, actor) in listOf(
            listOf(parcelA, parcelB, siteA, siteB, a.second), listOf(parcelB, parcelA, siteB, siteA, b.second))) {
            assertEquals(200, call("GET", "/api/relay/parcels?relayPointId=$site", actor).statusCode())
            assertEquals(403, call("GET", "/api/relay/parcels?relayPointId=$otherSite", actor).statusCode())
            assertEquals(200, call("GET", "/api/relay/parcels/$own", actor).statusCode())
            assertEquals(403, call("GET", "/api/relay/parcels/$other?relayPointId=$otherSite", actor).statusCode())
            assertEquals(403, call("GET", "/api/relay/parcels/$other?relayPointId=$site", actor).statusCode())
            assertEquals(403, call("GET", "/api/hub/summary?hubId=$otherSite", actor).statusCode())
            assertEquals(200, call("GET", "/api/hub/summary?hubId=$site", actor).statusCode())
            for (route in listOf("opening-hours", "control-state", "control-state/history"))
                assertEquals(403, call("GET", "/api/hub/$route?relayPointId=$otherSite", actor).statusCode())
            val scan = call("POST", "/api/hub/scan/resolve", actor,
                mapOf("hubId" to otherSite, "credential" to "123456", "credentialType" to "AUTO"))
            assertEquals(403, scan.statusCode(), scan.body())
            assertEquals(403, call("POST", "/api/hub/lockers/guessed-slot/availability", actor,
                mapOf("relayPointId" to otherSite, "status" to "AVAILABLE")).statusCode())
            assertEquals(403, call("POST", "/api/relay/parcels/$other/problem", actor,
                mapOf("eventId" to UUID.randomUUID().toString(), "idempotencyKey" to UUID.randomUUID().toString(), "metadata" to "Denied")).statusCode())
        }
        assertEquals(403, call("GET", "/api/relay/parcels?relayPointId=$siteA").statusCode())
        for (denied in listOf(customer, courier, unassigned, "expired.invalid.token"))
            assertEquals(403, call("GET", "/api/relay/parcels?relayPointId=$siteA", denied).statusCode())
        assertEquals(200, call("GET", "/api/relay/parcels?relayPointId=$siteB", admin).statusCode())
        val codeId = UUID.randomUUID().toString()
        val code = mapOf("codeId" to codeId, "rawNumericCode" to "123456", "rawQrNonce" to "isolated-qr-nonce-for-relay",
            "identityCheckRequired" to true, "expiresAt" to Instant.now().plusSeconds(3600).toString())
        assertEquals(403, call("POST", "/api/relay/parcels/$parcelA/pickup-code", b.second, code).statusCode())
        assertEquals(400, call("POST", "/api/relay/parcels/$parcelA/pickup-code", a.second, code + ("identityCheckRequired" to false)).statusCode())
        assertEquals(200, call("POST", "/api/relay/parcels/$parcelA/pickup-code", a.second, code).statusCode())
        assertEquals(400, call("POST", "/api/relay/parcels/$parcelB/pickup-code", b.second, code).statusCode())
        assertEquals(parcelA, credentials.findById(codeId).orElseThrow().relayParcelId)
        val key = UUID.randomUUID().toString()
        val release = mapOf("relayPointId" to siteA, "rawNumericCode" to "123456", "identityDocumentMatched" to true,
            "eventId" to UUID.randomUUID().toString(), "idempotencyKey" to key)
        assertEquals(403, call("POST", "/api/relay/parcels/$parcelA/release", b.second, release).statusCode())
        assertEquals(403, call("POST", "/api/relay/parcels/$parcelA/release", a.second, release + ("relayPointId" to siteB)).statusCode())
        assertEquals(400, call("POST", "/api/relay/parcels/$parcelA/release", a.second, release + ("identityDocumentMatched" to false)).statusCode())
        assertEquals(400, call("POST", "/api/relay/parcels/$parcelA/release", a.second, release + ("rawNumericCode" to "999999")).statusCode())
        assertEquals(2, credentials.findById(codeId).orElseThrow().attemptCount)
        val releases = (1..2).map { CompletableFuture.supplyAsync { call("POST", "/api/relay/parcels/$parcelA/release", a.second, release) } }.map { it.join() }
        releases.forEach { assertEquals(200, it.statusCode(), it.body()) }
        assertEquals(3, credentials.findById(codeId).orElseThrow().attemptCount)
        assertEquals(400, call("POST", "/api/relay/parcels/$parcelA/release", a.second, release + ("idempotencyKey" to UUID.randomUUID().toString())).statusCode())
        val readback = call("GET", "/api/relay/parcels/$parcelA", a.second)
        assertEquals("PickedUp", json.readTree(readback.body())["status"].asText())
        assertFalse(readback.body().contains("123456"))
        val ownCodeB = code + ("codeId" to UUID.randomUUID().toString())
        assertEquals(200, call("POST", "/api/relay/parcels/$parcelB/pickup-code", b.second, ownCodeB).statusCode())
        val qrRelease = release + mapOf("relayPointId" to siteB, "rawNumericCode" to null,
            "rawQrNonce" to "isolated-qr-nonce-for-relay", "eventId" to UUID.randomUUID().toString(), "idempotencyKey" to UUID.randomUUID().toString())
        assertEquals(403, call("POST", "/api/relay/parcels/$parcelB/release", a.second, qrRelease).statusCode())
        assertEquals(400, call("POST", "/api/relay/parcels/$parcelB/release", b.second,
            qrRelease + ("eventId" to release["eventId"])).statusCode())
        assertEquals(200, call("POST", "/api/relay/parcels/$parcelB/release", b.second, qrRelease).statusCode())
        assertEquals("PickedUp", json.readTree(call("GET", "/api/relay/parcels/$parcelB", b.second).body())["status"].asText())
        val (problemParcel, _) = deposit(siteB, b.second)
        val problem = call("POST", "/api/relay/parcels/$problemParcel/problem", b.second,
            mapOf("eventId" to UUID.randomUUID().toString(), "idempotencyKey" to UUID.randomUUID().toString(), "metadata" to "Damaged packaging"))
        assertEquals(200, problem.statusCode(), problem.body())
        assertEquals(2, json.readTree(call("GET", "/api/relay/parcels/$problemParcel", b.second).body())["history"].size())
        // Legacy rows without an explicit assignment are never claimed by a matching subject string.
        val legacy = token(RoleCode.RELAY_PARTNER)
        points.save(RelayPointRecord(id = legacy.first, name = "Unassigned legacy"))
        assertEquals(403, call("GET", "/api/hub/summary?hubId=${legacy.first}", legacy.second).statusCode())
        assertEquals(403, call("GET", "/api/relay/parcels?relayPointId=${legacy.first}", legacy.second).statusCode())
        points.findById(siteB).orElseThrow().also { it.status = "INACTIVE"; points.save(it) }
        assertEquals(403, call("GET", "/api/relay/parcels/$parcelB", b.second).statusCode())
        assertEquals("[]", call("GET", "/api/relay/parcels/sites", b.second).body())
    }
}
