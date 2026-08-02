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
    fun signUp(@RequestBody request: SignUpRequest): ResponseEntity<AuthTokens> {
        return try {
            ResponseEntity.ok(authService.signUp(request))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }
    }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginWithEmailRequest): ResponseEntity<AuthTokens> {
        val tokens = authService.login(request)
        return tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).build()
    }

    @PostMapping("/login/social")
    fun loginSocial(@RequestBody request: LoginWithSocialRequest): ResponseEntity<AuthTokens> {
        val tokens = authService.loginWithSocialToken(request.provider, request.token)
        return tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).build()
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
}
