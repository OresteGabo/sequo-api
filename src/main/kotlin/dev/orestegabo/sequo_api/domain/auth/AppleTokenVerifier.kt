package dev.orestegabo.sequo_api.domain.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date

@Service
class AppleTokenVerifier(
    @Value("\${sequo.auth.apple.client-id}") clientIds: String,
) : SocialTokenVerifier {
    private val keys = ApplePublicKeys()
    private val validator = AppleIdentityTokenValidator(clientIds.split(",").map(String::trim).toSet(), keys::find)

    // Apple authentication must be bound to a server-issued challenge.
    override fun verify(token: String): SocialUser? = null

    fun verify(token: String, nonce: String): SocialUser? = validator.verify(token, nonce)
}

internal class AppleIdentityTokenValidator(
    private val audiences: Set<String>,
    private val keyProvider: (String) -> RSAPublicKey?,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mapper = ObjectMapper()

    fun verify(token: String, nonce: String): SocialUser? = try {
        require(token.length in 1..16384 && nonce.length in 32..128)
        val parts = token.split('.')
        require(parts.size == 3)
        val header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]))
        require(header.path("alg").asText() == "RS256")
        val kid = header.path("kid").asText()
        require(kid.length in 1..128)
        val key = keyProvider(kid) ?: error("Unknown Apple signing key")
        val parsed = Jwts.parser().verifyWith(key)
            .requireIssuer("https://appleid.apple.com")
            .require("nonce", nonce)
            .clock { Date.from(clock.instant()) }
            .build().parseSignedClaims(token)
        val claims = parsed.payload
        require(claims.audience.size == 1 && claims.audience.any { it in audiences && it.isNotBlank() })
        require(claims.expiration?.toInstant()?.isAfter(clock.instant()) == true)
        require(claims.issuedAt?.toInstant()?.isAfter(clock.instant().plusSeconds(30)) == false)
        val subject = claims.subject?.takeIf { it.isNotBlank() && it.length <= 255 } ?: error("Missing subject")
        val email = (claims["email"] as? String)?.takeIf { it.isNotBlank() }
        val verified = claims["email_verified"] == true || claims["email_verified"] == "true"
        require(email == null || verified)
        SocialUser(subject, AuthProvider.APPLE, email, null, null, verified)
    } catch (_: Exception) {
        null
    }
}

private class ApplePublicKeys {
    private val http = RestTemplate(SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(5000)
        setReadTimeout(5000)
    })
    private val mapper = ObjectMapper()
    private var keys: Map<String, RSAPublicKey> = emptyMap()
    private var expiresAt = Instant.EPOCH
    private var lastFetch = Instant.EPOCH

    @Synchronized
    fun find(kid: String): RSAPublicKey? {
        val now = Instant.now()
        if ((now.isAfter(expiresAt) || kid !in keys) && now.isAfter(lastFetch.plusSeconds(60))) {
            lastFetch = now
            // Only Apple's fixed JWKS endpoint is trusted; token-supplied URLs are ignored.
            val json = http.getForObject("https://appleid.apple.com/auth/keys", String::class.java)
                ?: return null
            val refreshed = mapper.readTree(json).path("keys").mapNotNull { jwk ->
                if (jwk.path("kty").asText() != "RSA" || jwk.path("alg").asText() != "RS256" || jwk.path("use").asText() != "sig") return@mapNotNull null
                val decoder = Base64.getUrlDecoder()
                val spec = RSAPublicKeySpec(
                    BigInteger(1, decoder.decode(jwk.path("n").asText())),
                    BigInteger(1, decoder.decode(jwk.path("e").asText())),
                )
                jwk.path("kid").asText() to (KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey)
            }.toMap()
            require(refreshed.isNotEmpty())
            keys = refreshed
            expiresAt = now.plusSeconds(3600)
        }
        return keys[kid]?.takeIf { now.isBefore(expiresAt) }
    }
}
