package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private lateinit var socialIdentityRepository: SocialIdentityRepository

    @Autowired
    private lateinit var refreshSessionRepository: RefreshSessionRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @MockitoBean
    private lateinit var googleVerifier: GoogleTokenVerifier

    @BeforeEach
    fun cleanDatabase() {
        refreshSessionRepository.deleteAll()
        socialIdentityRepository.deleteAll()
        userRepository.deleteAll()
        Mockito.reset(googleVerifier)
    }

    @Test
    fun blankSocialTokenIsRejectedBeforeVerification() {
        assertFailsWith<IllegalArgumentException> {
            AuthController.LoginWithSocialRequest(AuthProvider.GOOGLE, " ")
        }
    }

    @Test
    fun unsupportedSocialProviderReturnsBadRequest() {
        val response = authController.loginSocial(
            AuthController.LoginWithSocialRequest(AuthProvider.APPLE, "apple-token")
        )

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun invalidGoogleTokenReturnsUnauthorized() {
        Mockito.`when`(googleVerifier.verify("bad-google-token")).thenThrow(
            InvalidGoogleTokenException(GoogleTokenRejection(reason = "invalid_signature"))
        )

        val response = authController.loginSocial(
            AuthController.LoginWithSocialRequest(AuthProvider.GOOGLE, "bad-google-token")
        )

        val body = response.body as AuthController.InvalidGoogleTokenResponse
        assertEquals(401, response.statusCode.value())
        assertEquals("invalid_google_token", body.error)
        assertEquals("invalid_signature", body.reason)
        assertEquals(0, userRepository.count())
    }

    @Test
    fun wrongAudienceGoogleTokenReturnsUnauthorized() {
        Mockito.`when`(googleVerifier.verify("wrong-audience-google-token")).thenThrow(
            InvalidGoogleTokenException(
                GoogleTokenRejection(
                    reason = "invalid_audience",
                    audience = "wrong-client.apps.googleusercontent.com",
                    issuer = "https://accounts.google.com",
                    emailVerified = true,
                    subPresent = true,
                )
            )
        )

        val response = authController.loginSocial(
            AuthController.LoginWithSocialRequest(AuthProvider.GOOGLE, "wrong-audience-google-token")
        )

        val body = response.body as AuthController.InvalidGoogleTokenResponse
        assertEquals(401, response.statusCode.value())
        assertEquals("invalid_google_token", body.error)
        assertEquals("invalid_audience", body.reason)
        assertEquals(0, userRepository.count())
    }

    @Test
    fun expiredGoogleTokenReturnsUnauthorizedReason() {
        Mockito.`when`(googleVerifier.verify("expired-google-token")).thenThrow(
            InvalidGoogleTokenException(GoogleTokenRejection(reason = "expired_token"))
        )

        val response = authController.loginSocial(
            AuthController.LoginWithSocialRequest(AuthProvider.GOOGLE, "expired-google-token")
        )

        val body = response.body as AuthController.InvalidGoogleTokenResponse
        assertEquals(401, response.statusCode.value())
        assertEquals("expired_token", body.reason)
        assertEquals(0, userRepository.count())
    }

    @Test
    fun missingGoogleEmailReturnsUnauthorizedReason() {
        Mockito.`when`(googleVerifier.verify("missing-email-google-token")).thenReturn(
            SocialUser(
                providerId = "google-123",
                provider = AuthProvider.GOOGLE,
                email = null,
                name = "Customer",
                pictureUrl = null,
                emailVerified = true,
            )
        )

        val response = authController.loginSocial(
            AuthController.LoginWithSocialRequest(AuthProvider.GOOGLE, "missing-email-google-token")
        )

        val body = response.body as AuthController.InvalidGoogleTokenResponse
        assertEquals(401, response.statusCode.value())
        assertEquals("missing_email", body.reason)
        assertEquals(0, userRepository.count())
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
    fun verifiedGoogleLoginLinksToExistingEmailPasswordAccount() {
        val existing = userRepository.save(emailUser("customer@sequo.test"))
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

        val body = response.body as AuthTokens
        val linkedIdentity = socialIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "google-123")
        assertEquals(200, response.statusCode.value())
        assertNotNull(body.accessToken)
        assertNotNull(body.refreshToken)
        assertEquals(1, userRepository.count())
        assertEquals(AuthProvider.EMAIL, userRepository.findByEmail("customer@sequo.test")?.provider)
        assertNotNull(linkedIdentity)
        assertEquals(existing.id, linkedIdentity.userId)
        assertEquals("customer@sequo.test", linkedIdentity.verifiedEmail)
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
        assertNotNull(socialIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "google-123"))
    }

    @Test
    fun validGoogleTokenLogsInExistingLinkedGoogleUser() {
        val user = userRepository.save(googleUser("linked@sequo.test"))
        socialIdentityRepository.save(
            SocialIdentity(
                userId = requireNotNull(user.id),
                provider = AuthProvider.GOOGLE,
                providerSubject = "google-linked",
                verifiedEmail = "linked@sequo.test",
            )
        )
        Mockito.`when`(googleVerifier.verify("linked-google-token")).thenReturn(
            SocialUser(
                providerId = "google-linked",
                provider = AuthProvider.GOOGLE,
                email = "linked@sequo.test",
                name = "Linked User",
                pictureUrl = null,
                emailVerified = true
            )
        )

        val tokens = authService.loginWithSocialToken(AuthProvider.GOOGLE, "linked-google-token")

        assertNotNull(tokens)
        assertEquals(1, userRepository.count())
        assertEquals(user.id, socialIdentityRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "google-linked")?.userId)
    }

    @Test
    fun refreshRouteWorksForGoogleLoginSession() {
        Mockito.`when`(googleVerifier.verify("refresh-google-token")).thenReturn(
            SocialUser(
                providerId = "google-refresh",
                provider = AuthProvider.GOOGLE,
                email = "refresh@sequo.test",
                name = "Refresh User",
                pictureUrl = null,
                emailVerified = true
            )
        )

        val login = authController.loginSocial(
            AuthController.LoginWithSocialRequest(AuthProvider.GOOGLE, "refresh-google-token")
        )
        val loginTokens = login.body as AuthTokens
        val refresh = authController.refresh(AuthController.RefreshRequest(loginTokens.refreshToken))
        val refreshedTokens = refresh.body as AuthTokens

        assertEquals(200, login.statusCode.value())
        assertEquals(200, refresh.statusCode.value())
        assertNotNull(refreshedTokens.accessToken)
        assertNotNull(refreshedTokens.refreshToken)
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

        val response = authController.loginSocial(
            AuthController.LoginWithSocialRequest(AuthProvider.GOOGLE, "unverified-google-token")
        )

        val body = response.body as AuthController.InvalidGoogleTokenResponse
        assertEquals(401, response.statusCode.value())
        assertEquals("unverified_email", body.reason)
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
