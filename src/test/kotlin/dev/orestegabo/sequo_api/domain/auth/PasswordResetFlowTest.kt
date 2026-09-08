package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class PasswordResetFlowTest {

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var socialIdentityRepository: SocialIdentityRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var tokenService: PasswordResetTokenService

    @BeforeEach
    fun cleanDatabase() {
        socialIdentityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun forgotPasswordStoresOnlyTokenHash() {
        userRepository.save(emailUser("customer@sequo.test"))

        authService.forgotPassword("customer@sequo.test")

        val user = userRepository.findByEmail("customer@sequo.test")
        assertNotNull(user?.resetTokenHash)
        assertTrue(user.resetTokenHash!!.length >= 40)
        assertNotNull(user.resetTokenExpiry)
    }

    @Test
    fun forgotPasswordDoesNotCreateTokenForUnknownAccount() {
        authService.forgotPassword("missing@sequo.test")

        assertTrue(userRepository.findAll().isEmpty())
    }

    @Test
    fun resetPasswordAcceptsValidTokenAndClearsItAfterUse() {
        val token = tokenService.generate()
        val user = emailUser("customer@sequo.test").apply {
            resetTokenHash = token.tokenHash
            resetTokenExpiry = Instant.now().plusSeconds(120)
        }
        userRepository.save(user)

        val success = authService.resetPassword(
            AuthController.ResetPasswordRequest(
                token = token.rawToken,
                newPassword = "Cobalt-Violet-47!"
            )
        )

        val updated = userRepository.findByEmail("customer@sequo.test")
        assertTrue(success)
        assertNotNull(updated)
        assertNull(updated.resetTokenHash)
        assertNull(updated.resetTokenExpiry)
        assertTrue(passwordEncoder.matches("Cobalt-Violet-47!", updated.passwordHash))
    }

    @Test
    fun resetPasswordRejectsExpiredToken() {
        val token = tokenService.generate()
        val user = emailUser("customer@sequo.test").apply {
            resetTokenHash = token.tokenHash
            resetTokenExpiry = Instant.now().minusSeconds(1)
        }
        userRepository.save(user)

        val success = authService.resetPassword(
            AuthController.ResetPasswordRequest(
                token = token.rawToken,
                newPassword = "Cobalt-Violet-47!"
            )
        )

        assertFalse(success)
    }

    @Test
    fun resetPasswordRejectsMissingExpiry() {
        val token = tokenService.generate()
        val user = emailUser("customer@sequo.test").apply {
            resetTokenHash = token.tokenHash
            resetTokenExpiry = null
        }
        userRepository.save(user)

        val success = authService.resetPassword(
            AuthController.ResetPasswordRequest(
                token = token.rawToken,
                newPassword = "Cobalt-Violet-47!"
            )
        )

        assertFalse(success)
    }

    private fun emailUser(email: String): User =
        User(
            email = email,
            passwordHash = passwordEncoder.encode("OldPassword2026!"),
            name = "Customer",
            provider = AuthProvider.EMAIL
        )
}
