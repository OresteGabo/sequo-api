package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
class AuthProviderCollisionTest {
    @Autowired
    private lateinit var authController: AuthController

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userIdentityRepository: UserIdentityRepository

    @Autowired
    private lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired
    private lateinit var authChallengeRepository: AuthChallengeRepository

    @MockitoBean
    private lateinit var googleVerifier: GoogleTokenVerifier

    @BeforeEach
    fun cleanDatabase() {
        Mockito.reset(googleVerifier)
        authChallengeRepository.deleteAll()
        userDeviceRepository.deleteAll()
        userIdentityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun blankSocialTokenIsRejectedBeforeVerification() {
        val response = authController.loginSocial(
            AuthProvider.GOOGLE,
            AuthController.SocialLoginRequest(" ", deviceRequest("blank-token-device")),
        )

        assertEquals(400, response.statusCode.value())
        assertEquals("invalid_auth_request", (response.body as AuthErrorResponse).code)
    }

    @Test
    fun unsupportedSocialProviderReturnsUnauthorized() {
        val response = authController.loginSocial(
            AuthProvider.EMAIL,
            AuthController.SocialLoginRequest("email-token", deviceRequest("email-provider-device")),
        )

        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun invalidGoogleTokenReturnsUnauthorizedWithReason() {
        Mockito.`when`(googleVerifier.verify("bad-google-token")).thenThrow(
            InvalidGoogleTokenException(GoogleTokenRejection(reason = "invalid_signature"))
        )

        val response = authController.loginSocial(
            AuthProvider.GOOGLE,
            AuthController.SocialLoginRequest("bad-google-token", deviceRequest("bad-google-device")),
        )

        val body = response.body as AuthController.InvalidGoogleTokenResponse
        assertEquals(401, response.statusCode.value())
        assertEquals("invalid_google_token", body.error)
        assertEquals("invalid_signature", body.reason)
        assertEquals(0, userRepository.count())
    }

    @Test
    fun wrongAudienceGoogleTokenReturnsUnauthorizedWithDebugFields() {
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
            AuthProvider.GOOGLE,
            AuthController.SocialLoginRequest("wrong-audience-google-token", deviceRequest("wrong-audience-device")),
        )

        val body = response.body as AuthController.InvalidGoogleTokenResponse
        assertEquals(401, response.statusCode.value())
        assertEquals("invalid_audience", body.reason)
        assertEquals("wrong-client.apps.googleusercontent.com", body.audience)
        assertEquals("https://accounts.google.com", body.issuer)
    }

    @Test
    fun verifiedGoogleLoginLinksToExistingEmailAccount() {
        val existing = userRepository.save(User(email = "customer@sequo.test", name = "Customer", provider = AuthProvider.EMAIL))
        Mockito.`when`(googleVerifier.verify("google-token")).thenReturn(
            SocialUser(
                providerId = "google-123",
                provider = AuthProvider.GOOGLE,
                email = "customer@sequo.test",
                name = "Customer",
                pictureUrl = null,
                emailVerified = true,
            )
        )

        val response = authController.loginSocial(
            AuthProvider.GOOGLE,
            AuthController.SocialLoginRequest("google-token", deviceRequest("linked-google-device")),
        )

        val body = response.body as AuthTokens
        val linkedIdentity = userIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "google-123")
        assertEquals(200, response.statusCode.value())
        assertNotNull(body.accessToken)
        assertNotNull(body.refreshToken)
        assertEquals(1, userRepository.count())
        assertEquals(existing.id, linkedIdentity?.userId)
    }

    @Test
    fun validGoogleTokenLogsInExistingLinkedGoogleUser() {
        val user = userRepository.save(User(email = "linked@sequo.test", name = "Linked User", provider = AuthProvider.GOOGLE))
        userIdentityRepository.save(
            UserIdentity(
                userId = requireNotNull(user.id),
                provider = AuthProvider.GOOGLE,
                providerUserId = "google-linked",
            )
        )
        Mockito.`when`(googleVerifier.verify("linked-google-token")).thenReturn(
            SocialUser(
                providerId = "google-linked",
                provider = AuthProvider.GOOGLE,
                email = "linked@sequo.test",
                name = "Linked User",
                pictureUrl = null,
                emailVerified = true,
            )
        )

        val tokens = authService.loginWithSocialToken(AuthProvider.GOOGLE, "linked-google-token", device("existing-google-device"))

        assertNotNull(tokens)
        assertEquals(1, userRepository.count())
        assertEquals(user.id, userIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "google-linked")?.userId)
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
                emailVerified = true,
            )
        )

        val login = authController.loginSocial(
            AuthProvider.GOOGLE,
            AuthController.SocialLoginRequest("refresh-google-token", deviceRequest("refresh-device")),
        )
        val loginTokens = login.body as AuthTokens
        val refresh = authController.refresh(AuthController.RefreshRequest(loginTokens.refreshToken, "refresh-device"))
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
                emailVerified = false,
            )
        )

        val response = authController.loginSocial(
            AuthProvider.GOOGLE,
            AuthController.SocialLoginRequest("unverified-google-token", deviceRequest("unverified-device")),
        )

        assertEquals(401, response.statusCode.value())
        assertEquals(0, userRepository.count())
        assertEquals(0, userIdentityRepository.count())
    }

    private fun deviceRequest(id: String) = AuthController.DeviceRequest(id, AppSource.SEQUO_APP)

    private fun device(id: String) = DeviceBinding(id, AppSource.SEQUO_APP, null)
}
