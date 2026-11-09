package dev.orestegabo.sequo_api.domain.auth

import io.jsonwebtoken.Jwts
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleTokenVerifierTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val nonce = "n".repeat(43)
    private val audience = "dev.orestegabo.sequo.Sequo"
    private val validator = AppleIdentityTokenValidator(setOf(audience), { kid ->
        if (kid == "apple-test") key.public as RSAPublicKey else null
    }, Clock.fixed(now, ZoneOffset.UTC))

    private fun token(
        aud: String = audience,
        issuer: String = "https://appleid.apple.com",
        tokenNonce: String = nonce,
        expires: Instant? = now.plusSeconds(300),
        verified: Any = "true",
        signer: java.security.PrivateKey = key.private,
    ): String = Jwts.builder().header().keyId("apple-test").and()
        .issuer(issuer).subject("apple-subject").audience().add(aud).and()
        .issuedAt(Date.from(now.minusSeconds(5))).apply { if (expires != null) expiration(Date.from(expires)) }
        .claim("nonce", tokenNonce).claim("email", "relay@privaterelay.appleid.com")
        .claim("email_verified", verified).signWith(signer, Jwts.SIG.RS256).compact()

    @Test fun acceptsVerifiedAppleIdentityAndPrivateRelayEmail() {
        val user = validator.verify(token(), nonce)
        assertEquals(AuthProvider.APPLE, user?.provider)
        assertEquals("apple-subject", user?.providerId)
        assertEquals("relay@privaterelay.appleid.com", user?.email)
    }

    @Test fun rejectsDifferentAppAudience() { assertNull(validator.verify(token(aud = "other.app"), nonce)) }
    @Test fun rejectsWrongIssuer() { assertNull(validator.verify(token(issuer = "https://attacker.example"), nonce)) }
    @Test fun rejectsMismatchedChallenge() { assertNull(validator.verify(token(tokenNonce = "x".repeat(43)), nonce)) }
    @Test fun rejectsExpiredIdentity() { assertNull(validator.verify(token(expires = now.minusSeconds(1)), nonce)) }
    @Test fun rejectsMissingExpiry() { assertNull(validator.verify(token(expires = null), nonce)) }
    @Test fun rejectsUnverifiedEmail() { assertNull(validator.verify(token(verified = false), nonce)) }
    @Test fun rejectsInvalidSignature() {
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertNull(validator.verify(token(signer = otherKey.private), nonce))
    }
    @Test fun rejectsMalformedOrUnsignedTokens() {
        assertNull(validator.verify("not-a-jwt", nonce))
        assertNull(validator.verify(Jwts.builder().subject("attacker").compact(), nonce))
    }
}
