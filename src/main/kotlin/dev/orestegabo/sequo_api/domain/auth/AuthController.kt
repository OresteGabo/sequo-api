package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import java.time.Instant
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val authRateLimiter: AuthRateLimiter,
) {

    data class SignUpRequest(val email: String, val password: String, val name: String?)
    data class LoginWithEmailRequest(val email: String, val password: String)
    data class LoginWithSocialRequest(val provider: AuthProvider, val token: String) {
        init {
            require(token.isNotBlank()) { "token is required." }
        }
    }
    data class RefreshRequest(val refreshToken: String) {
        init { RefreshTokenPolicy.validate(refreshToken) }
    }
    data class LogoutRequest(val refreshToken: String) {
        init { RefreshTokenPolicy.validate(refreshToken) }
    }
    data class SessionResponse(
        val id: String,
        val createdAt: Instant,
        val lastUsedAt: Instant?,
        val expiresAt: Instant,
        val revokedAt: Instant?,
    )
    data class CurrentUserResponse(
        val id: String,
        val email: String,
        val name: String?,
        val provider: AuthProvider,
        val status: UserStatus,
    )
    data class ForgotPasswordRequest(val email: String)
    data class ResetPasswordRequest(val token: String, val newPassword: String)
    data class RateLimitErrorResponse(
        val code: String,
        val message: String,
        val retryAfterSeconds: Long,
    )
    data class InvalidGoogleTokenResponse(
        val error: String = "invalid_google_token",
        val message: String,
    )

    @PostMapping("/signup")
    fun signUp(@RequestBody request: SignUpRequest): ResponseEntity<Any> {
        return try {
            val normalizedEmail = ApiInputPolicy.normalizedEmail(request.email)
            authRateLimiter.checkSignUp(normalizedEmail)
            ResponseEntity.ok(authService.signUp(request.copy(email = normalizedEmail)))
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: AuthProviderRequiredException) {
            authProviderRequiredResponse(e.requiredProvider)
        } catch (e: EmailAlreadyRegisteredException) {
            ResponseEntity.status(409).body(
                AuthErrorResponse(
                    code = "email_already_registered",
                    message = "An account already exists for this email."
                )
            )
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginWithEmailRequest): ResponseEntity<Any> {
        return try {
            val normalizedEmail = ApiInputPolicy.normalizedEmail(request.email)
            authRateLimiter.checkEmailLogin(normalizedEmail)
            val tokens = authService.login(request.copy(email = normalizedEmail))
            tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).build()
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: AuthProviderRequiredException) {
            authProviderRequiredResponse(e.requiredProvider)
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/login/social")
    fun loginSocial(@RequestBody request: LoginWithSocialRequest): ResponseEntity<Any> {
        return try {
            if (request.provider != AuthProvider.GOOGLE) return ResponseEntity.badRequest().build()
            val token = ApiInputPolicy.requiredToken(request.token, "token")
            authRateLimiter.checkSocialLogin(request.provider)
            val tokens = authService.loginWithSocialToken(request.provider, token)
            tokens?.let { ResponseEntity.ok(it) } ?: invalidGoogleTokenResponse()
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: InvalidGoogleTokenException) {
            invalidGoogleTokenResponse()
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        } catch (e: AccountLinkRequiredException) {
            ResponseEntity.status(409).body(
                AuthErrorResponse(
                    code = "account_link_required",
                    message = "This email already belongs to an existing ${providerLabel(e.existingProvider)} account. Sign in with that method before linking ${providerLabel(e.attemptedProvider)}.",
                    requiredProvider = e.existingProvider,
                    attemptedProvider = e.attemptedProvider
                )
            )
        }
    }

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshRequest): ResponseEntity<Any> {
        return try {
            val refreshToken = ApiInputPolicy.requiredShortToken(request.refreshToken, "refreshToken")
            authRateLimiter.checkRefresh(refreshToken)
            val tokens = authService.refreshTokens(refreshToken)
            tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).build()
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/logout")
    fun logout(@RequestBody request: LogoutRequest): ResponseEntity<Void> {
        return try {
            authService.logout(ApiInputPolicy.requiredShortToken(request.refreshToken, "refreshToken"))
            ResponseEntity.noContent().build()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }
    }

    @PostMapping("/logout-all")
    fun logoutAll(@AuthenticationPrincipal userId: String?): ResponseEntity<Void> {
        userId ?: return ResponseEntity.status(401).build()
        authService.logoutAll(userId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/sessions")
    fun sessions(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(
            authService.listSessions(userId).map { session ->
                SessionResponse(
                    id = session.id,
                    createdAt = session.createdAt,
                    lastUsedAt = session.lastUsedAt,
                    expiresAt = session.expiresAt,
                    revokedAt = session.revokedAt,
                )
            }
        )
    }

    @GetMapping("/me")
    fun currentUser(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        val user = authService.currentUser(userId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            CurrentUserResponse(
                id = requireNotNull(user.id),
                email = user.email,
                name = user.name,
                provider = user.provider,
                status = user.status,
            )
        )
    }

    @DeleteMapping("/sessions/{sessionId}")
    fun revokeSession(
        @PathVariable sessionId: String,
        @AuthenticationPrincipal userId: String?,
    ): ResponseEntity<Void> {
        userId ?: return ResponseEntity.status(401).build()
        if (!authService.revokeSession(sessionId, userId)) return ResponseEntity.notFound().build()
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/forgot-password")
    fun forgotPassword(@RequestBody request: ForgotPasswordRequest): ResponseEntity<Map<String, String>> {
        return try {
            val normalizedEmail = ApiInputPolicy.normalizedEmail(request.email)
            authRateLimiter.checkForgotPassword(normalizedEmail)
            authService.forgotPassword(normalizedEmail)
            ResponseEntity.ok(
                mapOf("message" to "If the account exists, password reset instructions will be sent.")
            )
        } catch (e: RateLimitExceededException) {
            rateLimitedMapResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidAuthMapRequest(e)
        }
    }

    @PostMapping("/reset-password")
    fun resetPassword(@RequestBody request: ResetPasswordRequest): ResponseEntity<Map<String, String>> {
        return try {
            val token = ApiInputPolicy.requiredShortToken(request.token, "token")
            authRateLimiter.checkResetPassword(token)
            val success = authService.resetPassword(request.copy(token = token))
            if (success) {
                ResponseEntity.ok(mapOf("message" to "Password reset successful"))
            } else {
                ResponseEntity.badRequest().body(mapOf("message" to "Invalid or expired token"))
            }
        } catch (e: RateLimitExceededException) {
            rateLimitedMapResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidAuthMapRequest(e)
        }
    }

    private fun rateLimitedResponse(e: RateLimitExceededException): ResponseEntity<Any> =
        ResponseEntity.status(429)
            .header("Retry-After", e.retryAfterSeconds.toString())
            .body(
                RateLimitErrorResponse(
                    code = "rate_limited",
                    message = "Too many attempts. Please wait before trying again.",
                    retryAfterSeconds = e.retryAfterSeconds,
                )
            )

    private fun rateLimitedMapResponse(e: RateLimitExceededException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(429)
            .header("Retry-After", e.retryAfterSeconds.toString())
            .body(
                mapOf(
                    "code" to "rate_limited",
                    "message" to "Too many attempts. Please wait before trying again.",
                    "retryAfterSeconds" to e.retryAfterSeconds.toString(),
                )
            )

    private fun authProviderRequiredResponse(provider: AuthProvider): ResponseEntity<Any> =
        ResponseEntity.status(409).body(
            AuthErrorResponse(
                code = "auth_provider_required",
                message = "This account uses ${providerLabel(provider)} sign-in. Continue with ${providerLabel(provider)} to access it.",
                requiredProvider = provider
            )
        )

    private fun invalidAuthRequest(error: IllegalArgumentException): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(
            AuthErrorResponse(
                code = "invalid_auth_request",
                message = error.message ?: "Invalid authentication request.",
            )
        )

    private fun invalidAuthMapRequest(error: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "code" to "invalid_auth_request",
                "message" to (error.message ?: "Invalid authentication request."),
            )
        )

    private fun invalidGoogleTokenResponse(): ResponseEntity<Any> =
        ResponseEntity.status(401).body(
            InvalidGoogleTokenResponse(message = "Google sign-in could not be completed with the provided token.")
        )

    private fun providerLabel(provider: AuthProvider): String =
        when (provider) {
            AuthProvider.EMAIL -> "email/password"
            AuthProvider.GOOGLE -> "Google"
            AuthProvider.FACEBOOK -> "Facebook"
            AuthProvider.APPLE -> "Apple"
        }
}
