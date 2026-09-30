package dev.orestegabo.sequo_api.domain.auth

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

    @BeforeEach
    fun cleanDatabase() {
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
}
