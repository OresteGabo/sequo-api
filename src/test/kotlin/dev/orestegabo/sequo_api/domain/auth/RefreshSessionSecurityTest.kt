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

@SpringBootTest
class RefreshSessionSecurityTest {
    @Autowired private lateinit var authService: AuthService
    @Autowired private lateinit var refreshSessionService: RefreshSessionService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var refreshSessionRepository: RefreshSessionRepository
    @Autowired private lateinit var socialIdentityRepository: SocialIdentityRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    @BeforeEach
    fun cleanDatabase() {
        refreshSessionRepository.deleteAll()
        socialIdentityRepository.deleteAll()
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

    private fun activeUser(email: String) = User(
        email = email,
        passwordHash = passwordEncoder.encode("Cobalt-Violet-47!"),
        name = "Session Test",
        provider = AuthProvider.EMAIL,
        status = UserStatus.ACTIVE,
    )
}
