package dev.orestegabo.sequo_api.domain.auth

import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@Transactional
class AppleSignInChallengeTest {
    @Autowired private lateinit var service: AuthService
    @Autowired private lateinit var challenges: AuthChallengeRepository
    @MockitoBean private lateinit var apple: AppleTokenVerifier

    private fun device() = DeviceBinding("apple-test-${UUID.randomUUID()}", AppSource.SEQUO_APP, null)
    private fun mockIdentity(nonce: String) {
        val subject = UUID.randomUUID().toString()
        Mockito.`when`(apple.verify("apple-token", nonce)).thenReturn(
            SocialUser(subject, AuthProvider.APPLE, "$subject@privaterelay.appleid.com", null, null, true)
        )
    }

    @Test fun createsSessionAndConsumesChallengeOnce() {
        val device = device()
        val challenge = service.createAppleChallenge(device)
        mockIdentity(challenge.nonce)
        assertNotNull(service.loginWithSocialToken(AuthProvider.APPLE, "apple-token", device, challenge.challengeId))
        assertNotNull(challenges.findById(challenge.challengeId).orElseThrow().consumedAt)
        assertNull(service.loginWithSocialToken(AuthProvider.APPLE, "apple-token", device, challenge.challengeId))
    }

    @Test fun rejectsChallengeFromAnotherDeviceOrApp() {
        val device = device()
        val challenge = service.createAppleChallenge(device)
        mockIdentity(challenge.nonce)
        assertNull(service.loginWithSocialToken(AuthProvider.APPLE, "apple-token", device(), challenge.challengeId))
        assertNull(service.loginWithSocialToken(AuthProvider.APPLE, "apple-token", device.copy(appSource = AppSource.SEQUO_HUB), challenge.challengeId))
        assertNull(challenges.findById(challenge.challengeId).orElseThrow().consumedAt)
    }

    @Test fun rejectsExpiredChallenge() {
        val device = device()
        val challenge = challenges.save(AuthChallenge(
            purpose = AuthChallengePurpose.APPLE_SIGN_IN, subject = "apple-sign-in", challengeHash = "n".repeat(43),
            requestingDeviceId = device.deviceId, requestingAppSource = device.appSource,
            expiresAt = Instant.now().minusSeconds(1),
        ))
        mockIdentity(challenge.challengeHash)
        assertNull(service.loginWithSocialToken(AuthProvider.APPLE, "apple-token", device, challenge.id))
    }

    @Test fun invalidTokenDoesNotConsumeChallenge() {
        val device = device()
        val challenge = service.createAppleChallenge(device)
        assertNull(service.loginWithSocialToken(AuthProvider.APPLE, "invalid-token", device, challenge.challengeId))
        assertNull(challenges.findById(challenge.challengeId).orElseThrow().consumedAt)
    }

    @Test fun missingChallengeFailsClosed() {
        assertNull(service.loginWithSocialToken(AuthProvider.APPLE, "apple-token", device()))
        assertNull(apple.verify("apple-token"))
    }
}
