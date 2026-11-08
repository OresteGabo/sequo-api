package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.*
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:admin_catalog_media;DB_CLOSE_DELAY=-1",
    "sequo.catalog.approved-generic-image-url=https://cdn.sequo.example/approved/standard.jpg",
    "spring.servlet.multipart.max-file-size=8MB", "spring.servlet.multipart.max-request-size=9MB",
])
class AdminCatalogMediaHttpTest @Autowired constructor(private val users: UserRepository, private val jwt: JwtService,
    private val products: CommerceProductRepository, private val payloads: CatalogCameraPayloadRepository,
    private val history: AdminCatalogMediaChangeRepository) {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    @Test fun adminCameraAndPublicationUseActualBytesGuardedRevisionsAndReplaySafeHistory() {
        val token = admin()
        val created = call("POST", "/api/catalog/products", token,
            """{"name":"SEQUO prepared product","kind":"SellerSpecific","basePriceCfa":1500,"status":"ACTIVE","cameraVerified":true,"capturedAtLabel":"forged"}""")
        assertEquals(200, created.statusCode(), created.body())
        val id = json.readTree(created.body())["id"].asText()
        val path = path(id)
        var current = read(path, token)
        assertEquals("INACTIVE", current["product"]["status"].asText())
        assertTrue(current["product"]["photoRequired"].asBoolean())
        assertFalse(current["product"]["cameraVerified"].asBoolean())
        assertTrue(current["product"]["capturedAtLabel"].isNull)
        val revision = current["product"]["updatedAt"].asText()
        assertEquals(400, call("POST", "$path/publication", token, mutation(revision, "publish-before-photo", "\"status\":\"ACTIVE\"")).statusCode())
        assertEquals(400, call("PATCH", "/api/catalog/products/$id", token, """{"status":"ACTIVE","cameraVerified":true}""").statusCode())
        assertEquals(400, call("POST", "$path/photos", token, mutation(revision, "generic-denied", "\"source\":\"GenericCatalogReference\",\"catalogImageId\":\"standard\"")).statusCode())
        assertEquals(0, read(path, token)["history"].size())

        val at = Instant.now().toString()
        val bytes = jpeg()
        assertEquals(400, upload(path, token, "not-an-image".toByteArray(), at, revision, "bad-image").statusCode())
        assertEquals(400, upload(path, token, bytes, at, revision, "wrong-type", "image/png").statusCode())
        assertEquals(400, upload(path, token, bytes, Instant.now().minusSeconds(901).toString(), revision, "stale-capture").statusCode())
        assertFalse(payloads.existsById(id))
        val saved = upload(path, token, bytes, at, revision, "camera-ok")
        assertEquals(200, saved.statusCode(), saved.body())
        current = read(path, token)
        assertTrue(current["product"]["cameraVerified"].asBoolean())
        assertEquals(1, current["history"].size())
        assertEquals("CAMERA_IMAGE", current["history"][0]["action"].asText())
        assertEquals("Photograph verified by operator", current["history"][0]["reason"].asText())
        val preview = Base64.getDecoder().decode(current["previewDataUrl"].asText().substringAfter(','))
        assertEquals(256, ImageIO.read(preview.inputStream()).width)
        assertEquals(404, call("GET", "/api/catalog/products/$id/image").statusCode())
        assertEquals(200, upload(path, token, bytes, at, revision, "camera-ok").statusCode())
        assertEquals(1, read(path, token)["history"].size())
        assertEquals(409, upload(path, token, bytes, at, revision, "different-key-stale").statusCode())
        assertEquals(409, upload(path, token, jpeg(12), at, revision, "camera-ok").statusCode())
        val published = call("POST", "$path/publication", token, mutation(current["product"]["updatedAt"].asText(), "publish-ok", "\"status\":\"ACTIVE\""))
        assertEquals(200, published.statusCode(), published.body())
        assertEquals(200, call("GET", "/api/catalog/products/$id/image").statusCode())
        val last = read(path, token)
        assertEquals(2, last["history"].size())
        assertEquals("ACTIVE", last["product"]["status"].asText())
        assertTrue(last["history"].all { it["actorUserId"].asText().isNotBlank() })
    }

    @Test fun approvedGenericReferenceNeverAcceptsCustomUrlOrNonAdminAndRetainsProductOwnership() {
        val token = admin()
        val id = "standard-" + UUID.randomUUID()
        products.saveAndFlush(ProductRecord(id, name = "Standard product", kind = CatalogProductKind.GenericSealedItem, status = CatalogProductStatus.INACTIVE))
        val current = read(path(id), token)
        val revision = current["product"]["updatedAt"].asText()
        val body = mutation(revision, "generic-" + UUID.randomUUID(), "\"source\":\"GenericCatalogReference\",\"catalogImageId\":\"standard\"")
        for (role in listOf(RoleCode.CUSTOMER, RoleCode.COURIER, RoleCode.RELAY_PARTNER, RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF)) {
            val other = jwt.generateAccessToken(UserSession("foreign-actor", null, AuthProvider.EMAIL, setOf(role), setOf("foreign-merchant")))
            assertEquals(403, call("GET", path(id), other).statusCode())
            assertEquals(403, call("POST", path(id) + "/photos", other, body).statusCode())
        }
        assertTrue(call("GET", path(id)).statusCode() in listOf(401, 403))
        val wrong = body.replace("\"standard\"", "\"unapproved\"")
        assertEquals(400, call("POST", path(id) + "/photos", token, wrong).statusCode())
        // Extra client URL/merchant claims cannot replace the approved image or product owner.
        val forged = body.dropLast(1) + ",\"sourceUrl\":\"https://unapproved.example/image.jpg\",\"merchantId\":\"forged-owner\"}"
        val result = call("POST", path(id) + "/photos", token, forged)
        assertEquals(200, result.statusCode(), result.body())
        val reread = read(path(id), token)
        assertEquals("https://cdn.sequo.example/approved/standard.jpg", reread["product"]["imageUrl"].asText())
        assertFalse(reread["product"]["cameraVerified"].asBoolean())
        assertTrue(products.findById(id).orElseThrow().merchantId == null)
        assertEquals(200, call("POST", path(id) + "/photos", token, body).statusCode())
        assertEquals(1, read(path(id), token)["history"].size())
        assertEquals(409, call("POST", path(id) + "/photos", token, body.replace("Photograph verified by operator", "Changed intent")).statusCode())
    }

    @Test fun operatorApprovesAnActualGenericJpegWithoutClaimingCameraCapture() {
        val token = admin()
        val id = "generic-upload-" + UUID.randomUUID()
        products.saveAndFlush(ProductRecord(id, name = "Standard sealed product", kind = CatalogProductKind.GenericSealedItem, status = CatalogProductStatus.INACTIVE))
        val revision = read(path(id), token)["product"]["updatedAt"].asText()
        val bytes = jpeg()
        assertEquals(400, genericUpload(id, token, bytes, revision, "not-approved-$id", approved = false).statusCode())
        assertEquals(400, genericUpload(id, token, "not-jpeg".toByteArray(), revision, "bad-generic-$id").statusCode())
        assertFalse(payloads.existsById(id))
        val seller = "generic-forbidden-" + UUID.randomUUID()
        products.saveAndFlush(ProductRecord(seller, name = "Fresh prepared product", kind = CatalogProductKind.SellerSpecific, status = CatalogProductStatus.INACTIVE))
        assertEquals(400, genericUpload(seller, token, bytes, read(path(seller), token)["product"]["updatedAt"].asText(), "seller-generic-denied").statusCode())
        val customer = jwt.generateAccessToken(UserSession("generic-upload-customer", null, AuthProvider.EMAIL, setOf(RoleCode.CUSTOMER)))
        assertEquals(403, genericUpload(id, customer, bytes, revision, "customer-generic-denied").statusCode())
        val saved = genericUpload(id, token, bytes, revision, "generic-file-$id")
        assertEquals(200, saved.statusCode(), saved.body())
        var current = read(path(id), token)
        assertFalse(current["product"]["cameraVerified"].asBoolean())
        assertEquals("SEQUO-approved generic image", current["product"]["capturedAtLabel"].asText())
        assertEquals("GENERIC_UPLOAD", current["history"][0]["action"].asText())
        assertEquals(200, genericUpload(id, token, bytes, revision, "generic-file-$id").statusCode())
        assertEquals(1, read(path(id), token)["history"].size())
        assertEquals(409, genericUpload(id, token, bytes, revision, "stale-generic-$id").statusCode())
        assertEquals(200, call("POST", path(id) + "/publication", token, mutation(current["product"]["updatedAt"].asText(), "publish-$id", "\"status\":\"ACTIVE\"")).statusCode())
        val image = client.send(HttpRequest.newBuilder(URI("http://localhost:$port/api/catalog/products/$id/image")).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, image.statusCode())
        assertEquals(512, ImageIO.read(image.body().inputStream()).width)
        current = read(path(id), token)
        assertEquals(200, call("POST", path(id) + "/publication", token, mutation(current["product"]["updatedAt"].asText(), "hide-$id", "\"status\":\"INACTIVE\"")).statusCode())
        assertEquals(404, call("GET", "/api/catalog/products/$id/image").statusCode())
    }

    private fun admin(): String {
        val user = users.saveAndFlush(User(email = "admin-media-${UUID.randomUUID()}@fixture.invalid", provider = AuthProvider.EMAIL, roles = mutableSetOf(RoleCode.ADMIN)))
        return jwt.generateAccessToken(UserSession(requireNotNull(user.id), user.email, AuthProvider.EMAIL, user.roles))
    }
    private fun path(id: String) = "/api/admin/web/catalog/products/$id/media"
    private fun mutation(revision: String, key: String, fields: String) = """{$fields,"reason":"Photograph verified by operator","expectedUpdatedAt":"$revision","idempotencyKey":"$key"}"""
    private fun read(path: String, token: String): JsonNode {
        val result = call("GET", path, token)
        assertEquals(200, result.statusCode(), result.body())
        assertEquals("no-store", result.headers().firstValue("cache-control").orElseThrow())
        return json.readTree(result.body())
    }
    private fun jpeg(size: Int = 512) = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(size, size / 2, BufferedImage.TYPE_INT_RGB), "jpeg", it) }.toByteArray()
    private fun upload(path: String, token: String, bytes: ByteArray, at: String, revision: String, key: String, mime: String = "image/jpeg"): HttpResponse<String> {
        val boundary = "sequo-admin-camera-boundary"
        val data = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"capture.jpg\"\r\nContent-Type: $mime\r\n\r\n".toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray()
        val params = mapOf("capturedAt" to at, "deviceId" to "admin-camera-fixture", "reason" to "Photograph verified by operator", "expectedUpdatedAt" to revision, "idempotencyKey" to key)
            .entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path/photos/camera?$params"))
            .header("Authorization", "Bearer $token").header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(data)).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
    private fun genericUpload(id: String, token: String, bytes: ByteArray, revision: String, key: String, approved: Boolean = true): HttpResponse<String> {
        val boundary = "sequo-admin-generic-boundary"
        val data = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"generic.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray()
        val params = mapOf("genericApproved" to approved.toString(), "reason" to "SEQUO approves this standard product image", "expectedUpdatedAt" to revision, "idempotencyKey" to key)
            .entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI("http://localhost:$port${path(id)}/photos/generic?$params"))
            .header("Authorization", "Bearer $token").header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(data)).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
    private fun call(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.header("Content-Type", "application/json") }
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
