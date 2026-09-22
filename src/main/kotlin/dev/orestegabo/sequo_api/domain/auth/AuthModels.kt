package dev.orestegabo.sequo_api.domain.auth

enum class AuthProvider {
    EMAIL, GOOGLE, FACEBOOK, APPLE
}

enum class UserStatus {
    PENDING,
    ACTIVE,
    LOCKED,
    SUSPENDED,
    DELETED;

    fun canAuthenticate(): Boolean = this == ACTIVE
}

enum class RoleCode {
    CUSTOMER,
    MERCHANT_OWNER,
    MERCHANT_STAFF,
    COURIER,
    RELAY_PARTNER,
    SUPPORT_AGENT,
    ADMIN,
    SUPER_ADMIN
}

enum class TokenUse {
    ACCESS,
    REFRESH
}

data class SocialUser(
    val providerId: String,
    val provider: AuthProvider,
    val email: String?,
    val name: String?,
    val pictureUrl: String?,
    val emailVerified: Boolean = false
)

data class VerifiedGoogleAccount(
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
    val name: String?,
    val pictureUrl: String?,
    val audience: String,
)

data class GoogleTokenRejection(
    val reason: String,
    val audience: String? = null,
    val issuer: String? = null,
    val emailVerified: Boolean? = null,
    val subPresent: Boolean? = null,
)

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

data class UserSession(
    val userId: String,
    val email: String?,
    val provider: AuthProvider,
    val roles: Set<RoleCode> = setOf(RoleCode.CUSTOMER),
    val merchantScopeIds: Set<String> = emptySet(),
    val sessionId: String? = null,
)

data class AuthErrorResponse(
    val code: String,
    val message: String,
    val requiredProvider: AuthProvider? = null,
    val attemptedProvider: AuthProvider? = null
)

class EmailAlreadyRegisteredException : RuntimeException()

class AuthProviderRequiredException(
    val requiredProvider: AuthProvider
) : RuntimeException()

class AccountLinkRequiredException(
    val existingProvider: AuthProvider,
    val attemptedProvider: AuthProvider
) : RuntimeException()

class InvalidGoogleTokenException(
    val rejection: GoogleTokenRejection
) : RuntimeException(rejection.reason)
