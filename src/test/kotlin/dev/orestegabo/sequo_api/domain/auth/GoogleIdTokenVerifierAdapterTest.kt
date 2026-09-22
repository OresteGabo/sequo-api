package dev.orestegabo.sequo_api.domain.auth

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleIdTokenVerifierAdapterTest {
    private val objectMapper = ObjectMapper()
    private val androidClientId = "543119759762-f4s16bdo6sjhqsbk15na7df96dib6kmo.apps.googleusercontent.com"
    private val iosClientId = "543119759762-ehcnb5lpi883c94457ogrgqsd0nshde2.apps.googleusercontent.com"

    @Test
    fun unknownAudienceIsRejectedBeforeSignatureVerification() {
        val verifier = verifier()
        val exception = assertFailsWith<InvalidGoogleTokenException> {
            verifier.verifyAccount(
                unsignedGoogleToken(
                    audience = "unknown-client.apps.googleusercontent.com",
                    expiresAt = Instant.now().plusSeconds(300).epochSecond,
                )
            )
        }

        assertEquals("invalid_audience", exception.rejection.reason)
        assertEquals("unknown-client.apps.googleusercontent.com", exception.rejection.audience)
        assertEquals("https://accounts.google.com", exception.rejection.issuer)
        assertEquals(true, exception.rejection.emailVerified)
        assertEquals(true, exception.rejection.subPresent)
    }

    @Test
    fun expiredAndroidAudienceTokenReportsExpiredToken() {
        val verifier = verifier()
        val exception = assertFailsWith<InvalidGoogleTokenException> {
            verifier.verifyAccount(
                unsignedGoogleToken(
                    audience = androidClientId,
                    expiresAt = Instant.now().minusSeconds(30).epochSecond,
                )
            )
        }

        assertEquals("expired_token", exception.rejection.reason)
        assertEquals(androidClientId, exception.rejection.audience)
    }

    @Test
    fun missingEmailForIosAudienceReportsMissingEmail() {
        val verifier = verifier()
        val exception = assertFailsWith<InvalidGoogleTokenException> {
            verifier.verifyAccount(
                unsignedGoogleToken(
                    audience = iosClientId,
                    expiresAt = Instant.now().plusSeconds(300).epochSecond,
                    email = null,
                )
            )
        }

        assertEquals("missing_email", exception.rejection.reason)
        assertEquals(iosClientId, exception.rejection.audience)
    }

    @Test
    fun unverifiedEmailForIosAudienceReportsUnverifiedEmail() {
        val verifier = verifier()
        val exception = assertFailsWith<InvalidGoogleTokenException> {
            verifier.verifyAccount(
                unsignedGoogleToken(
                    audience = iosClientId,
                    expiresAt = Instant.now().plusSeconds(300).epochSecond,
                    emailVerified = false,
                )
            )
        }

        assertEquals("unverified_email", exception.rejection.reason)
        assertEquals(iosClientId, exception.rejection.audience)
        assertEquals(false, exception.rejection.emailVerified)
    }

    @Test
    fun configuredAndroidAudienceReachesSignatureVerification() {
        val verifier = verifier()
        val exception = assertFailsWith<InvalidGoogleTokenException> {
            verifier.verifyAccount(
                unsignedGoogleToken(
                    audience = androidClientId,
                    expiresAt = Instant.now().plusSeconds(300).epochSecond,
                )
            )
        }

        assertEquals("invalid_signature", exception.rejection.reason)
        assertEquals(androidClientId, exception.rejection.audience)
    }

    @Test
    fun configuredIosAudienceReachesSignatureVerification() {
        val verifier = verifier()
        val exception = assertFailsWith<InvalidGoogleTokenException> {
            verifier.verifyAccount(
                unsignedGoogleToken(
                    audience = iosClientId,
                    expiresAt = Instant.now().plusSeconds(300).epochSecond,
                )
            )
        }

        assertEquals("invalid_signature", exception.rejection.reason)
        assertEquals(iosClientId, exception.rejection.audience)
    }

    private fun verifier(): GoogleIdTokenVerifierAdapter =
        GoogleIdTokenVerifierAdapter(listOf(androidClientId, iosClientId))

    private fun unsignedGoogleToken(
        audience: String,
        expiresAt: Long,
        issuer: String = "https://accounts.google.com",
        subject: String? = "google-subject",
        email: String? = "customer@sequo.test",
        emailVerified: Boolean = true,
    ): String {
        val header = mapOf("alg" to "RS256", "kid" to "test-key")
        val payload = buildMap<String, Any> {
            put("aud", audience)
            put("iss", issuer)
            put("exp", expiresAt)
            subject?.let { put("sub", it) }
            email?.let { put("email", it) }
            put("email_verified", emailVerified)
        }
        return "${encode(header)}.${encode(payload)}.unsigned"
    }

    private fun encode(value: Map<String, Any>): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(value))
}
