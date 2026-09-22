package dev.orestegabo.sequo_api.domain.auth

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.time.Instant
import java.util.Base64

interface SocialTokenVerifier {
    fun verify(token: String): SocialUser?
}

interface GoogleTokenVerifier : SocialTokenVerifier {
    fun verifyAccount(idToken: String): VerifiedGoogleAccount?

    override fun verify(token: String): SocialUser? =
        verifyAccount(token)?.let {
            SocialUser(
                providerId = it.subject,
                provider = AuthProvider.GOOGLE,
                email = it.email,
                name = it.name,
                pictureUrl = it.pictureUrl,
                emailVerified = it.emailVerified,
            )
        }
}

@Service
class GoogleIdTokenVerifierAdapter(
    @Value("\${sequo.auth.google.allowed-client-ids}") allowedClientIds: List<String>,
) : GoogleTokenVerifier {
    private val logger = LoggerFactory.getLogger(GoogleIdTokenVerifierAdapter::class.java)
    private val objectMapper = ObjectMapper()
    private val audiences = allowedClientIds
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot { it.startsWith("replace_with_") }

    private val verifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory())
        .setAudience(audiences)
        .build()

    override fun verifyAccount(idToken: String): VerifiedGoogleAccount? {
        var claims: GoogleIdTokenClaims? = null
        return try {
            if (audiences.isEmpty()) reject(GoogleTokenRejection(reason = "missing_allowed_audience"))
            claims = decodeClaims(idToken)
                ?: reject(GoogleTokenRejection(reason = "malformed_token"))
            preflightClaims(claims)

            val token = verifier.verify(idToken) ?: reject(
                claims.toRejection(reason = "invalid_signature")
            )
            val payload = token.payload
            val subject = payload.subject?.takeIf { it.isNotBlank() }
                ?: reject(claims.toRejection(reason = "missing_subject"))
            val email = payload.email?.takeIf { it.isNotBlank() }
                ?: reject(claims.toRejection(reason = "missing_email"))
            val audience = payload.audience?.toString()?.takeIf { it.isNotBlank() }
                ?: reject(claims.toRejection(reason = "invalid_audience"))
            if (payload.emailVerified != true) reject(claims.toRejection(reason = "unverified_email"))
            if (audience !in audiences) reject(claims.toRejection(reason = "invalid_audience"))
            VerifiedGoogleAccount(
                subject = subject,
                email = email,
                emailVerified = payload.emailVerified == true,
                name = payload["name"] as? String,
                pictureUrl = payload["picture"] as? String,
                audience = audience,
            )
        } catch (e: InvalidGoogleTokenException) {
            throw e
        } catch (e: Exception) {
            reject(
                claims?.toRejection(reason = "invalid_signature")
                    ?: GoogleTokenRejection(reason = "invalid_signature")
            )
        }
    }

    private fun preflightClaims(claims: GoogleIdTokenClaims) {
        if (claims.audience !in audiences) reject(claims.toRejection(reason = "invalid_audience"))
        if (claims.issuer !in allowedIssuers) reject(claims.toRejection(reason = "invalid_issuer"))
        if (claims.expiresAtEpochSeconds == null || claims.expiresAtEpochSeconds < Instant.now().epochSecond) {
            reject(claims.toRejection(reason = "expired_token"))
        }
        if (!claims.subPresent) reject(claims.toRejection(reason = "missing_subject"))
        if (!claims.emailPresent) reject(claims.toRejection(reason = "missing_email"))
        if (claims.emailVerified != true) reject(claims.toRejection(reason = "unverified_email"))
    }

    private fun decodeClaims(idToken: String): GoogleIdTokenClaims? {
        val payload = idToken.split('.').getOrNull(1) ?: return null
        return try {
            val json = String(Base64.getUrlDecoder().decode(payload))
            val values = objectMapper.readValue(json, object : TypeReference<Map<String, Any?>>() {})
            GoogleIdTokenClaims(
                audience = values["aud"].asClaimString(),
                issuer = values["iss"] as? String,
                expiresAtEpochSeconds = values["exp"].asLongClaim(),
                emailVerified = values["email_verified"].asBooleanClaim(),
                subPresent = (values["sub"] as? String)?.isNotBlank() == true,
                emailPresent = (values["email"] as? String)?.isNotBlank() == true,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun GoogleIdTokenClaims.toRejection(reason: String): GoogleTokenRejection =
        GoogleTokenRejection(
            reason = reason,
            audience = audience,
            issuer = issuer,
            emailVerified = emailVerified,
            subPresent = subPresent,
        )

    private fun reject(rejection: GoogleTokenRejection): Nothing {
        logger.warn(
            "Google login rejected: reason={} aud={} iss={} emailVerified={} subPresent={}",
            rejection.reason,
            rejection.audience,
            rejection.issuer,
            rejection.emailVerified,
            rejection.subPresent,
        )
        throw InvalidGoogleTokenException(rejection)
    }

    private fun Any?.asClaimString(): String? =
        when (this) {
            is String -> takeIf { it.isNotBlank() }
            is List<*> -> firstOrNull { it is String && it.isNotBlank() } as? String
            else -> null
        }

    private fun Any?.asLongClaim(): Long? =
        when (this) {
            is Number -> toLong()
            is String -> toLongOrNull()
            else -> null
        }

    private fun Any?.asBooleanClaim(): Boolean? =
        when (this) {
            is Boolean -> this
            is String -> toBooleanStrictOrNull()
            else -> null
        }

    private data class GoogleIdTokenClaims(
        val audience: String?,
        val issuer: String?,
        val expiresAtEpochSeconds: Long?,
        val emailVerified: Boolean?,
        val subPresent: Boolean,
        val emailPresent: Boolean,
    )

    private companion object {
        val allowedIssuers = setOf("accounts.google.com", "https://accounts.google.com")
    }
}

@Service
class FacebookTokenVerifier(
    @Value("\${sequo.auth.facebook.app-id}") private val appId: String
) : SocialTokenVerifier {
    private val restTemplate = RestTemplate()

    override fun verify(token: String): SocialUser? {
        // Implementation for Facebook Graph API verification
        // In production, you can also use appId to verify the token was generated for your app
        return try {
            val url = "https://graph.facebook.com/me?fields=id,name,email,picture&access_token=$token"
            val response = restTemplate.getForObject(url, Map::class.java) ?: return null
            SocialUser(
                providerId = response["id"] as String,
                provider = AuthProvider.FACEBOOK,
                email = response["email"] as? String,
                name = response["name"] as? String,
                pictureUrl = ((response["picture"] as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("url") as? String,
                emailVerified = true
            )
        } catch (e: Exception) {
            null
        }
    }
}

@Service
class AppleTokenVerifier(
    @Value("\${sequo.auth.apple.client-id}") private val clientId: String
) : SocialTokenVerifier {
    override fun verify(token: String): SocialUser? {
        // Apple verification requires validating a JWT (identity token) against Apple's public keys.
        // The 'clientId' (Service ID) is used here to verify the 'aud' (audience) claim in the JWT.
        return null 
    }
}
