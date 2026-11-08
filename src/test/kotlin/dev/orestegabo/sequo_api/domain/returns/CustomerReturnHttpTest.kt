package dev.orestegabo.sequo_api.domain.returns

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.hub.RelayLockerRecord
import dev.orestegabo.sequo_api.domain.hub.RelayLockerRepository
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.relay.RelayPointRepository
import dev.orestegabo.sequo_api.domain.relay.RelayParcelCategory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:customer_return_http;DB_CLOSE_DELAY=-1",
        // LOCAL_FIXTURE_ONLY: explicit exclusion tests configuration, not a production food-return ban.
        "sequo.returns.excluded-categories=Food",
    ],
)
class CustomerReturnHttpTest @Autowired constructor(
    private val context: ApplicationContext,
    private val returns: ReturnRequestRecordRepository,
    private val orders: CustomerOrderRecordRepository,
    private val rateLimiter: InMemoryRateLimiter,
) {
    companion object {
        @JvmStatic @org.springframework.test.context.DynamicPropertySource
        fun isolatedPostgres(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("SEQUO_RETURN_JOURNEY_TEST_URL") ?: return
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) {
                "Use a disposable local return fixture database."
            }
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "sequo_fixture" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.dialect.PostgreSQLDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    @MockitoBean(name = "returnClock") private lateinit var returnClock: Clock
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val deliveredAt = Instant.parse("2026-09-01T10:00:00Z")

    @org.junit.jupiter.api.BeforeEach
    fun resetFixtures() {
        rateLimiter.resetForTests()
        setTime(deliveredAt.plusSeconds(3600))
        // Only this isolated H2/guarded local PostgreSQL database is used by the class.
        context.getBean(RelayPointRepository::class.java).findAll().forEach {
            it.status = "INACTIVE"; context.getBean(RelayPointRepository::class.java).save(it)
        }
    }

    @Test
    fun customerCreatesOneReturnWithServerOwnedValuesAndScopedReadback() {
        val relayId = ReturnJourneyFixtures.relay(context)
        val fixture = ReturnJourneyFixtures.order(context, deliveredAt)
        val stranger = ReturnJourneyFixtures.order(context, deliveredAt)
        val eligibility = read("/api/returns/orders/${fixture.orderId}/eligibility", fixture.owner.token)
        assertTrue(eligibility["eligible"].asBoolean())
        assertTrue(eligibility["relayPoints"].any { it["id"].asText() == relayId })
        assertEquals(200, request("GET", "/api/auth/me", fixture.owner.token).statusCode())
        val returnId = "return-${UUID.randomUUID()}"
        val body = ReturnJourneyFixtures.body(returnId, fixture.orderId, extras = mapOf(
            "customerId" to fixture.owner.id, "requestedAt" to "1900-01-01T00:00:00Z",
            "requestedRefundCfa" to Int.MAX_VALUE, "rawReturnPin" to "client-value-is-not-a-pin",
            "productReturnable" to false, "merchantAllowsReturn" to false, "adminOverride" to true,
        ))
        val responses = (1..2).map { CompletableFuture.supplyAsync { request("POST", "/api/returns", fixture.owner.token, body) } }.map { it.join() }
        responses.forEach { assertEquals(200, it.statusCode(), it.body()) }
        val created = json.readTree(responses.first().body())
        assertEquals(returnId, created["id"].asText())
        assertEquals(fixture.orderId, created["orderId"].asText())
        assertEquals("AwaitingRelayDropoff", created["status"].asText())
        assertEquals(3000, created["requestedRefundCfa"].asInt())
        assertEquals(deliveredAt.plusSeconds(3600), Instant.parse(created["requestedAt"].asText()))
        val pin = created["returnPin"].asText()
        assertTrue(pin.matches(Regex("[0-9]{6}")))
        assertTrue(Sha256ReturnPinHasher().matches(pin, returns.findById(returnId).orElseThrow().returnPinHash))
        assertFalse(created.has("returnPinHash"))
        assertEquals(1, returns.findByOrderIdOrderByCreatedAtDesc(fixture.orderId).size)
        assertEquals(CustomerOrderStatus.RETURN_REQUESTED, orders.findById(fixture.orderId).orElseThrow().orderStatus)
        assertEquals(returnId, read("/api/returns/${returnId}", fixture.owner.token)["id"].asText())
        assertEquals(returnId, read("/api/returns/orders/${fixture.orderId}", fixture.owner.token).single()["id"].asText())
        assertEquals(returnId, read("/api/returns", fixture.owner.token).single()["id"].asText())
        val retried = parse(request("POST", "/api/returns", fixture.owner.token,
            ReturnJourneyFixtures.body("new-id-${UUID.randomUUID()}", fixture.orderId)))
        assertEquals(returnId, retried["id"].asText())
        assertEquals(pin, retried["returnPin"].asText())
        assertEquals(400, request("POST", "/api/returns", fixture.owner.token,
            ReturnJourneyFixtures.body(returnId, fixture.orderId, "A changed reason.")).statusCode())
        assertDenied(request("GET", "/api/returns/$returnId", stranger.owner.token))
        assertDenied(request("GET", "/api/returns/orders/${fixture.orderId}/eligibility", stranger.owner.token))
        assertDenied(request("GET", "/api/returns/orders/${fixture.orderId}", stranger.owner.token))
        assertTrue(read("/api/returns?customerId=${fixture.owner.id}", stranger.owner.token).isEmpty)
        val collision = request("POST", "/api/returns", stranger.owner.token, ReturnJourneyFixtures.body(returnId, stranger.orderId))
        assertDenied(collision)
        assertFalse(collision.body().contains(pin))
        assertDenied(request("POST", "/api/returns", stranger.owner.token,
            ReturnJourneyFixtures.body("forged-${UUID.randomUUID()}", fixture.orderId, extras = mapOf("customerId" to fixture.owner.id))))
        assertEquals(1, returns.findByOrderIdOrderByCreatedAtDesc(fixture.orderId).size)
        assertTrue(returns.findByOrderIdOrderByCreatedAtDesc(stranger.orderId).isEmpty())
    }

    @Test
    fun serverClockEnforcesBeforeAtAndAfterSeventyTwoHoursAndNewIdsCannotDuplicate() {
        val before = ReturnJourneyFixtures.order(context, deliveredAt)
        val at = ReturnJourneyFixtures.order(context, deliveredAt)
        val after = ReturnJourneyFixtures.order(context, deliveredAt)
        val deadline = deliveredAt.plus(Duration.ofHours(72))
        setTime(deadline.minusNanos(1))
        val manual = parse(request("POST", "/api/returns", before.owner.token,
            ReturnJourneyFixtures.body("before-${UUID.randomUUID()}", before.orderId,
                extras = mapOf("requestedAt" to "2099-01-01T00:00:00Z"))))
        assertEquals("Requested", manual["status"].asText())
        assertTrue(manual["instructions"].asText().isNotBlank())
        assertTrue(manual["relayPoints"].isEmpty)
        setTime(deadline)
        val attempts = (1..2).map { index -> CompletableFuture.supplyAsync {
            request("POST", "/api/returns", at.owner.token, ReturnJourneyFixtures.body("at-$index-${UUID.randomUUID()}", at.orderId))
        } }.map { it.join() }
        attempts.forEach { assertEquals(200, it.statusCode(), it.body()) }
        assertEquals(1, attempts.map { json.readTree(it.body())["id"].asText() }.toSet().size)
        assertEquals(1, returns.findByOrderIdOrderByCreatedAtDesc(at.orderId).size)
        setTime(deadline.plusNanos(1))
        val rejected = request("POST", "/api/returns", after.owner.token,
            ReturnJourneyFixtures.body("after-${UUID.randomUUID()}", after.orderId,
                extras = mapOf("requestedAt" to deliveredAt.plusSeconds(1).toString(), "adminOverride" to true)))
        assertEquals(400, rejected.statusCode(), rejected.body())
        assertEquals("return_window_expired", json.readTree(rejected.body())["code"].asText())
        assertTrue(returns.findByOrderIdOrderByCreatedAtDesc(after.orderId).isEmpty())
        val eligibility = read("/api/returns/orders/${after.orderId}/eligibility", after.owner.token)
        assertFalse(eligibility["eligible"].asBoolean())
        assertEquals("return_window_expired", eligibility["code"].asText())
    }

    @Test
    fun explicitCategoryPolicyAndUndeliveredOrdersCannotBeOverriddenByCustomerFlags() {
        val food = ReturnJourneyFixtures.order(context, deliveredAt, OrderProductCategory.Food)
        val notDelivered = ReturnJourneyFixtures.order(context, deliveredAt, delivered = false)
        val sealedGrocery = ReturnJourneyFixtures.order(context, deliveredAt, OrderProductCategory.GenericSealedItem,
            relayCategory = RelayParcelCategory.Food)
        val purchasedProduct = context.getBean(CustomerOrderLineRecordRepository::class.java)
            .findByOrderIdOrderByLineIndexAsc(sealedGrocery.orderId).single().productId
        context.getBean(CommerceProductRepository::class.java).findById(purchasedProduct).orElseThrow().also {
            it.category = "general"; context.getBean(CommerceProductRepository::class.java).save(it)
        }
        for ((fixture, expectedCode) in listOf(food to "item_not_returnable", notDelivered to "order_not_delivered", sealedGrocery to "item_not_returnable")) {
            val result = request("POST", "/api/returns", fixture.owner.token,
                ReturnJourneyFixtures.body("ineligible-${UUID.randomUUID()}", fixture.orderId,
                    extras = mapOf("productReturnable" to true, "merchantAllowsReturn" to true, "adminOverride" to true)))
            assertEquals(400, result.statusCode(), result.body())
            assertEquals(expectedCode, json.readTree(result.body())["code"].asText())
            assertTrue(returns.findByOrderIdOrderByCreatedAtDesc(fixture.orderId).isEmpty())
        }
        ReturnJourneyFixtures.relay(context)
        val manual = ReturnJourneyFixtures.order(context, deliveredAt, OrderProductCategory.Sensitive)
        val created = parse(request("POST", "/api/returns", manual.owner.token,
            ReturnJourneyFixtures.body("manual-${UUID.randomUUID()}", manual.orderId)))
        assertEquals("Requested", created["status"].asText())
        assertTrue(created["instructions"].asText().isNotBlank())
        assertTrue(created["relayPoints"].isEmpty)
        assertTrue(created.path("returnPin").isNull || created.path("returnPin").isMissingNode)
    }

    @Test
    fun clickAndCollectPendingRecordsTheRequestWithoutInventingAnExtraCategoryBan() {
        ReturnJourneyFixtures.relay(context)
        val fixture = ReturnJourneyFixtures.order(context, deliveredAt, OrderProductCategory.Food, route = OrderRoute.Pickup)
        val eligibility = read("/api/returns/orders/${fixture.orderId}/eligibility", fixture.owner.token)
        assertTrue(eligibility["eligible"].asBoolean())
        assertEquals("CUSTOMER_MANAGED_PENDING", eligibility["dropoffMethod"].asText())
        val created = parse(request("POST", "/api/returns", fixture.owner.token,
            ReturnJourneyFixtures.body("pickup-return-${UUID.randomUUID()}", fixture.orderId)))
        assertEquals("Requested", created["status"].asText())
        assertEquals("CUSTOMER_MANAGED_PENDING", created["dropoffMethod"].asText())
        assertTrue(created["instructions"].asText().isNotBlank())
        assertTrue(created["relayPoints"].isEmpty)
        assertTrue(created.path("returnPin").isNull || created.path("returnPin").isMissingNode)
        assertEquals(1, returns.findByOrderIdOrderByCreatedAtDesc(fixture.orderId).size)
    }

    @Test
    fun relayIntakeUsesTheAuthenticatedSiteAndHidesThePinAfterDeposit() {
        val first = ReturnJourneyFixtures.relayWithOperator(context)
        val second = ReturnJourneyFixtures.relayWithOperator(context)
        val fixture = ReturnJourneyFixtures.order(context, deliveredAt)
        val created = parse(request("POST", "/api/returns", fixture.owner.token,
            ReturnJourneyFixtures.body("relay-return-${UUID.randomUUID()}", fixture.orderId)))
        val id = created["id"].asText()
        val pin = created["returnPin"].asText()
        val endpoint = "/api/returns/$id/relay-dropoff"
        assertEquals(403, request("GET", "/api/returns/$id", first.operator.token).statusCode())
        assertEquals(403, request("GET", "/api/returns/$id", second.operator.token).statusCode())
        fun intakeBody(point: String, rawPin: String = pin) = json.writeValueAsString(mapOf(
            "relayPointId" to point, "rawReturnPin" to rawPin, "droppedAt" to "1900-01-01T00:00:00Z"))
        assertEquals(403, request("POST", endpoint, first.operator.token, intakeBody(second.id)).statusCode())
        assertEquals(400, request("POST", endpoint, first.operator.token, intakeBody(first.id, "wrong-pin")).statusCode())
        assertEquals(ReturnStatus.AwaitingRelayDropoff, returns.findById(id).orElseThrow().status)
        val received = parse(request("POST", endpoint, first.operator.token, intakeBody(first.id)))
        assertEquals("DroppedAtRelay", received["status"].asText())
        assertEquals(first.id, received["relayPointId"].asText())
        assertEquals(deliveredAt.plusSeconds(3600), Instant.parse(received["droppedAt"].asText()))
        setTime(deliveredAt.plusSeconds(3660))
        val repeated = parse(request("POST", endpoint, first.operator.token, intakeBody(first.id)))
        assertEquals(received["id"].asText(), repeated["id"].asText())
        assertEquals(received["droppedAt"].asText(), repeated["droppedAt"].asText())
        assertEquals(200, request("GET", "/api/returns/$id", first.operator.token).statusCode())
        assertEquals(403, request("GET", "/api/returns/$id", second.operator.token).statusCode())
        val customerRead = read("/api/returns/$id", fixture.owner.token)
        assertEquals("DroppedAtRelay", customerRead["status"].asText())
        assertTrue(customerRead.path("returnPin").isNull || customerRead.path("returnPin").isMissingNode)
        assertFalse(customerRead.has("returnPinHash"))
    }

    @Test
    fun concurrentReturnIdCollisionCannotOverwriteAnotherCustomersReturn() {
        ReturnJourneyFixtures.relay(context)
        val fixtures = listOf(ReturnJourneyFixtures.order(context, deliveredAt), ReturnJourneyFixtures.order(context, deliveredAt))
        val reasons = listOf("First customer's delivered package is wrong.", "Second customer's delivered package is wrong.")
        val returnId = "colliding-return-${UUID.randomUUID()}"
        val responses = fixtures.mapIndexed { index, fixture -> CompletableFuture.supplyAsync {
            request("POST", "/api/returns", fixture.owner.token, ReturnJourneyFixtures.body(returnId, fixture.orderId, reasons[index]))
        } }.map { it.join() }
        val outcomes = responses.map { it.statusCode() to it.body() }.toString()
        assertEquals(1, responses.count { it.statusCode() == 200 }, outcomes)
        assertEquals(1, responses.count { it.statusCode() in setOf(400, 409) }, outcomes)
        val winnerIndex = responses.indices.single { responses[it].statusCode() == 200 }
        val loserIndex = 1 - winnerIndex
        val winner = fixtures[winnerIndex]
        val loser = fixtures[loserIndex]
        val created = json.readTree(responses[winnerIndex].body())
        val pin = created["returnPin"].asText()
        assertTrue(pin.matches(Regex("[0-9]{6}")))
        assertFalse(responses[loserIndex].body().contains(pin))
        val stored = returns.findById(returnId).orElseThrow()
        assertEquals(winner.orderId, stored.orderId)
        assertEquals(winner.owner.id, stored.customerId)
        assertEquals(reasons[winnerIndex], stored.reason)
        assertEquals(1, returns.findByOrderIdOrderByCreatedAtDesc(winner.orderId).size)
        assertTrue(returns.findByOrderIdOrderByCreatedAtDesc(loser.orderId).isEmpty())
        assertEquals(CustomerOrderStatus.RETURN_REQUESTED, orders.findById(winner.orderId).orElseThrow().orderStatus)
        assertEquals(CustomerOrderStatus.DELIVERED, orders.findById(loser.orderId).orElseThrow().orderStatus)
        assertDenied(request("GET", "/api/returns/$returnId", loser.owner.token))
        val restored = read("/api/returns/$returnId", winner.owner.token)
        assertEquals(winner.orderId, restored["orderId"].asText())
        assertEquals(reasons[winnerIndex], restored["reason"].asText())
        assertEquals(pin, restored["returnPin"].asText())
    }

    private fun setTime(at: Instant) {
        Mockito.`when`(returnClock.instant()).thenReturn(at)
        Mockito.`when`(returnClock.zone).thenReturn(ZoneOffset.UTC)
    }
    private fun assertDenied(response: HttpResponse<String>) = assertTrue(response.statusCode() in 400..499, response.body())
    private fun read(path: String, token: String) = parse(request("GET", path, token))
    private fun parse(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }
    private fun request(method: String, path: String, token: String, body: String? = null) =
        ReturnJourneyFixtures.request(port, method, path, token, body)
}

internal data class ReturnJourneyActor(val id: String, val token: String)
internal data class ReturnJourneyOrder(val owner: ReturnJourneyActor, val orderId: String)
internal data class ReturnJourneyRelay(val id: String, val operator: ReturnJourneyActor)

internal object ReturnJourneyFixtures {
    private val json = ObjectMapper()
    private val client = HttpClient.newHttpClient()

    fun order(context: ApplicationContext, deliveredAt: Instant, category: OrderProductCategory = OrderProductCategory.GeneralGoods,
        delivered: Boolean = true, route: OrderRoute = OrderRoute.FastDelivery, relayCategory: RelayParcelCategory? = null): ReturnJourneyOrder {
        val users = context.getBean(UserRepository::class.java)
        val user = users.save(User(email = "return-${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL))
        val customerId = requireNotNull(user.id)
        val merchantId = "return-merchant-${UUID.randomUUID()}"
        context.getBean(CommerceMerchantRepository::class.java).save(MerchantRecord(id = merchantId, name = "Fixture return merchant"))
        val productId = "return-product-${UUID.randomUUID()}"
        context.getBean(CommerceProductRepository::class.java).save(ProductRecord(id = productId, merchantId = merchantId,
            name = "Fixture return product", kind = CatalogProductKind.GenericSealedItem, basePriceCfa = 3000,
            category = if (category == OrderProductCategory.GenericSealedItem && relayCategory == RelayParcelCategory.Food) "grocery" else category.name.lowercase(),
            imageUrl = "https://fixture.invalid/approved.jpg"))
        val checkoutId = "return-checkout-${UUID.randomUUID()}"
        val request = OrderProcessingRequest(checkoutId, customerId, OrderServiceLevel.Regular, route,
            listOf(OrderLineRequest(productId, merchantId, "Fixture return merchant", "Fixture return product", category,
                1, 3000, photoEvidence = ProductPhotoEvidence.LiveCameraCapture(deliveredAt.toEpochMilli()), relayCategory = relayCategory)),
            1.0, paymentProvider = SequoPaymentProviders.YasTogo, paymentReference = checkoutId)
        val processor = RegularOrderProcessor(PaymentProcessor(listOf(YasTogoPaymentMethod(validateHandler = {
            PaymentValidationResult.validated(it.paymentReference)
        }))), DeliveryPricingService())
        val accepted = processor.process(request) as OrderProcessingResult.AcceptedForFulfillment
        context.getBean(PendingPaymentCheckoutService::class.java).recordCartCheckout(request, accepted.order.pricing,
            PendingPaymentCheckoutStatus.COMPLETED, deliveredAt.minusSeconds(3600))
        val fulfillment = context.getBean(OrderFulfillmentPersistenceService::class.java).persistAcceptedOrder(request, accepted, deliveredAt.minusSeconds(3600))
        val orders = context.getBean(CustomerOrderRecordRepository::class.java)
        orders.findById(fulfillment.order.id).orElseThrow().also {
            if (delivered) {
                it.orderStatus = CustomerOrderStatus.DELIVERED; it.deliveredAt = deliveredAt
                it.returnWindowEndsAt = deliveredAt.plus(Duration.ofHours(72)); it.updatedAt = deliveredAt
                orders.save(it)
            }
        }
        return ReturnJourneyOrder(ReturnJourneyActor(customerId, token(context, customerId)), fulfillment.order.id)
    }

    fun token(context: ApplicationContext, customerId: String): String {
        val user = context.getBean(UserRepository::class.java).findById(customerId).orElseThrow()
        return context.getBean(JwtService::class.java).generateAccessToken(UserSession(customerId, user.email, user.provider, user.roles))
    }

    fun relay(context: ApplicationContext): String = relayWithOperator(context).id

    fun relayWithOperator(context: ApplicationContext): ReturnJourneyRelay {
        val user = context.getBean(UserRepository::class.java).save(User(email = "return-operator-${UUID.randomUUID()}@fixture.invalid",
            provider = AuthProvider.EMAIL, roles = mutableSetOf(RoleCode.RELAY_PARTNER)))
        val id = "return-relay-${UUID.randomUUID()}"
        context.getBean(RelayPointRepository::class.java).save(RelayPointRecord(id, user.id, name = "Fixture return relay",
            latitude = 0.0, longitude = 0.02, city = "Lomé", neighborhood = "Fixture receiving area"))
        val time = java.sql.Timestamp.from(Instant.now())
        context.getBean(JdbcTemplate::class.java).update("insert into relay_locker_grids (id,relay_point_id,grid_code,label,created_at,updated_at,version) values (?,?,?,?,?,?,0)",
            id, id, id, "Staging logical return grid", time, time)
        context.getBean(RelayLockerRepository::class.java).save(RelayLockerRecord("slot-$id", id, relayLockerGridId = id, lockerCode = "SLOT-1"))
        val operatorId = requireNotNull(user.id)
        return ReturnJourneyRelay(id, ReturnJourneyActor(operatorId, token(context, operatorId)))
    }

    fun body(returnId: String, orderId: String, reason: String = "Wrong size delivered.", extras: Map<String, Any?> = emptyMap()): String =
        json.writeValueAsString(mapOf("returnId" to returnId, "orderId" to orderId, "reason" to reason) + extras)

    fun request(port: Int, method: String, path: String, token: String, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("Authorization", "Bearer $token")
        body?.let { request.header("Content-Type", "application/json") }
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
