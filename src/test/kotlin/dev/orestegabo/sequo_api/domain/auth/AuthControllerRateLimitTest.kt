package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
class AuthControllerRateLimitTest {
    @Autowired
    private lateinit var authController: AuthController

    @Autowired
    private lateinit var authRateLimiter: AuthRateLimiter

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var socialIdentityRepository: SocialIdentityRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @BeforeEach
    fun cleanDatabase() {
        authRateLimiter.resetForTests()
        socialIdentityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun forgotPasswordReturns429AfterEmailBudgetIsConsumed() {
        userRepository.save(
            User(
                email = "rate-limit@sequo.test",
                passwordHash = passwordEncoder.encode("OldPassword2026!"),
                name = "Rate Limited Customer",
                provider = AuthProvider.EMAIL,
            )
        )
        val request = AuthController.ForgotPasswordRequest("rate-limit@sequo.test")

        repeat(3) {
            assertEquals(200, authController.forgotPassword(request).statusCode.value())
        }

        val blocked = authController.forgotPassword(request)

        assertEquals(429, blocked.statusCode.value())
        assertNotNull(blocked.headers["Retry-After"])
        assertEquals("rate_limited", blocked.body?.get("code"))
        assertTrue(blocked.body?.get("message")!!.contains("Too many attempts"))
    }
}
