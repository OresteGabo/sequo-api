package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.*
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:admin_negotiation_reads;DB_CLOSE_DELAY=-1",
])
class AdminNegotiationReadsHttpTest @Autowired constructor(
    private val users: UserRepository, private val jwt: JwtService,
    private val merchants: CommerceMerchantRepository, private val products: CommerceProductRepository,
    private val threads: BargainingThreadRepository, private val offers: BargainingOfferRepository,
) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private lateinit var admin: User
    private lateinit var customer: User
    private lateinit var merchant: User

    @BeforeEach
    fun fixture() {
        offers.deleteAll(); threads.deleteAll(); products.deleteAll(); merchants.deleteAll(); users.deleteAll()
        admin = users.save(User(email = "negotiation-admin@fixture.invalid", roles = mutableSetOf(RoleCode.ADMIN)))
        customer = users.save(User(email = "negotiation-customer@fixture.invalid", roles = mutableSetOf(RoleCode.CUSTOMER)))
        merchant = users.save(User(email = "negotiation-owner@fixture.invalid", roles = mutableSetOf(RoleCode.MERCHANT_OWNER)))
        merchants.save(MerchantRecord("oversight-merchant", name = "Merchant fixture"))
        products.save(ProductRecord("oversight-product", merchantId = "oversight-merchant", name = "Recorded product",
            basePriceCfa = 3000, bargainingEnabled = true, bargainFloorCfa = 1234))
        val before = Instant.now().minusSeconds(3600)
        threads.save(BargainingThreadRecord("oversight-thread", customer.id!!, "oversight-merchant", productId = "oversight-product",
            status = BargainingThreadStatus.ACCEPTED, openedAt = before.minusSeconds(60), closedAt = before,
            expiresAt = before.plusSeconds(1), lastOfferCfa = 2200, acceptedPriceCfa = 2200))
        offers.saveAll(listOf(
            BargainingOfferRecord("offer-customer", "oversight-thread", actorUserId = customer.id!!, actorType = BargainingActorType.CUSTOMER,
                offerType = BargainingOfferType.OFFER, amountCfa = 2000, message = "private-contact-message", idempotencyKey = "private-retry-key", requestFingerprint = "private-fingerprint", createdAt = before.minusSeconds(30)),
            BargainingOfferRecord("offer-merchant", "oversight-thread", actorUserId = merchant.id!!, actorType = BargainingActorType.MERCHANT,
                offerType = BargainingOfferType.COUNTER_OFFER, amountCfa = 2200, message = "private-merchant-message", createdAt = before),
        ))
    }

    @Test
    fun oversightUsesServerExpiryAttemptsAndRecordedOfferHistoryWithPrivateFieldsRemoved() {
        val response = request("GET", "/api/admin/web/bargaining-threads/oversight-thread")
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow())
        val body = json.readTree(response.body())
        assertEquals("EXPIRED", body["status"].asText())
        assertEquals(2200, body["acceptedPriceCfa"].asInt())
        assertEquals(1, body["customerAttemptsUsed"].asInt())
        assertEquals(1, body["merchantAttemptsUsed"].asInt())
        assertEquals(3, body["maxCustomerAttempts"].asInt())
        assertEquals(2, body["history"].size())
        assertEquals(customer.id, body["history"][0]["actorUserId"].asText())
        listOf("private-contact", "private-merchant", "private-retry", "private-fingerprint", "bargainFloor", "message").forEach {
            assertFalse(response.body().contains(it), it)
        }
        assertEquals(1, json.readTree(request("GET", "/api/admin/web/bargaining-threads?merchantId=oversight-merchant&limit=1").body()).size())
        assertEquals(0, json.readTree(request("GET", "/api/admin/web/bargaining-threads?merchantId=other-merchant").body()).size())
        assertEquals(BargainingThreadStatus.ACCEPTED, threads.findById("oversight-thread").orElseThrow().status)
    }

    @Test
    fun adminReadDoesNotGrantPartyActionsAndAllOtherRolesOrMissingSessionsFailClosed() {
        val paths = listOf("/api/admin/web/bargaining-threads", "/api/admin/web/bargaining-threads/oversight-thread")
        for (path in paths) {
            assertTrue(request("GET", path, bearer = null).statusCode() in setOf(401, 403))
            for (user in listOf(customer, merchant)) assertEquals(403, request("GET", path, bearer = token(user)).statusCode())
        }
        assertEquals(404, request("GET", "/api/admin/web/bargaining-threads/guessed-thread").statusCode())
        assertEquals(403, request("GET", "/api/bargaining/threads/oversight-thread").statusCode())
        val attempt = request("POST", "/api/bargaining/threads/oversight-thread/offers", """{"offerType":"ACCEPT","expectedOfferId":"offer-customer","idempotencyKey":"not-a-party"}""")
        assertEquals(403, attempt.statusCode(), attempt.body())
        assertEquals(2L, offers.count())
    }

    private fun token(user: User = admin) = jwt.generateAccessToken(UserSession(user.id!!, null, AuthProvider.EMAIL, user.roles))
    private fun request(method: String, path: String, body: String? = null, bearer: String? = token()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        body?.let { builder.header("Content-Type", "application/json") }
        return client.send(builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
}
