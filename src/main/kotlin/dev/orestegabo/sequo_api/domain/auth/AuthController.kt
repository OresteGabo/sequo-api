package dev.orestegabo.sequo_api.domain.auth

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    data class SignUpRequest(val email: String, val password: String, val name: String?)
    data class LoginWithEmailRequest(val email: String, val password: String)
    data class LoginWithSocialRequest(val provider: AuthProvider, val token: String)
    data class RefreshRequest(val refreshToken: String)
    data class ForgotPasswordRequest(val email: String)
    data class ResetPasswordRequest(val token: String, val newPassword: String)

    @PostMapping("/signup")
    fun signUp(@RequestBody request: SignUpRequest): ResponseEntity<Any> {
        return try {
            ResponseEntity.ok(authService.signUp(request))
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
            ResponseEntity.badRequest().build()
        }
    }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginWithEmailRequest): ResponseEntity<Any> {
        return try {
            val tokens = authService.login(request)
            tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).build()
        } catch (e: AuthProviderRequiredException) {
            authProviderRequiredResponse(e.requiredProvider)
        }
    }

    @PostMapping("/login/social")
    fun loginSocial(@RequestBody request: LoginWithSocialRequest): ResponseEntity<Any> {
        return try {
            val tokens = authService.loginWithSocialToken(request.provider, request.token)
            tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).build()
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
    fun refresh(@RequestBody request: RefreshRequest): ResponseEntity<AuthTokens> {
        val tokens = authService.refreshTokens(request.refreshToken)
        return tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).build()
    }

    @PostMapping("/forgot-password")
    fun forgotPassword(@RequestBody request: ForgotPasswordRequest): ResponseEntity<Map<String, String>> {
        authService.forgotPassword(request.email)
        return ResponseEntity.ok(
            mapOf("message" to "If the account exists, password reset instructions will be sent.")
        )
    }

    @PostMapping("/reset-password")
    fun resetPassword(@RequestBody request: ResetPasswordRequest): ResponseEntity<Map<String, String>> {
        val success = authService.resetPassword(request)
        return if (success) {
            ResponseEntity.ok(mapOf("message" to "Password reset successful"))
        } else {
            ResponseEntity.badRequest().body(mapOf("message" to "Invalid or expired token"))
        }
    }

    private fun authProviderRequiredResponse(provider: AuthProvider): ResponseEntity<Any> =
        ResponseEntity.status(409).body(
            AuthErrorResponse(
                code = "auth_provider_required",
                message = "This account uses ${providerLabel(provider)} sign-in. Continue with ${providerLabel(provider)} to access it.",
                requiredProvider = provider
            )
        )

    private fun providerLabel(provider: AuthProvider): String =
        when (provider) {
            AuthProvider.EMAIL -> "email/password"
            AuthProvider.GOOGLE -> "Google"
            AuthProvider.FACEBOOK -> "Facebook"
            AuthProvider.APPLE -> "Apple"
        }
}
