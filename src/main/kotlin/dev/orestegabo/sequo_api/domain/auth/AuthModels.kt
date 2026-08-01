package dev.orestegabo.sequo_api.domain.auth

enum class AuthProvider {
    EMAIL, GOOGLE, FACEBOOK, APPLE
}

data class SocialUser(
    val providerId: String,
    val provider: AuthProvider,
    val email: String?,
    val name: String?,
    val pictureUrl: String?
)

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

data class UserSession(
    val userId: String,
    val email: String?,
    val provider: AuthProvider
)
