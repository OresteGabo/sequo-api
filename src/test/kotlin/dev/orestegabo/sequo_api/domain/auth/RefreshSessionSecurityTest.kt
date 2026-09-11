package dev.orestegabo.sequo_api.domain.auth

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RefreshSessionSecurityTest {
    @Autowired private lateinit var authService: AuthService
    @Autowired private lateinit var authController: AuthController
    @Autowired private lateinit var refreshSessionService: RefreshSessionService
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var refreshSessionRepository: RefreshSessionRepository
    @Autowired private lateinit var socialIdentityRepository: SocialIdentityRepository
    @Autowired private lateinit var merchantMembershipRepository: MerchantMembershipRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    @BeforeEach
    fun cleanDatabase() {
        refreshSessionRepository.deleteAll()
        socialIdentityRepository.deleteAll()
        merchantMembershipRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun refreshRotatesTokenAndRejectsThePreviousToken() {
        val user = userRepository.save(activeUser("rotation@sequo.test"))
        val first = requireNotNull(authService.login(AuthController.LoginWithEmailRequest(user.email, "Cobalt-Violet-47!")))

        assertEquals(1, first.refreshToken.split('.').size)
        val second = requireNotNull(authService.refreshTokens(first.refreshToken))

        assertNotEquals(first.refreshToken, second.refreshToken)
        assertNotNull(authService.refreshTokens(second.refreshToken))
        assertNull(authService.refreshTokens(first.refreshToken))
    }

    @Test
    fun accessTokensCarryThePersistedRefreshSessionId() {
        val user = userRepository.save(activeUser("session-claim@sequo.test"))
        val first = requireNotNull(authService.login(AuthController.LoginWithEmailRequest(user.email, "Cobalt-Violet-47!")))
        val firstRefreshSession = requireNotNull(refreshSessionService.findByToken(first.refreshToken))
        val firstAccessSession = requireNotNull(jwtService.parseAccessToken(first.accessToken))

        assertEquals(firstRefreshSession.id, firstAccessSession.sessionId)

        val second = requireNotNull(authService.refreshTokens(first.refreshToken))
        val secondRefreshSession = requireNotNull(refreshSessionService.findByToken(second.refreshToken))
        val secondAccessSession = requireNotNull(jwtService.parseAccessToken(second.accessToken))

        assertEquals(secondRefreshSession.id, secondAccessSession.sessionId)
        assertNotEquals(firstRefreshSession.id, secondRefreshSession.id)
    }

    @Test
    fun accessTokensCarryPersistedRolesAndMerchantMembershipScopes() {
        val user = userRepository.save(
            activeUser("merchant-token@sequo.test").apply {
                roles = mutableSetOf(RoleCode.CUSTOMER, RoleCode.MERCHANT_STAFF)
            }
        )
        merchantMembershipRepository.save(
            MerchantMembership(
                userId = requireNotNull(user.id),
                merchantId = "merchant-token-scope",
                role = RoleCode.MERCHANT_STAFF,
            )
        )

        val tokens = requireNotNull(authService.login(AuthController.LoginWithEmailRequest(user.email, "Cobalt-Violet-47!")))
        val session = requireNotNull(jwtService.parseAccessToken(tokens.accessToken))

        assertEquals(setOf(RoleCode.CUSTOMER, RoleCode.MERCHANT_STAFF), session.roles)
        assertEquals(setOf("merchant-token-scope"), session.merchantScopeIds)
    }

    @Test
    fun logoutRevokesTheRefreshSessionButIsIdempotent() {
        val user = userRepository.save(activeUser("logout@sequo.test"))
        val tokens = requireNotNull(authService.login(AuthController.LoginWithEmailRequest(user.email, "Cobalt-Violet-47!")))

        authService.logout(tokens.refreshToken)
        authService.logout(tokens.refreshToken)

        assertNull(authService.refreshTokens(tokens.refreshToken))
        assertEquals(1, refreshSessionRepository.count())
    }

    @Test
    fun logoutAllRevokesEveryActiveRefreshSession() {
        val user = userRepository.save(activeUser("logout-all@sequo.test"))
        val request = AuthController.LoginWithEmailRequest(user.email, "Cobalt-Violet-47!")
        val first = requireNotNull(authService.login(request))
        val second = requireNotNull(authService.login(request))

        authService.logoutAll(requireNotNull(user.id))

        assertNull(authService.refreshTokens(first.refreshToken))
        assertNull(authService.refreshTokens(second.refreshToken))
        assertEquals(0, refreshSessionRepository.findAllByUserIdAndRevokedAtIsNull(requireNotNull(user.id)).size)
    }

    @Test
    fun sessionRevocationIsScopedToTheOwningUser() {
        val first = userRepository.save(activeUser("owner-one@sequo.test"))
        val second = userRepository.save(activeUser("owner-two@sequo.test"))
        val firstToken = requireNotNull(authService.login(AuthController.LoginWithEmailRequest(first.email, "Cobalt-Violet-47!")))
        val secondToken = requireNotNull(authService.login(AuthController.LoginWithEmailRequest(second.email, "Cobalt-Violet-47!")))
        val secondSession = requireNotNull(refreshSessionService.findByToken(secondToken.refreshToken))

        assertFalse(authService.revokeSession(requireNotNull(secondSession.id), requireNotNull(first.id)))
        assertNotNull(authService.refreshTokens(firstToken.refreshToken))
        assertNotNull(authService.refreshTokens(secondToken.refreshToken))
    }

    @Test
    fun refreshAndLogoutRejectUnreasonablySizedTokens() {
        assertFailsWith<IllegalArgumentException> { AuthController.RefreshRequest("short") }
        assertFailsWith<IllegalArgumentException> { AuthController.LogoutRequest("x".repeat(513)) }
        assertNull(refreshSessionService.findByToken("short"))
        assertNull(refreshSessionService.findByToken("x".repeat(513)))
    }

    @Test
    fun sessionEndpointsReturnMetadataWithoutTokenMaterial() {
        val user = userRepository.save(activeUser("metadata@sequo.test"))
        authService.login(AuthController.LoginWithEmailRequest(user.email, "Cobalt-Violet-47!"))

        val response = authController.sessions(requireNotNull(user.id))

        assertEquals(200, response.statusCode.value())
        val session = (response.body as List<*>).single() as AuthController.SessionResponse
        assertEquals(requireNotNull(user.id), user.id)
        assertTrue(session.id.isNotBlank())
        assertTrue(session.expiresAt.isAfter(session.createdAt))
    }

    @Test
    fun sessionEndpointsRejectMissingIdentityAndUnknownSession() {
        assertEquals(401, authController.sessions(null).statusCode.value())
        assertEquals(401, authController.revokeSession("missing", null).statusCode.value())

        val user = userRepository.save(activeUser("unknown-session@sequo.test"))
        assertEquals(404, authController.revokeSession("missing", requireNotNull(user.id)).statusCode.value())
    }

    private fun activeUser(email: String) = User(
        email = email,
        passwordHash = passwordEncoder.encode("Cobalt-Violet-47!"),
        name = "Session Test",
        provider = AuthProvider.EMAIL,
        status = UserStatus.ACTIVE,
    )
}
