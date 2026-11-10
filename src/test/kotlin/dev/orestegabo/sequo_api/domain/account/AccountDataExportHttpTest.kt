package dev.orestegabo.sequo_api.domain.account

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.*
import dev.orestegabo.sequo_api.domain.commerce.*
import dev.orestegabo.sequo_api.domain.platform.*
import java.time.Instant
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.*
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:account_exports;DB_CLOSE_DELAY=-1"
])
class AccountDataExportHttpTest @Autowired constructor(private val jdbc: JdbcTemplate, private val users: UserRepository,
    private val jwt: JwtService, private val products: CommerceProductRepository,
    private val addresses: CustomerAddressRepository, private val devices: UserDeviceRepository,
    private val cart: CustomerCartItemRepository) {
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    private val http = HttpClient.newHttpClient()
    private fun account(name: String = "Export customer"): Pair<String, String> {
        val user = users.saveAndFlush(User(email = "${UUID.randomUUID()}@fixture.invalid", name = name))
        val id = requireNotNull(user.id)
        return id to jwt.generateAccessToken(UserSession(id, user.email, AuthProvider.EMAIL))
    }
    private fun call(path: String, token: String? = null, body: Any? = null): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/account/$path"))
            .header("Content-Type", "application/json")
        token?.let { request.header("Authorization", "Bearer $it") }
        if (body == null) request.GET() else request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) }
        }
    }
    @Test fun authenticatedArchiveIsCompleteScopedEscapedAndExcludesSecrets() {
        val own = account("<script>alert('unsafe')</script>")
        val other = account("OTHER_CUSTOMER_PRIVATE")
        for ((id, label) in listOf(own.first to "MY_PRIVATE_ADDRESS", other.first to "OTHER_ADDRESS_SECRET")) {
            addresses.saveAndFlush(CustomerAddressRecord("address-$id", id, CustomerAddressKind.HOME, label, "Recipient", "+22800000000", "Lome"))
        }
        devices.saveAndFlush(UserDevice(userId = own.first, deviceId = "own-device-${own.first}", appSource = AppSource.SEQUO_APP,
            refreshTokenHash = "NEVER_EXPORT_REFRESH_SECRET", expiresAt = Instant.now().plusSeconds(3600)))
        val response = call("data-export", own.second, mapOf("devicePreferences" to mapOf("language" to "French", "accessToken" to "NEVER_EXPORT_CLIENT_SECRET"), "userId" to other.first))
        assertEquals(200, response.statusCode(), response.body().toString(Charsets.UTF_8))
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"))
        val files = unzip(response.body())
        assertTrue(files.keys.containsAll(listOf("START_HERE.html", "data.json", "data.txt", "README.txt")))
        val data = files.getValue("data.json").toString(Charsets.UTF_8)
        assertTrue(data.contains("MY_PRIVATE_ADDRESS")); assertTrue(data.contains("French"))
        for (secret in listOf("OTHER_CUSTOMER_PRIVATE", "OTHER_ADDRESS_SECRET", "NEVER_EXPORT_REFRESH_SECRET", "NEVER_EXPORT_CLIENT_SECRET")) assertFalse(data.contains(secret), secret)
        assertFalse(files.getValue("START_HERE.html").toString(Charsets.UTF_8).contains("<script>"))
        assertTrue(files.getValue("START_HERE.html").toString(Charsets.UTF_8).contains("&lt;script&gt;"))
        val tree = json.readTree(data)
        assertEquals(1, tree["sections"]["Saved addresses"].size())
        assertEquals(0, tree["sections"]["Orders"].size())
    }
    @Test fun storedPhotoAttachmentIsLimitedToProductsInOwnCart() {
        val own = account(); val other = account()
        val ids = listOf("photo-${UUID.randomUUID()}", "photo-${UUID.randomUUID()}")
        ids.forEach { products.saveAndFlush(ProductRecord(id = it, name = "Export photo")) }
        val ownBytes = byteArrayOf(-1, -40, -1, 42)
        jdbc.update("insert into catalog_camera_payloads(product_id,jpeg) values (?,?)", ids[0], ownBytes)
        jdbc.update("insert into catalog_camera_payloads(product_id,jpeg) values (?,?)", ids[1], byteArrayOf(77, 88, 99))
        for ((userId, productId) in listOf(own.first to ids[0], other.first to ids[1])) cart.saveAndFlush(
            CustomerCartItemRecord(UUID.randomUUID().toString(), userId, productId, quantity = 1))
        val response = call("data-export", own.second, emptyMap<String, Any>())
        assertEquals(200, response.statusCode(), response.body().toString(Charsets.UTF_8))
        val files = unzip(response.body()); val photos = files.filterKeys { it.startsWith("attachments/") }
        assertEquals(1, photos.size); assertContentEquals(ownBytes, photos.values.single())
        assertFalse(files.getValue("data.json").toString(Charsets.UTF_8).contains(requireNotNull(ids[1])))
        jdbc.update("delete from catalog_camera_payloads where product_id in (?,?)", ids[0], ids[1])
    }
    @Test fun requestsAreIdempotentAndStatusCannotLeakAnotherAccount() {
        val own = account(); val other = account()
        val invalid = call("deletion-requests", own.second, mapOf("confirmation" to "not-confirmed"))
        assertEquals(400, invalid.statusCode())
        val first = call("deletion-requests", own.second, mapOf("confirmation" to "DELETE_MY_ACCOUNT"))
        assertEquals(200, first.statusCode())
        val second = call("deletion-requests", own.second, mapOf("confirmation" to "DELETE_MY_ACCOUNT"))
        assertEquals(200, second.statusCode(), second.body().toString(Charsets.UTF_8))
        assertEquals(json.readTree(first.body())["id"].asText(), json.readTree(second.body())["id"].asText())
        val status = call("deletion-requests", own.second)
        assertEquals(1, json.readTree(status.body()).size())
        assertEquals("REQUESTED", json.readTree(status.body())[0]["status"].asText())
        assertEquals(0, json.readTree(call("deletion-requests", other.second).body()).size())
        assertTrue(users.findById(own.first).orElseThrow().active) // request, not a false erasure claim
    }
    @Test fun exportsRequireAuthenticationAndAreRateLimited() {
        assertTrue(call("data-export", body = emptyMap<String, Any>()).statusCode() in setOf(401,403))
        assertTrue(call("deletion-requests").statusCode() in setOf(401,403))
        val own = account()
        repeat(3) { assertEquals(200, call("data-export", own.second, emptyMap<String, Any>()).statusCode()) }
        assertEquals(429, call("data-export", own.second, emptyMap<String, Any>()).statusCode())
    }
    @Test fun inactiveAccountCannotExportWithALegacyToken() {
        val own = account(); jdbc.update("update users set is_active=false where id=?", own.first)
        assertEquals(401, call("data-export", own.second, emptyMap<String, Any>()).statusCode())
    }
}
