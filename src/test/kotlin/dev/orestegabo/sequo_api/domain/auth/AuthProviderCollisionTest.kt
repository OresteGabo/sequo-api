package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
class AuthProviderCollisionTest {

    @Autowired
    private lateinit var authController: AuthController

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @MockitoBean
    private lateinit var googleVerifier: GoogleTokenVerifier

    @BeforeEach
    fun cleanDatabase() {
        userRepository.deleteAll()
        Mockito.reset(googleVerifier)
    }

    @Test
    fun emailLoginForGoogleAccountReturnsGoogleProviderHint() {
        userRepository.save(googleUser("customer@sequo.test"))

        val response = authController.login(
            AuthController.LoginWithEmailRequest(
                email = "customer@sequo.test",
                password = "Cobalt-Violet-47!"
            )
        )

        val body = response.body as AuthErrorResponse
        assertEquals(409, response.statusCode.value())
        assertEquals("auth_provider_required", body.code)
        assertEquals(AuthProvider.GOOGLE, body.requiredProvider)
    }

    @Test
    fun emailSignupForGoogleAccountReturnsGoogleProviderHint() {
        userRepository.save(googleUser("customer@sequo.test"))

        val response = authController.signUp(
            AuthController.SignUpRequest(
                email = "CUSTOMER@SEQUO.TEST",
                password = "Cobalt-Violet-47!",
                name = "Customer"
            )
        )

        val body = response.body as AuthErrorResponse
        assertEquals(409, response.statusCode.value())
        assertEquals("auth_provider_required", body.code)
        assertEquals(AuthProvider.GOOGLE, body.requiredProvider)
    }

    @Test
    fun googleLoginForEmailPasswordAccountRequiresExplicitLinking() {
        userRepository.save(emailUser("customer@sequo.test"))
        Mockito.`when`(googleVerifier.verify("google-token")).thenReturn(
            SocialUser(
                providerId = "google-123",
                provider = AuthProvider.GOOGLE,
                email = "customer@sequo.test",
                name = "Customer",
                pictureUrl = null,
                emailVerified = true
            )
        )

        val response = authController.loginSocial(
            AuthController.LoginWithSocialRequest(AuthProvider.GOOGLE, "google-token")
        )

        val body = response.body as AuthErrorResponse
        assertEquals(409, response.statusCode.value())
        assertEquals("account_link_required", body.code)
        assertEquals(AuthProvider.EMAIL, body.requiredProvider)
        assertEquals(AuthProvider.GOOGLE, body.attemptedProvider)
        assertEquals(1, userRepository.count())
        assertEquals(AuthProvider.EMAIL, userRepository.findByEmail("customer@sequo.test")?.provider)
    }

    @Test
    fun verifiedGoogleLoginCreatesNewGoogleAccountWhenEmailIsUnused() {
        Mockito.`when`(googleVerifier.verify("new-google-token")).thenReturn(
            SocialUser(
                providerId = "google-123",
                provider = AuthProvider.GOOGLE,
                email = "Customer@Sequo.Test",
                name = "Customer",
                pictureUrl = null,
                emailVerified = true
            )
        )

        val tokens = authService.loginWithSocialToken(AuthProvider.GOOGLE, "new-google-token")
        val user = userRepository.findByEmail("customer@sequo.test")

        assertNotNull(tokens)
        assertNotNull(user)
        assertEquals(AuthProvider.GOOGLE, user.provider)
        assertEquals("google-123", user.providerId)
    }

    @Test
    fun unverifiedGoogleEmailDoesNotCreateOrLinkAccount() {
        Mockito.`when`(googleVerifier.verify("unverified-google-token")).thenReturn(
            SocialUser(
                providerId = "google-123",
                provider = AuthProvider.GOOGLE,
                email = "customer@sequo.test",
                name = "Customer",
                pictureUrl = null,
                emailVerified = false
            )
        )

        val tokens = authService.loginWithSocialToken(AuthProvider.GOOGLE, "unverified-google-token")

        assertNull(tokens)
        assertEquals(0, userRepository.count())
    }

    private fun googleUser(email: String): User =
        User(
            email = email,
            name = "Customer",
            provider = AuthProvider.GOOGLE,
            providerId = "google-existing"
        )

    private fun emailUser(email: String): User =
        User(
            email = email,
            passwordHash = passwordEncoder.encode("Cobalt-Violet-47!"),
            name = "Customer",
            provider = AuthProvider.EMAIL
        )
}
