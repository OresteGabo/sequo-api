package dev.orestegabo.sequo_api.domain.auth

import org.springframework.stereotype.Service

@Service
class AuthService(
    private val googleVerifier: GoogleTokenVerifier,
    private val facebookVerifier: FacebookTokenVerifier,
    private val appleVerifier: AppleTokenVerifier,
    private val jwtService: JwtService
) {
    fun loginWithSocialToken(provider: AuthProvider, token: String): AuthTokens? {
        val verifier = when (provider) {
            AuthProvider.GOOGLE -> googleVerifier
            AuthProvider.FACEBOOK -> facebookVerifier
            AuthProvider.APPLE -> appleVerifier
        }

        val socialUser = verifier.verify(token) ?: return null
        
        val session = UserSession(
            userId = socialUser.providerId,
            email = socialUser.email,
            provider = provider
        )

        return jwtService.generateTokens(session)
    }

    fun refreshTokens(refreshToken: String): AuthTokens? {
        // Extract session info directly from the refresh token to keep the system stateless and free
        val session = jwtService.parseToken(refreshToken) ?: return null
        return jwtService.generateTokens(session)
    }
}
