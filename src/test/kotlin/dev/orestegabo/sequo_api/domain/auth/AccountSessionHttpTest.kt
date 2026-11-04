package dev.orestegabo.sequo_api.domain.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:account_session_http;DB_CLOSE_DELAY=-1",
        "sequo.auth.jwt.secret=account_session_test_key_for_local_http_fixtures",
    ],
)
class AccountSessionHttpTest @Autowired constructor(
    private val auth: AuthService,
    private val users: UserRepository,
    private val memberships: MerchantMembershipRepository,
    private val merchants: CommerceMerchantRepository,
) {
    @MockitoBean
    private lateinit var google: GoogleTokenVerifier

    @LocalServerPort
    private var port = 0

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val json = ObjectMapper()

    @Test
    fun customerSignsUpAndRestoresWithDeviceBoundRefresh() {
        Mockito.`when`(google.verify("fixture-google-token")).thenReturn(
            SocialUser("fixture-google-subject", AuthProvider.GOOGLE, "signup-fixture@sequo.test", "Fixture Customer", null, true)
        )
        val body = """{"token":"fixture-google-token","device":{"deviceId":"customer-device","appSource":"SEQUO_APP"}}"""
        val first = read(request("POST", "/api/auth/social/GOOGLE", body = body))
        val profile = read(request("GET", "/api/auth/me", first.get("accessToken").asText()))
        assertEquals("signup-fixture@sequo.test", profile.get("email").asText())
        assertEquals(listOf("CUSTOMER"), profile.get("roles").map(JsonNode::asText))
        assertTrue(profile.get("merchantScopeIds").isEmpty)
        assertNull(profile.get("refreshToken"))

        val login = read(request("POST", "/api/auth/social/GOOGLE", body = body))
        val loginProfile = read(request("GET", "/api/auth/me", login.get("accessToken").asText()))
        assertEquals(profile.get("id"), loginProfile.get("id"))

        val expired = JwtService(
            "account_session_test_key_for_local_http_fixtures", -60_000, 60_000, "sequo-api", "sequo-mobile",
        ).generateAccessToken(UserSession(profile.get("id").asText(), null, AuthProvider.GOOGLE))
        for (path in listOf("/api/auth/me", "/api/v1/auth/me")) {
            assertEquals(401, request("GET", path, expired).statusCode())
            assertEquals(401, request("GET", path).statusCode())
        }
        val refreshToken = login.get("refreshToken").asText()
        fun refresh(device: String) = request(
            "POST", "/api/auth/refresh", body = """{"refreshToken":"$refreshToken","deviceId":"$device"}""",
        )
        assertEquals(401, refresh("wrong-device").statusCode())
        val restored = read(refresh("customer-device"))
        assertNotEquals(refreshToken, restored.get("refreshToken").asText())
        assertEquals(401, refresh("customer-device").statusCode())
        assertEquals(profile.get("id"), read(request("GET", "/api/auth/me", restored.get("accessToken").asText())).get("id"))
    }

    @Test
    fun issuedRolesAndMembershipsMatchWorkspaceAuthorization() {
        val merchant = merchants.save(MerchantRecord(id = "membership-fixture", name = "Fixture Store"))
        val vendor = account("vendor", RoleCode.CUSTOMER)
        memberships.save(MerchantMembership(userId = requireNotNull(vendor.id), merchantId = merchant.id, role = RoleCode.MERCHANT_STAFF))
        memberships.save(MerchantMembership(userId = requireNotNull(vendor.id), merchantId = "inactive-fixture", role = RoleCode.MERCHANT_OWNER, active = false))
        val vendorToken = token(vendor, AppSource.SEQUO_HUB)
        val profile = read(request("GET", "/api/auth/me", vendorToken))
        assertEquals(listOf(merchant.id), profile.get("merchantScopeIds").map(JsonNode::asText))
        assertTrue(profile.get("roles").any { it.asText() == "MERCHANT_STAFF" })
        assertNotEquals(profile.get("id").asText(), merchant.id)
        val create = """{"merchantId":"${merchant.id}","name":"Account fixture product","basePriceCfa":500}"""
        assertEquals(200, request("POST", "/api/merchant/catalog/products", vendorToken, create).statusCode())
        assertEquals(403, request("POST", "/api/merchant/catalog/products", vendorToken, create.replace(merchant.id, "inactive-fixture")).statusCode())
        assertEquals(403, request("GET", "/api/rider/missions", vendorToken).statusCode())
        assertEquals(403, request("GET", "/api/admin/web/users", vendorToken).statusCode())

        val riderToken = token(account("rider", RoleCode.COURIER), AppSource.SEQUO_RIDER)
        assertEquals(200, request("GET", "/api/rider/missions", riderToken).statusCode())
        assertEquals(403, request("POST", "/api/merchant/catalog/products", riderToken, create).statusCode())
        assertEquals(403, request("GET", "/api/admin/web/users", riderToken).statusCode())
        val adminToken = token(account("admin", RoleCode.ADMIN), AppSource.SEQUO_ADMIN)
        assertEquals(200, request("GET", "/api/admin/web/users", adminToken).statusCode())
        assertEquals(403, request("GET", "/api/rider/missions", adminToken).statusCode())
        val relay = account("relay", RoleCode.RELAY_PARTNER)
        val relayToken = token(relay, AppSource.SEQUO_HUB)
        assertEquals(200, request("GET", "/api/hub/summary?hubId=${relay.id}", relayToken).statusCode())
        assertEquals(403, request("GET", "/api/hub/summary?hubId=other-relay", relayToken).statusCode())
        assertEquals(403, request("GET", "/api/admin/web/users", relayToken).statusCode())
    }

    @Test
    fun savedContactAndDestinationStayWithTheirOwner() {
        val customer = token(account("contact-owner", RoleCode.CUSTOMER), AppSource.SEQUO_APP)
        val other = token(account("contact-other", RoleCode.COURIER), AppSource.SEQUO_RIDER)
        val addresses = "/api/customer/addresses"
        val destination = """{"kind":"HOME","label":"Home","recipientName":"Fixture Recipient","phoneNumber":"+22890000001","city":"Lome","streetHint":"Fixture landmark"}"""
        val address = read(request("POST", addresses, customer, destination))
        val id = address.get("id").asText()
        val profilePath = "/api/customer/profile"
        read(request("PUT", profilePath, customer, """{"displayName":"Fixture Recipient","phoneNumber":"+22890000001","defaultAddressId":"$id","marketingOptIn":false}"""))
        assertEquals(id, read(request("GET", profilePath, customer)).get("defaultAddressId").asText())
        assertEquals("+22890000001", read(request("GET", addresses, customer)).single().get("phoneNumber").asText())
        assertEquals("Fixture landmark", read(request("GET", addresses, customer)).single().get("streetHint").asText())
        assertTrue(read(request("GET", addresses, other)).isEmpty)
        assertEquals(404, request("GET", profilePath, other).statusCode())
        assertEquals(400, request("PUT", profilePath, other,
            """{"displayName":"Other","defaultAddressId":"$id","marketingOptIn":false}""").statusCode())
        assertEquals(400, request("PUT", "$addresses/$id", other, destination).statusCode())
        assertTrue(read(request("GET", addresses, other)).isEmpty)
        assertEquals(id, read(request("PUT", "$addresses/$id", customer, destination)).get("id").asText())
    }

    private fun account(name: String, role: RoleCode) =
        users.save(User(email = "$name-account-fixture@sequo.test", provider = AuthProvider.GOOGLE, roles = mutableSetOf(role)))

    private fun token(user: User, source: AppSource) = requireNotNull(
        auth.issueTokensForExistingUser(requireNotNull(user.id), user.provider, DeviceBinding("device-${user.id}", source, fcmToken = null))
    ).accessToken

    private fun read(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }

    private fun request(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.header("Content-Type", "application/json") }
        request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
