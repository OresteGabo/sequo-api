package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/auth", "/api/auth")
class AuthController(
    private val authService: AuthService,
    private val authRateLimiter: AuthRateLimiter,
) {
    data class DeviceRequest(
        val deviceId: String,
        val appSource: AppSource,
        val fcmToken: String? = null,
    )

    data class SocialLoginRequest(
        val token: String,
        val device: DeviceRequest,
    )

    data class WhatsAppOtpRequest(val phoneNumber: String)
    data class WhatsAppOtpResponse(val challengeId: String, val expiresInSeconds: Long = 300)
    data class WhatsAppOtpVerifyRequest(
        val phoneNumber: String,
        val code: String,
        val device: DeviceRequest,
    )

    data class PasskeyRegistrationFinishRequest(
        val challengeId: String,
        val credentialId: String,
        val credentialPublicKey: String,
    )

    data class PasskeyAuthenticationStartRequest(val credentialId: String)
    data class PasskeyAuthenticationFinishRequest(
        val challengeId: String,
        val assertion: PasskeyAssertion,
        val device: DeviceRequest,
    )

    data class CrossDeviceInitiateRequest(
        val phoneNumber: String,
        val requestingDevice: DeviceRequest,
    )

    data class CrossDeviceResolveRequest(
        val challengeId: String,
        val localBiometricVerified: Boolean,
        val targetDevice: DeviceRequest,
    )

    data class RefreshRequest(val refreshToken: String, val deviceId: String) {
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
        val phoneNumber: String?,
        val email: String?,
        val displayName: String?,
        val avatarUrl: String?,
        val provider: AuthProvider,
        val status: UserStatus,
        val active: Boolean,
        val roles: Set<RoleCode>,
    )

    data class RateLimitErrorResponse(
        val code: String,
        val message: String,
        val retryAfterSeconds: Long,
    )

    data class InvalidGoogleTokenResponse(
        val error: String,
        val reason: String,
        val code: String,
        val message: String,
        val audience: String?,
        val issuer: String?,
        val emailVerified: Boolean?,
        val subPresent: Boolean?,
    )

    @PostMapping("/social/{provider}")
    fun loginSocial(
        @PathVariable provider: AuthProvider,
        @RequestBody request: SocialLoginRequest,
    ): ResponseEntity<Any> {
        return try {
            val token = ApiInputPolicy.requiredToken(request.token, "token")
            authRateLimiter.checkSocialLogin(provider)
            val tokens = authService.loginWithSocialToken(provider, token, request.device.toBinding())
            tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).body(
                AuthErrorResponse("invalid_social_token", "${provider.label()} sign-in could not be completed.")
            )
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: InvalidGoogleTokenException) {
            ResponseEntity.status(401).body(
                InvalidGoogleTokenResponse(
                    error = "invalid_google_token",
                    reason = e.rejection.reason,
                    code = e.rejection.reason,
                    message = "Google sign-in could not be completed.",
                    audience = e.rejection.audience,
                    issuer = e.rejection.issuer,
                    emailVerified = e.rejection.emailVerified,
                    subPresent = e.rejection.subPresent,
                )
            )
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/whatsapp/request")
    fun requestWhatsAppOtp(@RequestBody request: WhatsAppOtpRequest): ResponseEntity<Any> {
        return try {
            val phone = authService.normalizePhone(request.phoneNumber)
            authRateLimiter.checkOtpRequest(phone)
            ResponseEntity.ok(WhatsAppOtpResponse(authService.requestWhatsAppOtp(phone)))
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/whatsapp/verify")
    fun verifyWhatsAppOtp(@RequestBody request: WhatsAppOtpVerifyRequest): ResponseEntity<Any> {
        return try {
            val phone = authService.normalizePhone(request.phoneNumber)
            val code = ApiInputPolicy.requiredShortText(request.code, "code", 16)
            authRateLimiter.checkOtpVerify(phone)
            val tokens = authService.verifyWhatsAppOtp(phone, code, request.device.toBinding())
            tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).body(
                AuthErrorResponse("invalid_otp", "The WhatsApp code is invalid or expired.")
            )
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/passkey/register/challenge")
    fun passkeyRegistrationChallenge(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        return try {
            authRateLimiter.checkPasskey(userId)
            authService.createPasskeyRegistrationChallenge(userId)
                ?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.status(404).build()
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        }
    }

    @PostMapping("/passkey/register/finish")
    fun finishPasskeyRegistration(
        @AuthenticationPrincipal userId: String?,
        @RequestBody request: PasskeyRegistrationFinishRequest,
    ): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        return try {
            val challengeId = ApiInputPolicy.requiredIdentifier(request.challengeId, "challengeId")
            val credentialId = ApiInputPolicy.requiredToken(request.credentialId, "credentialId")
            val publicKey = ApiInputPolicy.requiredToken(request.credentialPublicKey, "credentialPublicKey")
            authRateLimiter.checkPasskey(userId)
            if (authService.finishPasskeyRegistration(userId, challengeId, credentialId, publicKey)) {
                ResponseEntity.noContent().build()
            } else {
                ResponseEntity.badRequest().body(AuthErrorResponse("invalid_passkey_registration", "Passkey registration failed."))
            }
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/passkey/authenticate/challenge")
    fun passkeyAuthenticationChallenge(@RequestBody request: PasskeyAuthenticationStartRequest): ResponseEntity<Any> {
        return try {
            val credentialId = ApiInputPolicy.requiredToken(request.credentialId, "credentialId")
            authRateLimiter.checkPasskey(credentialId)
            authService.createPasskeyAuthenticationChallenge(credentialId)
                ?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.status(404).build()
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/passkey/authenticate/finish")
    fun finishPasskeyAuthentication(@RequestBody request: PasskeyAuthenticationFinishRequest): ResponseEntity<Any> {
        return try {
            val challengeId = ApiInputPolicy.requiredIdentifier(request.challengeId, "challengeId")
            authRateLimiter.checkPasskey(request.assertion.credentialId)
            val tokens = authService.finishPasskeyAuthentication(challengeId, request.assertion, request.device.toBinding())
            tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).body(
                AuthErrorResponse("invalid_passkey_assertion", "Passkey authentication failed.")
            )
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/cross-device/initiate")
    fun initiateCrossDevice(@RequestBody request: CrossDeviceInitiateRequest): ResponseEntity<Any> {
        return try {
            val phone = authService.normalizePhone(request.phoneNumber)
            authRateLimiter.checkCrossDevice(phone)
            authService.initiateCrossDeviceLogin(phone, request.requestingDevice.toBinding())
                ?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.status(404).body(AuthErrorResponse("no_active_device", "No active Sequo device can approve this login."))
        } catch (e: RateLimitExceededException) {
            rateLimitedResponse(e)
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/cross-device/resolve")
    fun resolveCrossDevice(
        @AuthenticationPrincipal userId: String?,
        @RequestBody request: CrossDeviceResolveRequest,
    ): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        return try {
            val challengeId = ApiInputPolicy.requiredIdentifier(request.challengeId, "challengeId")
            val tokens = authService.resolveCrossDeviceLogin(
                challengeId = challengeId,
                approvingUserId = userId,
                localBiometricVerified = request.localBiometricVerified,
                targetDevice = request.targetDevice.toBinding(),
            )
            tokens?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(401).body(
                AuthErrorResponse("cross_device_denied", "Cross-device login could not be approved.")
            )
        } catch (e: IllegalArgumentException) {
            invalidAuthRequest(e)
        }
    }

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshRequest): ResponseEntity<Any> {
        return try {
            val refreshToken = ApiInputPolicy.requiredShortToken(request.refreshToken, "refreshToken")
            val deviceId = ApiInputPolicy.requiredIdentifier(request.deviceId, "deviceId")
            authRateLimiter.checkRefresh(refreshToken)
            authService.refreshTokens(refreshToken, deviceId)
                ?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.status(401).build()
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
            authService.listSessions(userId).map {
                SessionResponse(it.id, it.createdAt, it.lastUsedAt, it.expiresAt, it.revokedAt)
            }
        )
    }

    @DeleteMapping("/sessions/{sessionId}")
    fun revokeSession(@PathVariable sessionId: String, @AuthenticationPrincipal userId: String?): ResponseEntity<Void> {
        userId ?: return ResponseEntity.status(401).build()
        return if (authService.revokeSession(sessionId, userId)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    @GetMapping("/me")
    fun currentUser(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        val user = authService.currentUser(userId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            CurrentUserResponse(
                id = requireNotNull(user.id),
                phoneNumber = user.phoneNumber,
                email = user.email,
                displayName = user.displayName,
                avatarUrl = user.avatarUrl,
                provider = user.provider,
                status = user.status,
                active = user.active,
                roles = user.roles,
            )
        )
    }

    private fun DeviceRequest.toBinding(): DeviceBinding =
        DeviceBinding(
            deviceId = ApiInputPolicy.requiredIdentifier(deviceId, "deviceId"),
            appSource = appSource,
            fcmToken = fcmToken?.let { ApiInputPolicy.requiredToken(it, "fcmToken") },
        )

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

    private fun invalidAuthRequest(error: IllegalArgumentException): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(
            AuthErrorResponse(
                code = "invalid_auth_request",
                message = error.message ?: "Invalid authentication request.",
            )
        )

    private fun AuthProvider.label(): String =
        when (this) {
            AuthProvider.EMAIL -> "WhatsApp"
            AuthProvider.GOOGLE -> "Google"
            AuthProvider.FACEBOOK -> "Facebook"
            AuthProvider.APPLE -> "Apple"
            AuthProvider.PASSKEY -> "Passkey"
        }
}
