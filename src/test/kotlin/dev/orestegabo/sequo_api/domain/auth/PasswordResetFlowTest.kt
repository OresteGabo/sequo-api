package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
class PasswordResetFlowTest {
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
    fun verifiedGoogleLoginCreatesUserIdentityAndRefreshDevice() {
        Mockito.`when`(googleVerifier.verify("new-google-token")).thenReturn(
            SocialUser(
                providerId = "google-123",
                provider = AuthProvider.GOOGLE,
                email = "Customer@Sequo.Test",
                name = "Customer",
                pictureUrl = null,
                emailVerified = true,
            )
        )

        val tokens = authService.loginWithSocialToken(AuthProvider.GOOGLE, "new-google-token", device("google-device"))
        val user = userRepository.findByEmail("customer@sequo.test")
        val identity = userIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "google-123")
        val persistedDevice = userDeviceRepository.findByDeviceId("google-device")

        assertNotNull(tokens)
        assertNotNull(user)
        assertEquals(AuthProvider.GOOGLE, user.provider)
        assertNotNull(identity)
        assertEquals(user.id, identity.userId)
        assertNotNull(persistedDevice)
        assertEquals(user.id, persistedDevice.userId)
    }

    @Test
    fun unverifiedGoogleEmailDoesNotCreateAccount() {
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

        val tokens = authService.loginWithSocialToken(AuthProvider.GOOGLE, "unverified-google-token", device("blocked-device"))

        assertNull(tokens)
        assertEquals(0, userRepository.count())
        assertEquals(0, userIdentityRepository.count())
        assertEquals(0, userDeviceRepository.count())
    }

    private fun device(id: String) = DeviceBinding(id, AppSource.SEQUO_APP, null)
}
