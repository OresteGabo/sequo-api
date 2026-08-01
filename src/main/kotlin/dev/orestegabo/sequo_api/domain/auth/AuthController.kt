package dev.orestegabo.sequo_api.domain.auth

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    data class LoginRequest(val provider: AuthProvider, val token: String)
    data class RefreshRequest(val refreshToken: String)

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<AuthTokens> {
        val tokens = authService.loginWithSocialToken(request.provider, request.token)
        return if (tokens != null) {
            ResponseEntity.ok(tokens)
        } else {
            ResponseEntity.status(401).build()
        }
    }

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshRequest): ResponseEntity<AuthTokens> {
        val tokens = authService.refreshTokens(request.refreshToken)
        return if (tokens != null) {
            ResponseEntity.ok(tokens)
        } else {
            ResponseEntity.status(401).build()
        }
    }
}
