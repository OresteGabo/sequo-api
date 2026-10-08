package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
    private lateinit var authChallengeRepository: AuthChallengeRepository

    @Autowired
    private lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired
    private lateinit var userIdentityRepository: UserIdentityRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @BeforeEach
    fun cleanDatabase() {
        authRateLimiter.resetForTests()
        authChallengeRepository.deleteAll()
        userDeviceRepository.deleteAll()
        userIdentityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun whatsappOtpRequestReturns429AfterPhoneBudgetIsConsumed() {
        val request = AuthController.WhatsAppOtpRequest("+15551234567")

        repeat(3) {
            assertEquals(200, authController.requestWhatsAppOtp(request).statusCode.value())
        }

        val blocked = authController.requestWhatsAppOtp(request)
        val body = blocked.body as AuthController.RateLimitErrorResponse

        assertEquals(429, blocked.statusCode.value())
        assertNotNull(blocked.headers["Retry-After"])
        assertEquals("rate_limited", body.code)
        assertTrue(body.message.contains("Too many attempts"))
    }
}
