package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.crypto.password.PasswordEncoder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerSecurityTest {

    @Autowired
    private lateinit var authController: AuthController

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var socialIdentityRepository: SocialIdentityRepository

    @Autowired
    private lateinit var refreshSessionRepository: RefreshSessionRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var productRepository: CommerceProductRepository

    @BeforeEach
    fun cleanDatabase() {
        productRepository.deleteAll()
        refreshSessionRepository.deleteAll()
        socialIdentityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun forgotPasswordResponseDoesNotExposeResetTokenForKnownAccount() {
        userRepository.save(
            User(
                email = "customer@sequo.test",
                passwordHash = passwordEncoder.encode("OldPassword2026!"),
                name = "Customer",
                provider = AuthProvider.EMAIL
            )
        )

        val response = authController.forgotPassword(
            AuthController.ForgotPasswordRequest("customer@sequo.test")
        )

        assertEquals(200, response.statusCode.value())
        val body = requireNotNull(response.body)
        assertTrue(requireNotNull(body["message"]).contains("If the account exists"))
        assertTrue("token" !in body.keys)
    }

    @Test
    fun forgotPasswordResponseIsGenericForUnknownAccount() {
        val response = authController.forgotPassword(
            AuthController.ForgotPasswordRequest("missing@sequo.test")
        )

        assertEquals(200, response.statusCode.value())
        val body = requireNotNull(response.body)
        assertTrue(requireNotNull(body["message"]).contains("If the account exists"))
        assertTrue("token" !in body.keys)
    }

    @Test
    fun forgotPasswordRejectsMalformedEmailBeforeGenericResponse() {
        val response = authController.forgotPassword(
            AuthController.ForgotPasswordRequest("not-an-email")
        )

        assertEquals(400, response.statusCode.value())
        val body = requireNotNull(response.body)
        assertEquals("invalid_auth_request", body["code"])
    }

    @Test
    fun signUpRejectsMalformedEmail() {
        val response = authController.signUp(
            AuthController.SignUpRequest(
                email = "not-an-email",
                password = "OldPassword2026!",
                name = "Malformed Email",
            )
        )

        assertEquals(400, response.statusCode.value())
        val body = response.body as AuthErrorResponse
        assertEquals("invalid_auth_request", body.code)
    }

    @Test
    fun loginRejectsMalformedEmail() {
        val response = authController.login(
            AuthController.LoginWithEmailRequest(
                email = "not-an-email",
                password = "OldPassword2026!",
            )
        )

        assertEquals(400, response.statusCode.value())
        val body = response.body as AuthErrorResponse
        assertEquals("invalid_auth_request", body.code)
    }

    @Test
    fun currentUserReturnsOnlySafeProfileFields() {
        val user = userRepository.save(
            User(
                email = "profile@sequo.test",
                passwordHash = passwordEncoder.encode("OldPassword2026!"),
                name = "Profile User",
                provider = AuthProvider.EMAIL,
            )
        )

        val response = authController.currentUser(requireNotNull(user.id))

        assertEquals(200, response.statusCode.value())
        val profile = response.body as AuthController.CurrentUserResponse
        assertEquals(user.id, profile.id)
        assertEquals(user.email, profile.email)
        assertEquals(user.name, profile.name)
        assertEquals(user.provider, profile.provider)
        assertEquals(user.status, profile.status)
    }

    @Test
    fun actuatorHealthIsPublicForContainerHealthchecks() {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/actuator/health"))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"status\":\"UP\""))
    }

    @Test
    fun publicCatalogDiscoveryEndpointsDoNotRequireAuthentication() {
        productRepository.save(
            ProductRecord(
                id = "public-active-product",
                name = "Active meal",
                status = CatalogProductStatus.ACTIVE,
                category = "food",
                basePriceCfa = 2500,
            )
        )

        val client = HttpClient.newHttpClient()
        val endpoints = listOf(
            "/api/catalog/home",
            "/api/catalog/categories",
            "/api/catalog/merchants",
            "/api/catalog/products",
            "/api/catalog/products/public-active-product/related",
            "/api/catalog/products/public-active-product/customizations",
            "/api/subscriptions/plans",
            "/api/payments/providers",
        )

        endpoints.forEach { path ->
            val response = client.send(get(path), HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode(), "$path should be public")
        }
    }

    @Test
    fun anonymousCatalogAccessCannotReadArchivedProductsOrMutateCatalogAndCart() {
        productRepository.saveAll(
            listOf(
                ProductRecord(
                    id = "public-active-product",
                    name = "Active meal",
                    status = CatalogProductStatus.ACTIVE,
                    category = "food",
                    basePriceCfa = 2500,
                ),
                ProductRecord(
                    id = "archived-product",
                    name = "Archived meal",
                    status = CatalogProductStatus.ARCHIVED,
                    category = "food",
                    basePriceCfa = 2500,
                ),
            )
        )

        val client = HttpClient.newHttpClient()
        val publicProducts = client.send(
            get("/api/catalog/products?includeArchived=true"),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, publicProducts.statusCode())
        assertTrue(publicProducts.body().contains("Active meal"))
        assertTrue(!publicProducts.body().contains("Archived meal"))

        val createProduct = client.send(
            post("/api/catalog/products", """{"name":"Anonymous product"}"""),
            HttpResponse.BodyHandlers.ofString(),
        )
        val putCart = client.send(
            put("/api/cart/items", """{"productId":"public-active-product","quantity":1}"""),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(403, createProduct.statusCode())
        assertEquals(403, putCart.statusCode())
    }

    private fun get(path: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .GET()
            .build()

    private fun post(path: String, body: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun put(path: String, body: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()
}
