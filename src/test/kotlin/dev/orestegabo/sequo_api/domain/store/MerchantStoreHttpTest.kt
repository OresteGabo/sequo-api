package dev.orestegabo.sequo_api.domain.store

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.*
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRepository
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.platform.*
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:merchant_store_http;DB_CLOSE_DELAY=-1",
    "sequo.cart.straight-line-dispatch-origin=0,0",
])
class MerchantStoreHttpTest @Autowired constructor(
    private val users: UserRepository, private val jwt: JwtService, private val memberships: MerchantMembershipRepository,
    private val merchants: CommerceMerchantRepository, private val stores: MerchantStoreRepository,
    private val changes: MerchantStoreChangeRepository, private val products: CommerceProductRepository,
    private val addresses: CustomerAddressRepository, private val cart: CustomerCartItemRepository,
    private val pending: PendingPaymentCheckoutRepository, private val orders: CustomerOrderRecordRepository,
) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private lateinit var owner: User
    private lateinit var other: User
    private lateinit var admin: User
    private lateinit var customer: User

    @BeforeEach fun fixture() {
        changes.deleteAll(); stores.deleteAll(); cart.deleteAll(); addresses.deleteAll(); products.deleteAll()
        memberships.deleteAll(); merchants.deleteAll(); users.deleteAll()
        fun user(name: String, role: RoleCode) = users.save(User(email = "$name@fixture.invalid", name = name, roles = mutableSetOf(role)))
        owner = user("store-owner", RoleCode.MERCHANT_OWNER); other = user("other-owner", RoleCode.MERCHANT_OWNER)
        admin = user("store-admin", RoleCode.ADMIN); customer = user("store-customer", RoleCode.CUSTOMER)
        merchants.save(MerchantRecord("store-a", ownerUserId = owner.id, name = "Fixture store A"))
        merchants.save(MerchantRecord("store-b", ownerUserId = other.id, name = "Fixture store B"))
        memberships.save(MerchantMembership(userId = requireNotNull(owner.id), merchantId = "store-a", role = RoleCode.MERCHANT_OWNER))
        memberships.save(MerchantMembership(userId = requireNotNull(other.id), merchantId = "store-b", role = RoleCode.MERCHANT_OWNER))
    }

    @Test fun scopedReadsPreserveUnconfiguredLegacyAndDenyGuessedStoresAndRoles() {
        val empty = tree(call("GET", own, owner))
        assertFalse(empty["configured"].asBoolean()); assertTrue(empty["canAcceptOrders"].asBoolean())
        assertFalse(empty["open"].asBoolean()); assertTrue(empty["timezone"].isNull)
        assertTrue(empty["openingHours"].isEmpty); assertEquals("NOT_CONFIGURED", empty["openStatus"].asText())
        assertEquals(403, call("GET", own, other).statusCode())
        assertEquals(403, call("GET", "/api/merchant/store?merchantId=store-b", owner).statusCode())
        assertEquals(403, call("GET", staff, customer).statusCode())
        assertTrue(call("GET", staff, null).statusCode() in setOf(401,403))
        assertEquals(200, call("GET", staff, admin).statusCode())
    }

    @Test fun merchantAndAdminSharePersistedSettingsWithReplayStaleAndActorHistory() {
        val revision = tree(call("GET", own, owner))["updatedAt"].asText()
        val close = body(revision, "store-close-retry", false)
        val saved = tree(call("PUT", own, owner, close))
        assertFalse(saved["record"]["canAcceptOrders"].asBoolean())
        val after = tree(call("GET", staff, admin))
        assertEquals(owner.id, after["history"][0]["actorUserId"].asText())
        assertEquals(saved["record"]["updatedAt"], after["updatedAt"])
        assertEquals(200, call("PUT", own, owner, close).statusCode())
        assertEquals(1, changes.count())
        assertEquals(409, call("PUT", own, owner, close.replace("Store policy review", "Changed intent")).statusCode())
        assertEquals(409, call("PUT", own, owner, body(revision, "store-stale-retry", true)).statusCode())
        val opened = tree(call("PUT", staff, admin, body(after["updatedAt"].asText(), "store-reopen-retry", true)))
        assertTrue(opened["record"]["canAcceptOrders"].asBoolean())
        assertEquals(admin.id, opened["record"]["history"][0]["actorUserId"].asText())
        assertEquals(2, changes.count())
    }

    @Test fun malformedHoursExceptionsAndIntervalsDoNotPartiallyConfigureStore() {
        val revision = tree(call("GET", own, owner))["updatedAt"].asText()
        val base = body(revision, "invalid-store-change", true)
        val overnight = (1..7).joinToString(",") { """{"dayOfWeek":$it,"opensAt":"22:00","closesAt":"06:00","closed":false}""" }
        val cases = listOf(
            base.replace("\"openingHours\":[]", "\"openingHours\":[$overnight]"),
            base.replace("\"temporaryExceptions\":[]", """"temporaryExceptions":[{"date":"2026-02-31","closed":true}]"""),
            base.replace("\"temporaryClosure\":null", """"temporaryClosure":{"startsAt":"2026-11-08T12:00:00Z","endsAt":"2026-11-08T10:00:00Z"}"""),
        )
        cases.forEach { assertEquals(400, call("PUT", own, owner, it).statusCode()) }
        assertEquals(0, stores.count()); assertEquals(0, changes.count())
    }

    @Test fun storeCloseAndAcceptingFlagBlockNewQuoteAndStaleCheckoutWithoutHidingCatalog() {
        products.save(ProductRecord(id = "store-product", merchantId = "store-a", kind = CatalogProductKind.GenericSealedItem,
            category = "electronics", name = "Fixture product", imageUrl = "https://fixture.invalid/product.jpg", basePriceCfa = 2000))
        addresses.save(CustomerAddressRecord("store-address", requireNotNull(customer.id), CustomerAddressKind.HOME, "Home",
            "Fixture recipient", "+22800000000", "Lome", latitude = 0.0, longitude = .04))
        assertEquals(200, call("PUT", "/api/cart/items", customer, """{"productId":"store-product","quantity":1}""").statusCode())
        val oldQuote = tree(call("GET", "/api/cart/quote?addressId=store-address", customer))
        val revision = tree(call("GET", staff, admin))["updatedAt"].asText()
        tree(call("PUT", staff, admin, body(revision, "close-before-checkout", false)))
        assertEquals(200, call("GET", "/api/catalog/products", null).statusCode())
        assertEquals(1, tree(call("GET", "/api/cart", customer))["totalItems"].asInt())
        assertEquals(400, call("GET", "/api/cart/quote?addressId=store-address", customer).statusCode())
        val checkout = """{"addressId":"store-address","quoteRevision":"${oldQuote["revision"].asText()}","paymentProvider":"yas_togo"}"""
        assertEquals(409, call("POST", "/api/orders/checkouts", customer, checkout).statusCode())
        assertEquals(0, pending.count()); assertEquals(0, orders.count())
        val closed = tree(call("GET", staff, admin))
        tree(call("PUT", staff, admin, body(closed["updatedAt"].asText(), "accepting-disabled", true, false)))
        assertEquals(400, call("GET", "/api/cart/quote?addressId=store-address", customer).statusCode())
        val paused = tree(call("GET", staff, admin))
        tree(call("PUT", staff, admin, body(paused["updatedAt"].asText(), "reopen-for-new-quote", true)))
        assertEquals(200, call("GET", "/api/cart/quote?addressId=store-address", customer).statusCode())
        assertEquals(409, call("POST", "/api/orders/checkouts", customer, checkout).statusCode())
        assertEquals(0, pending.count())
    }

    @Test fun concurrentExpectedRevisionWritesHaveOneWinner() {
        val revision = tree(call("GET", staff, admin))["updatedAt"].asText()
        val results = listOf(false, true).map { open -> CompletableFuture.supplyAsync { call("PUT", staff, admin, body(revision, "concurrent-store-$open", open)).statusCode() } }.map { it.join() }
        assertEquals(listOf(200,409), results.sorted()); assertEquals(1, changes.count())
    }

    private val own = "/api/merchant/store?merchantId=store-a"
    private val staff = "/api/admin/web/merchants/store-a/store"
    private fun body(revision: String, key: String, open: Boolean, accepting: Boolean = open) = """{"open":$open,"acceptingOrders":$accepting,"timezone":"Africa/Lome","openingHours":[],"temporaryExceptions":[],"temporaryClosure":null,"closureReason":null,"reason":"Store policy review","expectedUpdatedAt":"$revision","idempotencyKey":"$key"}"""
    private fun call(method: String, path: String, user: User?, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (user != null) request.header("Authorization", "Bearer " + jwt.generateAccessToken(UserSession(requireNotNull(user.id), user.email, user.provider, user.roles,
            merchantScopeIds = if (user.id == owner.id) setOf("store-a") else if (user.id == other.id) setOf("store-b") else emptySet())))
        if (body != null) request.header("Content-Type", "application/json")
        return client.send(request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun tree(response: HttpResponse<String>): JsonNode { assertEquals(200, response.statusCode(), response.body()); return json.readTree(response.body()) }
}
