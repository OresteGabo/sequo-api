package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.party.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.relay.*
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["spring.datasource.url=jdbc:h2:mem:owner_directory;DB_CLOSE_DELAY=-1"])
class OwnerDirectoryHttpTest @Autowired constructor(
    private val users: UserRepository, private val jwt: JwtService, private val auth: AuthService,
    private val operations: OwnerDirectoryRepository, private val access: AdminAccessChangeRepository,
    private val couriers: SettlementCourierRecordRepository, private val merchants: CommerceMerchantRepository,
    private val points: RelayPointRepository, private val missions: DeliveryMissionRepository,
    private val parcels: RelayParcelRecordRepository, private val subOrders: MerchantSubOrderRepository,
    private val devices: UserDeviceRepository, private val pending: PendingPaymentCheckoutRepository,
) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private fun user(vararg roles: RoleCode) = users.saveAndFlush(User(email = "${UUID.randomUUID()}@fixture.invalid", name = "Fixture person", roles = roles.toMutableSet()))
    private fun token(user: User) = jwt.generateAccessToken(auth.accountSession(user))
    private fun call(path: String, user: User, method: String = "GET", body: Any? = null, override: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/admin/web/directory/$path")).header("Authorization", "Bearer ${override ?: token(user)}")
        if (body != null) builder.header("Content-Type", "application/json")
        return client.send(builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun tree(response: HttpResponse<String>) = json.readTree(response.body())
    private fun createBody(role: String = "ADMIN", email: String = "${UUID.randomUUID()}@fixture.invalid", key: String = UUID.randomUUID().toString()) =
        mapOf("email" to email, "displayName" to "Approved personnel", "roleCode" to role, "reason" to "Owner reviewed the exact account and role", "idempotencyKey" to key)
    private fun guarded(path: String, owner: User, key: String = UUID.randomUUID().toString()) = mapOf("expectedUpdatedAt" to tree(call(path, owner))["record"]["updatedAt"].asText(), "reason" to "Reviewed personnel change", "idempotencyKey" to key)

    @Test fun onlyPersistedOwnerCanCreatePersonnelAndRetriesRemainAudited() {
        val owner = user(RoleCode.SUPER_ADMIN)
        for (role in listOf(RoleCode.ADMIN, RoleCode.STAFF_MANAGER, RoleCode.CUSTOMER)) {
            val actor = user(role)
            assertEquals(403, call("accounts", actor, "POST", createBody()).statusCode())
        }
        val ops = user(RoleCode.ADMIN)
        val forged = jwt.generateAccessToken(UserSession(ops.id!!, null, AuthProvider.EMAIL, setOf(RoleCode.SUPER_ADMIN)))
        assertEquals(403, call("accounts", ops, "POST", createBody(), forged).statusCode())
        for (role in listOf("ADMIN", "STAFF_MANAGER", "SUPER_ADMIN", "MERCHANT_OWNER", "MERCHANT_STAFF", "COURIER", "RELAY_PARTNER")) {
            val body = createBody(role)
            val result = call("accounts", owner, "POST", body)
            assertEquals(200, result.statusCode(), result.body())
            val id = tree(result)["record"]["id"].asText()
            assertEquals(setOf(RoleCode.CUSTOMER, RoleCode.valueOf(role)), users.findById(id).orElseThrow().roles)
            assertEquals(0, devices.findTop100ByUserIdOrderByCreatedAtDesc(id).size)
            val audit = access.findByIdempotencyKey(body.getValue("idempotencyKey"))!!
            assertEquals(owner.id, audit.actorUserId)
            assertEquals(body["reason"], audit.reason)
            assertEquals(id, tree(call("accounts", owner, "POST", body))["record"]["id"].asText())
            assertEquals(409, call("accounts", owner, "POST", body + ("reason" to "Different intent")).statusCode())
            assertEquals(409, call("accounts", owner, "POST", createBody(email = body.getValue("email").uppercase())).statusCode())
            assertEquals(1, operations.findTop50ByKindAndEntityIdOrderByCreatedAtDesc("accounts", id).size)
        }
        assertEquals(400, call("accounts", owner, "POST", createBody("SUPPORT_AGENT")).statusCode())
    }

    @Test fun newRiderCanUsePhoneIdentityButAdministratorRequiresEmail() {
        val owner = user(RoleCode.SUPER_ADMIN)
        val phone = "+228" + (10000000 + kotlin.random.Random.nextInt(89999999)).toString()
        val body = (createBody("COURIER") - "email") + ("phoneNumber" to phone)
        val result = call("accounts", owner, "POST", body)
        assertEquals(200, result.statusCode(), result.body())
        val account = users.findById(tree(result)["record"]["id"].asText()).orElseThrow()
        assertEquals(phone, account.phoneNumber)
        assertNull(account.email)
        assertEquals(409, call("accounts", owner, "POST", body + ("idempotencyKey" to UUID.randomUUID().toString())).statusCode())
        assertEquals(400, call("accounts", owner, "POST", body + ("roleCode" to "ADMIN") + ("idempotencyKey" to UUID.randomUUID().toString())).statusCode())
        assertEquals(400, call("accounts", owner, "POST", createBody("COURIER") - "email").statusCode())
    }

    @Test fun profileEditsRequireExactRevisionAndCannotChangeIdentityOrAccountAccess() {
        val owner = user(RoleCode.SUPER_ADMIN); val person = user(RoleCode.COURIER)
        val path = "accounts/${person.id}"; val body = guarded(path, owner) + ("name" to "Updated rider name")
        assertEquals(200, call(path, owner, "PATCH", body).statusCode())
        assertEquals("Updated rider name", users.findById(person.id!!).orElseThrow().name)
        assertEquals(200, call(path, owner, "PATCH", body).statusCode())
        assertEquals(409, call(path, owner, "PATCH", body + ("idempotencyKey" to UUID.randomUUID().toString())).statusCode())
        assertEquals(400, call(path, owner, "DELETE", guarded(path, owner)).statusCode())
        assertEquals(400, call(path, owner, "PATCH", guarded(path, owner) + ("status" to "INACTIVE")).statusCode())
        assertTrue(users.findById(person.id!!).orElseThrow().active)
        assertEquals(person.email, users.findById(person.id!!).orElseThrow().email)
        assertEquals(403, call(path, user(RoleCode.ADMIN)).statusCode())
    }

    @Test fun riderVehicleAndRemovalAreBlockedUntilCurrentMissionFinishes() {
        val owner = user(RoleCode.SUPER_ADMIN); val rider = user(RoleCode.COURIER)
        val courier = couriers.saveAndFlush(CourierRecord(UUID.randomUUID().toString(), userId = rider.id, vehicleType = "Moto", workforceType = "FREELANCER"))
        val mission = missions.saveAndFlush(DeliveryMission(deliveryCode = UUID.randomUUID().toString(), orderId = "fixture-order", courierId = courier.id,
            deliveryMode = DeliveryMissionRecordMode.STANDARD, destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS))
        val path = "couriers/${courier.id}"
        assertEquals(409, call(path, owner, "DELETE", guarded(path, owner)).statusCode())
        assertEquals(409, call(path, owner, "PATCH", guarded(path, owner) + ("vehicleType" to "Bicycle")).statusCode())
        mission.status = DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER; missions.saveAndFlush(mission)
        val archive = guarded(path, owner)
        assertEquals(200, call(path, owner, "DELETE", archive).statusCode())
        assertEquals("INACTIVE", couriers.findById(courier.id).orElseThrow().status)
        assertTrue(missions.existsById(mission.id!!))
        assertEquals(200, call(path, owner, "DELETE", archive).statusCode())
        assertEquals(200, call(path, owner, "PATCH", guarded(path, owner) + ("status" to "ACTIVE")).statusCode())
        assertEquals(200, call(path, owner, "PATCH", guarded(path, owner) + ("vehicleType" to "Bicycle")).statusCode())
        assertEquals("Bicycle", couriers.findById(courier.id).orElseThrow().vehicleType)
    }

    @Test fun vendorRemovalRetainsHistoryAndRequiresOrdersToBeResolved() {
        val owner = user(RoleCode.SUPER_ADMIN); val vendor = user(RoleCode.MERCHANT_OWNER)
        val merchant = merchants.saveAndFlush(MerchantRecord(UUID.randomUUID().toString(), ownerUserId = vendor.id, name = "Old shop"))
        val order = subOrders.saveAndFlush(MerchantSubOrder(subOrderCode = UUID.randomUUID().toString(), orderId = "fixture-order", merchantId = merchant.id))
        val path = "merchants/${merchant.id}"
        assertEquals(200, call(path, owner, "PATCH", guarded(path, owner) + ("name" to "New shop")).statusCode())
        assertEquals(409, call(path, owner, "DELETE", guarded(path, owner)).statusCode())
        order.status = MerchantSubOrderStatus.CANCELLED; subOrders.saveAndFlush(order)
        assertEquals(200, call(path, owner, "DELETE", guarded(path, owner)).statusCode())
        assertEquals("INACTIVE", merchants.findById(merchant.id).orElseThrow().status)
        assertTrue(subOrders.existsById(order.id!!))
        assertEquals(200, call(path, owner, "PATCH", guarded(path, owner) + ("status" to "ACTIVE")).statusCode())
        assertEquals(3, tree(call(path, owner))["history"].size())
    }

    @Test fun pendingCheckoutProtectsBothVendorAndHubUntilResolved() {
        val owner = user(RoleCode.SUPER_ADMIN); val person = user(RoleCode.MERCHANT_OWNER, RoleCode.RELAY_PARTNER)
        val merchant = merchants.saveAndFlush(MerchantRecord(UUID.randomUUID().toString(), ownerUserId = person.id, name = "Pending shop"))
        val point = points.saveAndFlush(RelayPointRecord(UUID.randomUUID().toString(), operatorUserId = person.id, name = "Pending hub"))
        val id = UUID.randomUUID().toString()
        val payload = json.writeValueAsString(mapOf("checkoutId" to id, "customerId" to owner.id, "serviceLevel" to "Regular", "route" to "PointDeRelai",
            "lines" to listOf(mapOf("productId" to "fixture-product", "sellerId" to merchant.id, "sellerName" to merchant.name, "productName" to "Package", "category" to "GeneralGoods", "quantity" to 1, "unitPriceCfa" to 100, "photoEvidenceType" to "GENERIC_CATALOG_IMAGE")),
            "deliveryDistanceKm" to 1.0, "referralCreditCfa" to 0, "paymentProvider" to "fixture", "paymentReference" to id, "countryCode" to "TG",
            "relayDestination" to mapOf("id" to point.id, "name" to point.name, "latitude" to 6.1, "longitude" to 1.2)))
        var checkout = pending.saveAndFlush(PendingPaymentCheckoutRecord(id, owner.id!!, paymentProvider = "fixture", paymentReference = id, amountCfa = 100, requestPayload = payload, pricingPayload = "{}", status = PendingPaymentCheckoutStatus.AWAITING_WEBHOOK, createdAt = Instant.now(), updatedAt = Instant.now()))
        try {
            for (path in listOf("merchants/${merchant.id}", "hubs/${point.id}")) {
                val result = call(path, owner, "DELETE", guarded(path, owner))
                assertEquals(409, result.statusCode(), result.body())
                assertTrue(result.body().contains("directory_checkout_in_progress"), result.body())
            }
        } finally { checkout.status = PendingPaymentCheckoutStatus.CANCELLED; checkout = pending.saveAndFlush(checkout) }
        for (path in listOf("merchants/${merchant.id}", "hubs/${point.id}")) assertEquals(200, call(path, owner, "DELETE", guarded(path, owner)).statusCode())
        assertEquals(PendingPaymentCheckoutStatus.CANCELLED, pending.findById(checkout.checkoutId).orElseThrow().status)
    }

    @Test fun hubRemovalRequiresEmptyCustodyAndRestorationRequiresAuthorizedOperator() {
        val owner = user(RoleCode.SUPER_ADMIN); val operator = user(RoleCode.RELAY_PARTNER)
        val point = points.saveAndFlush(RelayPointRecord(UUID.randomUUID().toString(), name = "Hub", operatorUserId = operator.id))
        val parcel = parcels.saveAndFlush(RelayParcelRecord(UUID.randomUUID().toString(), relayPointId = point.id, category = RelayParcelCategory.GeneralGoods,
            status = RelayParcelStatus.Deposited, createdAt = Instant.now(), updatedAt = Instant.now()))
        val path = "hubs/${point.id}"
        assertEquals(409, call(path, owner, "DELETE", guarded(path, owner)).statusCode())
        parcel.status = RelayParcelStatus.PickedUp; parcels.saveAndFlush(parcel)
        assertEquals(200, call(path, owner, "DELETE", guarded(path, owner)).statusCode())
        assertTrue(parcels.existsById(parcel.id))
        users.findById(operator.id!!).orElseThrow().let { it.active = false; users.saveAndFlush(it) }
        assertEquals(409, call(path, owner, "PATCH", guarded(path, owner) + ("status" to "ACTIVE")).statusCode())
        users.findById(operator.id!!).orElseThrow().let { it.active = true; users.saveAndFlush(it) }
        assertEquals(200, call(path, owner, "PATCH", guarded(path, owner) + ("status" to "ACTIVE")).statusCode())
    }
}
