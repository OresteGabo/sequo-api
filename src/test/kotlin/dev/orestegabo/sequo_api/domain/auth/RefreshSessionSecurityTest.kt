package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Transactional
class RefreshSessionSecurityTest {
    @Autowired private lateinit var authService: AuthService
    @Autowired private lateinit var authController: AuthController
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var userDeviceRepository: UserDeviceRepository
    @Autowired private lateinit var userIdentityRepository: UserIdentityRepository
    @Autowired private lateinit var authChallengeRepository: AuthChallengeRepository
    @Autowired private lateinit var merchantMembershipRepository: MerchantMembershipRepository

    @MockitoBean
    private lateinit var googleVerifier: GoogleTokenVerifier

    @BeforeEach
    fun cleanDatabase() {
        Mockito.reset(googleVerifier)
        authChallengeRepository.deleteAll()
        userDeviceRepository.deleteAll()
        userIdentityRepository.deleteAll()
        merchantMembershipRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun refreshRotatesTokenAndRejectsThePreviousToken() {
        val first = loginWithGoogle("rotation@sequo.test", "google-rotation", "rotation-device")

        assertEquals(1, first.refreshToken.split('.').size)
        val second = requireNotNull(authService.refreshTokens(first.refreshToken, "rotation-device"))

        assertNotEquals(first.refreshToken, second.refreshToken)
        assertNotNull(authService.refreshTokens(second.refreshToken, "rotation-device"))
        assertNull(authService.refreshTokens(first.refreshToken, "rotation-device"))
    }

    @Test
    fun accessTokensCarryThePersistedDeviceSessionId() {
        val first = loginWithGoogle("session-claim@sequo.test", "google-session", "session-device")
        val firstDevice = requireNotNull(userDeviceRepository.findByDeviceId("session-device"))
        val firstAccessSession = requireNotNull(jwtService.parseAccessToken(first.accessToken))

        assertEquals(firstDevice.id, firstAccessSession.sessionId)

        val second = requireNotNull(authService.refreshTokens(first.refreshToken, "session-device"))
        val secondDevice = requireNotNull(userDeviceRepository.findByDeviceId("session-device"))
        val secondAccessSession = requireNotNull(jwtService.parseAccessToken(second.accessToken))

        assertEquals(secondDevice.id, secondAccessSession.sessionId)
        assertEquals(firstDevice.id, secondDevice.id)
    }

    @Test
    fun accessTokensCarryPersistedRolesAndMerchantMembershipScopes() {
        val user = userRepository.save(
            User(
                email = "merchant-token@sequo.test",
                name = "Session Test",
                provider = AuthProvider.GOOGLE,
                roles = mutableSetOf(RoleCode.CUSTOMER, RoleCode.MERCHANT_STAFF),
            )
        )
        userIdentityRepository.save(
            UserIdentity(
                userId = requireNotNull(user.id),
                provider = AuthProvider.GOOGLE,
                providerUserId = "google-merchant-token",
            )
        )
        merchantMembershipRepository.save(
            MerchantMembership(
                userId = requireNotNull(user.id),
                merchantId = "merchant-token-scope",
                role = RoleCode.MERCHANT_STAFF,
            )
        )
        Mockito.`when`(googleVerifier.verify("google-merchant-token")).thenReturn(
            socialUser("merchant-token@sequo.test", "google-merchant-token")
        )

        val tokens = requireNotNull(authService.loginWithSocialToken(AuthProvider.GOOGLE, "google-merchant-token", device("merchant-device")))
        val session = requireNotNull(jwtService.parseAccessToken(tokens.accessToken))

        assertEquals(setOf(RoleCode.CUSTOMER, RoleCode.MERCHANT_STAFF), session.roles)
        assertEquals(setOf("merchant-token-scope"), session.merchantScopeIds)
    }

    @Test
    fun logoutRevokesTheRefreshSessionButIsIdempotent() {
        val tokens = loginWithGoogle("logout@sequo.test", "google-logout", "logout-device")

        authService.logout(tokens.refreshToken)
        authService.logout(tokens.refreshToken)

        assertNull(authService.refreshTokens(tokens.refreshToken, "logout-device"))
        assertEquals(1, userDeviceRepository.count())
    }

    @Test
    fun logoutAllRevokesEveryActiveRefreshSession() {
        val first = loginWithGoogle("logout-all@sequo.test", "google-logout-all", "logout-all-first")
        val user = requireNotNull(userRepository.findByEmail("logout-all@sequo.test"))
        Mockito.`when`(googleVerifier.verify("google-logout-all-second")).thenReturn(
            socialUser("logout-all@sequo.test", "google-logout-all")
        )
        val second = requireNotNull(authService.loginWithSocialToken(AuthProvider.GOOGLE, "google-logout-all-second", device("logout-all-second")))

        authService.logoutAll(requireNotNull(user.id))

        assertNull(authService.refreshTokens(first.refreshToken, "logout-all-first"))
        assertNull(authService.refreshTokens(second.refreshToken, "logout-all-second"))
        assertEquals(
            0,
            userDeviceRepository.findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(requireNotNull(user.id), java.time.Instant.now()).size,
        )
    }

    @Test
    fun sessionRevocationIsScopedToTheOwningUser() {
        val firstToken = loginWithGoogle("owner-one@sequo.test", "google-owner-one", "owner-one-device")
        val first = requireNotNull(userRepository.findByEmail("owner-one@sequo.test"))
        val secondToken = loginWithGoogle("owner-two@sequo.test", "google-owner-two", "owner-two-device")
        val secondDevice = requireNotNull(userDeviceRepository.findByDeviceId("owner-two-device"))

        assertFalse(authService.revokeSession(requireNotNull(secondDevice.id), requireNotNull(first.id)))
        assertNotNull(authService.refreshTokens(firstToken.refreshToken, "owner-one-device"))
        assertNotNull(authService.refreshTokens(secondToken.refreshToken, "owner-two-device"))
    }

    @Test
    fun refreshAndLogoutRejectUnreasonablySizedTokens() {
        assertFailsWith<IllegalArgumentException> { AuthController.RefreshRequest("short", "device") }
        assertFailsWith<IllegalArgumentException> { AuthController.LogoutRequest("x".repeat(513)) }
        assertNull(authService.refreshTokens("short", "device"))
        assertNull(authService.refreshTokens("x".repeat(513), "device"))
    }

    @Test
    fun sessionEndpointsReturnMetadataWithoutTokenMaterial() {
        loginWithGoogle("metadata@sequo.test", "google-metadata", "metadata-device")
        val user = requireNotNull(userRepository.findByEmail("metadata@sequo.test"))

        val response = authController.sessions(requireNotNull(user.id))

        assertEquals(200, response.statusCode.value())
        val session = (response.body as List<*>).single() as AuthController.SessionResponse
        assertTrue(session.id.isNotBlank())
        assertTrue(session.expiresAt.isAfter(session.createdAt))
    }

    @Test
    fun sessionEndpointsRejectMissingIdentityAndUnknownSession() {
        assertEquals(401, authController.sessions(null).statusCode.value())
        assertEquals(401, authController.revokeSession("missing", null).statusCode.value())

        val user = userRepository.save(User(email = "unknown-session@sequo.test", provider = AuthProvider.GOOGLE))
        assertEquals(404, authController.revokeSession("missing", requireNotNull(user.id)).statusCode.value())
    }

    private fun loginWithGoogle(email: String, providerId: String, deviceId: String): AuthTokens {
        val token = "token-$providerId"
        Mockito.`when`(googleVerifier.verify(token)).thenReturn(socialUser(email, providerId))
        return requireNotNull(authService.loginWithSocialToken(AuthProvider.GOOGLE, token, device(deviceId)))
    }

    private fun socialUser(email: String, providerId: String) =
        SocialUser(
            providerId = providerId,
            provider = AuthProvider.GOOGLE,
            email = email,
            name = "Session Test",
            pictureUrl = null,
            emailVerified = true,
        )

    private fun device(id: String) = DeviceBinding(id, AppSource.SEQUO_APP, null)
}
