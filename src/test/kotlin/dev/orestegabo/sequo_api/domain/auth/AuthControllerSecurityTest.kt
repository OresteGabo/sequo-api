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
class AuthControllerSecurityTest {

    @Autowired
    private lateinit var authController: AuthController

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @BeforeEach
    fun cleanDatabase() {
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
        assertNotNull(response.body)
        assertTrue(response.body!!["message"]!!.contains("If the account exists"))
        assertTrue("token" !in response.body!!.keys)
    }

    @Test
    fun forgotPasswordResponseIsGenericForUnknownAccount() {
        val response = authController.forgotPassword(
            AuthController.ForgotPasswordRequest("missing@sequo.test")
        )

        assertEquals(200, response.statusCode.value())
        assertNotNull(response.body)
        assertTrue(response.body!!["message"]!!.contains("If the account exists"))
        assertTrue("token" !in response.body!!.keys)
    }
}
