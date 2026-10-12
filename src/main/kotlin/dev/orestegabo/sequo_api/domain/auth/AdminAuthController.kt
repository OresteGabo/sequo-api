package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/admin-auth")
class AdminAuthController(
    private val adminAuthService: AdminAuthService,
    private val authRateLimiter: AuthRateLimiter,
) {
    data class LoginRequestStartRequest(
        val email: String,
        val deviceLabel: String? = null,
        val browserInfo: String? = null,
    )

    data class LoginRequestStartResponse(
        val requestId: String,
        val code: String,
        val expiresAt: Instant,
        val pollingIntervalSeconds: Int,
    )

    data class AdminAuthErrorResponse(
        val code: String,
        val message: String,
    )

    @PostMapping("/login-requests")
    fun startLoginRequest(@RequestBody request: LoginRequestStartRequest): ResponseEntity<Any> {
        return try {
            val email = ApiInputPolicy.normalizedIdentityEmail(request.email)
            authRateLimiter.checkAdminLoginRequest(email)
            val created = adminAuthService.createLoginRequest(
                email = email,
                deviceLabel = request.deviceLabel,
                browserInfo = request.browserInfo,
            )
            ResponseEntity.ok(
                LoginRequestStartResponse(
                    requestId = created.requestId,
                    code = created.code,
                    expiresAt = created.expiresAt,
                    pollingIntervalSeconds = created.pollingIntervalSeconds,
                )
            )
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidRequest()
        }
    }

    private fun rateLimitedResponse(e: RateLimitExceededException): ResponseEntity<Any> =
        ResponseEntity.status(429)
            .header("Retry-After", e.retryAfterSeconds.toString())
            .body(
                AuthController.RateLimitErrorResponse(
                    code = "rate_limited",
                    message = "Too many attempts. Please wait before trying again.",
                    retryAfterSeconds = e.retryAfterSeconds,
                )
            )

    private fun invalidRequest(): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(
            AdminAuthErrorResponse(
                code = "invalid_admin_login_request",
                message = "We could not create this login request.",
            )
        )
}
