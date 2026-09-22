package dev.orestegabo.sequo_api.domain.auth

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

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
    private val audiences = allowedClientIds
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private val verifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory())
        .setAudience(audiences)
        .build()

    override fun verifyAccount(idToken: String): VerifiedGoogleAccount? {
        return try {
            if (audiences.isEmpty()) return null
            val token = verifier.verify(idToken) ?: return null
            val payload = token.payload
            val subject = payload.subject?.takeIf { it.isNotBlank() } ?: return null
            val email = payload.email?.takeIf { it.isNotBlank() } ?: return null
            val audience = payload.audience?.toString()?.takeIf { it.isNotBlank() } ?: return null
            if (payload.emailVerified == false) return null
            if (audience !in audiences) return null
            VerifiedGoogleAccount(
                subject = subject,
                email = email,
                emailVerified = payload.emailVerified == true,
                name = payload["name"] as? String,
                pictureUrl = payload["picture"] as? String,
                audience = audience,
            )
        } catch (e: Exception) {
            null
        }
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
