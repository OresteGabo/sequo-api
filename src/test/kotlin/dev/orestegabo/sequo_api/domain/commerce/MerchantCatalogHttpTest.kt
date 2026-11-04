package dev.orestegabo.sequo_api.domain.commerce

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.JwtService
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.UserSession
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.auth.UserStatus
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressKind
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressRecord
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressRepository
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:merchant_catalog_http;DB_CLOSE_DELAY=-1",
        "sequo.catalog.approved-generic-image-url=https://cdn.sequo.example/catalog/standard.jpg",
        "spring.servlet.multipart.max-file-size=8MB",
        "sequo.cart.straight-line-dispatch-origin=0,0",
        "spring.servlet.multipart.max-request-size=9MB",
    ],
)
class MerchantCatalogHttpTest @Autowired constructor(
    private val merchants: CommerceMerchantRepository,
    private val jwt: JwtService,
    private val storefronts: MerchantStorefrontRepository,
    private val products: CommerceProductRepository,
    private val addresses: CustomerAddressRepository,
    private val users: UserRepository,
) {
    @LocalServerPort
    private var port = 0
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val json = ObjectMapper()
    private val endpoint = "/api/merchant/catalog/products"

    @Test
    fun merchantPublishesEditsAndTogglesCustomerVisibility() {
        val merchant = "catalog-http-owner"
        merchants.save(MerchantRecord(id = merchant, name = "Catalog HTTP owner", status = "ACTIVE"))
        storefronts.save(MerchantStorefrontRecord(merchantId = merchant))
        val owner = token(merchant)
        val other = token("catalog-http-other")
        val customer = token("catalog-http-customer", RoleCode.CUSTOMER)
        val payload = """{"merchantId":"$merchant","name":"Mango","kind":"GenericSealedItem","detail":"Fresh mango","basePriceCfa":750,"idempotencyKey":"catalog-test-create"}"""
        val created = request("POST", endpoint, owner, payload)
        assertEquals(200, created.statusCode(), created.body())
        val id = json.readTree(created.body()).path("id").asText()

        val replay = request("POST", endpoint, owner, payload)
        assertEquals(200, replay.statusCode(), replay.body())
        assertEquals(id, json.readTree(replay.body()).path("id").asText())
        assertEquals(409, request("POST", endpoint, owner, payload.replace("750", "999")).statusCode())

        fun readProduct() = json.readTree(request("GET", "/api/catalog/home").body()).let { home ->
            val listing = home.path("products").singleOrNull { it.path("id").asText() == id }
            val shopListing = home.path("merchants").firstOrNull { it.path("id").asText() == merchant }
                ?.path("products")?.singleOrNull { it.path("id").asText() == id }
            assertEquals(listing, shopListing)
            listing
        }

        assertEquals(750, readProduct()?.path("priceCfa")?.asInt())
        assertEquals("Fresh mango", readProduct()?.path("detail")?.asText())
        assertEquals(403, request("POST", endpoint, other, payload).statusCode())
        assertEquals(400, request("POST", "$endpoint/$id/deactivate", other).statusCode())
        assertEquals(403, request("PATCH", "/api/catalog/products/$id", other, """{"status":"INACTIVE"}""").statusCode())
        assertEquals(403, request("POST", endpoint, customer, payload).statusCode())
        val unscoped = jwt.generateAccessToken(
            UserSession(merchant, null, AuthProvider.EMAIL, setOf(RoleCode.MERCHANT_OWNER))
        )
        assertEquals(403, request("POST", endpoint, unscoped, payload).statusCode())
        val invalidEdit = request("PATCH", "$endpoint/$id", owner, """{"status":"INACTIVE","basePriceCfa":-1}""")
        assertEquals(400, invalidEdit.statusCode())
        assertEquals("basePriceCfa must be non-negative.", json.readTree(invalidEdit.body()).path("message").asText())
        assertEquals(750, readProduct()?.path("priceCfa")?.asInt())

        val edit = """{"name":"Mango box","detail":"Two ripe mangoes","basePriceCfa":1250}"""
        val updated = request("PATCH", "$endpoint/$id?merchantId=$merchant", owner, edit)
        assertEquals(200, updated.statusCode(), updated.body())
        assertEquals(1250, readProduct()?.path("priceCfa")?.asInt())
        assertEquals("Two ripe mangoes", readProduct()?.path("detail")?.asText())
        assertEquals(200, request("POST", "$endpoint/$id/deactivate", owner).statusCode())
        assertNull(readProduct())
        val inventory = "/api/catalog/products?merchantId=$merchant&includeArchived=true"
        fun readInventory(token: String) = json.readTree(request("GET", inventory, token).body())
        assertEquals("INACTIVE", readInventory(owner).single().path("status").asText())
        assertEquals(0, readInventory(other).size())
        assertEquals(0, readInventory(customer).size())
        assertEquals(200, request("POST", "$endpoint/$id/activate", owner).statusCode())
        assertEquals("Mango box", readProduct()?.path("name")?.asText())
        assertEquals(1250, readProduct()?.path("priceCfa")?.asInt())
    }

    @Test
    fun vendorToggleControlsNewAndExistingThreadOffers() {
        val merchant = "bargaining-http-owner"
        val product = "existing-bargaining-product"
        merchants.save(MerchantRecord(id = merchant, name = "Bargaining merchant"))
        storefronts.save(MerchantStorefrontRecord(merchantId = merchant))
        products.save(
            ProductRecord(id = product, merchantId = merchant, name = "Rice", basePriceCfa = 2000, bargainingEnabled = true)
        )
        val owner = token(merchant)
        val customer = token("bargaining-http-customer", RoleCode.CUSTOMER)
        val other = token("bargaining-http-other")
        val threads = "/api/bargaining/threads"
        val newThread = """{"productId":"$product","amountCfa":1500}"""
        val initial = request("POST", threads, customer, newThread)
        assertEquals(200, initial.statusCode(), initial.body())
        val threadId = json.readTree(initial.body()).path("id").asText()
        val offers = "$threads/$threadId/offers"

        fun updateEligibility(enabled: Boolean, token: String) =
            request("PATCH", "$endpoint/$product?merchantId=$merchant", token, """{"bargainingEnabled":$enabled}""")

        fun assertEligibility(expected: Boolean) {
            val inventory = request("GET", "/api/catalog/products?merchantId=$merchant&includeArchived=true", owner)
            assertEquals(200, inventory.statusCode())
            assertEquals(expected, json.readTree(inventory.body()).single().get("bargainingEnabled").booleanValue())
            val home = json.readTree(request("GET", "/api/catalog/home").body())
            val listing = home.path("products").single { it.path("id").asText() == product }
            val shop = home.path("merchants").single { it.path("id").asText() == merchant }
            assertEquals(expected, listing.get("bargainingEnabled").booleanValue())
            assertEquals(listing, shop.path("products").single())
        }

        assertEquals(200, updateEligibility(false, owner).statusCode())
        assertEligibility(false)
        for (rejected in listOf(
            request("POST", threads, customer, newThread),
            request("POST", offers, customer, """{"amountCfa":1600}"""),
        )) {
            assertEquals(400, rejected.statusCode(), rejected.body())
            assertEquals("This product does not accept bargaining.", json.readTree(rejected.body()).path("message").asText())
        }
        val history = json.readTree(request("GET", threads, customer).body()).single()
        assertEquals(1, history.path("offers").size())
        assertEquals(1500, history.path("lastOfferCfa").asInt())
        assertEquals(403, updateEligibility(true, other).statusCode())
        assertEquals(400, request("PATCH", "$endpoint/$product", other, """{"bargainingEnabled":true}""").statusCode())
        assertEligibility(false)

        assertEquals(200, updateEligibility(true, owner).statusCode())
        assertEquals(200, request("PATCH", "$endpoint/$product", owner, """{"detail":"Legacy edit"}""").statusCode())
        assertEligibility(true)
        assertEquals(200, request("POST", offers, customer, """{"amountCfa":1600}""").statusCode())
        assertEquals(200, request("POST", threads, customer, newThread).statusCode())
    }

    @Test
    fun sellerCameraPhotoAndApprovedGenericReferenceReachTheCustomerCatalog() {
        val merchant = "photo-http-owner"
        merchants.save(MerchantRecord(id = merchant, name = "Photo merchant"))
        storefronts.save(MerchantStorefrontRecord(merchantId = merchant))
        val owner = token(merchant)
        val other = token("photo-http-other")
        val camera = request("POST", endpoint, owner,
            """{"merchantId":"$merchant","name":"Prepared meal","basePriceCfa":1200,"cameraVerified":true}""")
        assertEquals(200, camera.statusCode(), camera.body())
        val initial = json.readTree(camera.body())
        val id = initial.get("id").asText()
        assertEquals("INACTIVE", initial.get("status").asText())
        assertEquals(false, initial.get("cameraVerified").asBoolean())
        assertEquals(400, request("POST", "$endpoint/$id/activate", owner).statusCode())
        assertEquals(400, request("PATCH", "$endpoint/$id", owner,
            """{"status":"ACTIVE","cameraVerified":true}""").statusCode())

        val jpg = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(12, 12, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", it)
        }.toByteArray()
        val image = "/api/catalog/products/$id/image"
        assertEquals(400, upload(id, merchant, owner, "wrong".toByteArray(), "image/jpeg").statusCode())
        assertEquals(400, upload(id, merchant, owner, jpg, "image/png").statusCode())
        assertEquals(413, upload(id, merchant, owner, ByteArray(8 * 1024 * 1024 + 1), "image/jpeg").statusCode())
        assertEquals(403, upload(id, merchant, other, jpg, "image/jpeg").statusCode())
        assertEquals(404, request("GET", image).statusCode())

        val attached = upload(id, merchant, owner, jpg, "image/jpeg")
        assertEquals(200, attached.statusCode(), attached.body())
        assertEquals(image, json.readTree(attached.body()).get("imageUrl").asText())
        assertEquals(404, request("GET", image).statusCode())
        assertEquals(200, request("POST", "$endpoint/$id/activate", owner).statusCode())
        val binary = client.send(HttpRequest.newBuilder(URI.create("http://localhost:$port$image")).build(),
            HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, binary.statusCode())
        assertEquals(12, javax.imageio.ImageIO.read(binary.body().inputStream()).width)

        val standard = request("POST", endpoint, owner,
            """{"merchantId":"$merchant","name":"Sealed item","kind":"GenericSealedItem","basePriceCfa":500,"status":"INACTIVE"}""")
        assertEquals(200, standard.statusCode(), standard.body())
        val standardId = json.readTree(standard.body()).get("id").asText()
        val photo = "$endpoint/$standardId/photos"
        assertEquals(400, request("POST", photo, owner,
            """{"source":"GenericCatalogReference","catalogImageId":"standard","sourceUrl":"https://unapproved.example/image.jpg"}""").statusCode())
        val generic = request("POST", photo, owner, """{"source":"GenericCatalogReference","catalogImageId":"standard"}""")
        assertEquals(200, generic.statusCode(), generic.body())
        assertEquals(200, request("POST", "$endpoint/$standardId/activate", owner).statusCode())
        val visible = json.readTree(request("GET", "/api/catalog/products?merchantId=$merchant").body())
        val images = visible.associate { it.get("id").asText() to it.get("imageUrl").asText() }
        assertEquals(mapOf(id to image, standardId to "https://cdn.sequo.example/catalog/standard.jpg"), images)
        val home = json.readTree(request("GET", "/api/catalog/home").body())
        val shop = home.get("merchants").single { it.get("id").asText() == merchant }
        assertEquals(images, shop.get("products").associate { it.get("id").asText() to it.get("imageUrl").asText() })
        assertEquals(200, request("POST", "$endpoint/$id/deactivate", owner).statusCode())
        assertEquals(404, request("GET", image).statusCode())
    }

    @Test
    fun merchantOptionsKeepIdsAndCustomerQuoteUsesPersistedRules() {
        val merchant = "options-http-owner"
        val id = "options-http-food"
        merchants.save(MerchantRecord(id = merchant, name = "Options merchant"))
        storefronts.save(MerchantStorefrontRecord(merchantId = merchant))
        products.save(ProductRecord(id = id, merchantId = merchant, name = "Rice", category = "food", basePriceCfa = 2000))
        val owner = token(merchant)
        val other = token("other-options-merchant")
        val merchantPath = "$endpoint/$id/customizations"
        val publicPath = "/api/catalog/products/$id/customizations"
        val setup = """{"groups":[{"name":"Side","minChoices":1,"maxChoices":1,"options":[{"name":"Plantain","priceDeltaCfa":500},{"name":"Salad","priceDeltaCfa":200,"active":false}]}]}"""
        val response = request("PUT", "$merchantPath?merchantId=$merchant", owner, setup)
        assertEquals(200, response.statusCode(), response.body())
        val group = json.readTree(response.body()).single()
        val groupId = group.get("id").asText()
        val plantain = group.get("options").first { it.get("name").asText() == "Plantain" }.get("id").asText()
        val salad = group.get("options").first { it.get("name").asText() == "Salad" }.get("id").asText()
        val retried = request("PUT", "$merchantPath?merchantId=$merchant", owner, setup)
        assertEquals(200, retried.statusCode(), retried.body())
        assertEquals(groupId, json.readTree(retried.body()).single().get("id").asText())
        assertEquals(403, request("PUT", "$merchantPath?merchantId=$merchant", other, setup).statusCode())
        assertEquals(400, request("PUT", "$merchantPath?merchantId=other-options-merchant", other, setup).statusCode())
        assertEquals(403, request("PUT", publicPath, other, setup).statusCode())
        val shop = json.readTree(request("GET", "/api/catalog/home").body()).get("merchants")
            .single { it.get("id").asText() == merchant }
        assertEquals(group, shop.get("products").single().get("customizations").single())

        fun quote(options: String) = request("POST", "$publicPath/quote", body =
            """{"selections":[{"groupId":"$groupId","optionIds":$options}]}""")
        val valid = quote("""["$plantain"]""")
        assertEquals(200, valid.statusCode(), valid.body())
        assertEquals(2500, json.readTree(valid.body()).get("unitPriceCfa").asInt())
        for ((selection, message) in listOf(
            "[]" to "requires at least",
            """["unknown"]""" to "does not exist",
            """["$salad"]""" to "not currently available",
            """["$plantain","$salad"]""" to "allows at most",
        )) {
            val rejected = quote(selection)
            assertEquals(400, rejected.statusCode(), rejected.body())
            kotlin.test.assertTrue(rejected.body().contains(message), rejected.body())
        }

        val user = users.save(User(email = "options-cart@sequo.test", provider = AuthProvider.EMAIL, status = UserStatus.ACTIVE))
        val customer = jwt.generateAccessToken(UserSession(requireNotNull(user.id), user.email, AuthProvider.EMAIL))
        fun saveCart(selections: String) = request("PUT", "/api/cart/items", customer,
            """{"productId":"$id","quantity":2,"selections":$selections,"unitPriceCfa":1}""")
        fun side(optionId: String) = """[{"groupId":"$groupId","optionIds":["$optionId"]}]"""
        for (selection in listOf("[]", side("unknown"), side(salad))) {
            val rejected = saveCart(selection)
            assertEquals(400, rejected.statusCode(), rejected.body())
        }
        val emptyCart = request("GET", "/api/cart", customer)
        assertEquals(0, json.readTree(emptyCart.body()).path("totalItems").asInt())
        val added = saveCart(side(plantain))
        assertEquals(200, added.statusCode(), added.body())
        val line = json.readTree(added.body()).path("items").single()
        assertEquals(5000, line.path("lineTotalCfa").asInt())
        assertEquals(json.readTree(side(plantain)), line.path("selections"))
        assertEquals(line.path("id"), json.readTree(saveCart(side(plantain)).body()).path("items").single().path("id"))

        val edit = """{"groups":[{"id":"$groupId","name":"Side","minChoices":1,"maxChoices":1,"options":[{"id":"$plantain","name":"Plantain","priceDeltaCfa":650},{"id":"$salad","name":"Salad","priceDeltaCfa":200,"active":false}]}]}"""
        val saved = request("PUT", "$merchantPath?merchantId=$merchant", owner, edit)
        assertEquals(200, saved.statusCode(), saved.body())
        assertEquals(groupId, json.readTree(saved.body()).single().get("id").asText())
        assertEquals(2650, json.readTree(quote("""["$plantain"]""").body()).get("unitPriceCfa").asInt())
        val restored = request("GET", "/api/cart", customer)
        assertEquals(200, restored.statusCode(), restored.body())
        assertEquals(5300, json.readTree(restored.body()).path("items").single().path("lineTotalCfa").asInt())
    }

    @Test
    fun cartQuoteUsesSavedDestinationAndCurrentPricesDespiteClientOverrides() {
        val user = users.save(User(email = "cart-http@sequo.test", provider = AuthProvider.EMAIL, status = UserStatus.ACTIVE))
        val userId = requireNotNull(user.id)
        val customer = jwt.generateAccessToken(UserSession(userId, user.email, AuthProvider.EMAIL, setOf(RoleCode.CUSTOMER)))
        val address = addresses.save(CustomerAddressRecord(
            "cart-http-home", userId, CustomerAddressKind.HOME, "Home", "Fixture", "+22890000000",
            "Lomé", latitude = 0.0, longitude = Math.toDegrees(5_001.0 / 6_371_000.0),
        ))
        products.save(ProductRecord(id = "cart-http-product", name = "Basket item", basePriceCfa = 2000))
        val put = request("PUT", "/api/cart/items", customer,
            """{"productId":"cart-http-product","quantity":2,"unitPriceCfa":1,"subtotalCfa":2}""")
        assertEquals(200, put.statusCode(), put.body())
        val path = "/api/cart/quote?addressId=${address.id}&distanceKm=0&totalCfa=1"
        assertEquals(403, request("GET", path).statusCode())
        assertEquals(400, request("GET", path, token("other-cart-http", RoleCode.CUSTOMER)).statusCode())
        val quote = request("GET", path, customer)
        assertEquals(200, quote.statusCode(), quote.body())
        val quoteJson = json.readTree(quote.body())
        assertEquals(5001, quoteJson.path("distanceMeters").asInt())
        assertEquals(4000, quoteJson.path("productTotalCfa").asInt())
        assertEquals(500, quoteJson.path("deliveryFeeCfa").asInt())
        assertEquals(4500, quoteJson.path("totalCfa").asInt())
    }

    private fun upload(id: String, merchant: String, token: String, bytes: ByteArray, mime: String): HttpResponse<String> {
        val boundary = "sequo-photo-fixture"
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"camera.jpg\"\r\nContent-Type: $mime\r\n\r\n".toByteArray() +
            bytes + "\r\n--$boundary--\r\n".toByteArray()
        val path = "$endpoint/$id/photos/camera?merchantId=$merchant&deviceId=fixture-camera&capturedAt=${java.time.Instant.now()}"
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun token(merchant: String, role: RoleCode = RoleCode.MERCHANT_OWNER): String = jwt.generateAccessToken(
        UserSession("user-$merchant", null, AuthProvider.EMAIL, setOf(role), setOf(merchant))
    )

    private fun request(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.header("Content-Type", "application/json") }
        request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
