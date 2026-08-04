package dev.orestegabo.sequo_api.domain.notification

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/notifications/devices")
class DeviceTokenController(
    private val deviceTokenService: DeviceTokenService,
) {
    data class RegisterFcmTokenRequest(
        val deviceId: String,
        val fcmToken: String,
        val appFamily: NotificationAppFamily,
        val platform: NotificationPlatform,
        val appVersion: String? = null,
        val locale: String? = null,
        val timezone: String? = null,
    )

    data class DeviceFcmTokenResponse(
        val id: String,
        val deviceId: String,
        val appFamily: NotificationAppFamily,
        val platform: NotificationPlatform,
        val status: DeviceFcmTokenStatus,
        val appVersion: String?,
        val locale: String?,
        val timezone: String?,
        val lastSeenAt: String?,
    )

    data class ErrorResponse(
        val code: String,
        val message: String,
    )

    @PostMapping("/fcm")
    fun registerFcmToken(
        @AuthenticationPrincipal userId: String?,
        @RequestBody request: RegisterFcmTokenRequest,
    ): ResponseEntity<Any> {
        if (userId == null) {
            return ResponseEntity.status(401).build()
        }

        return try {
            val token = deviceTokenService.registerOrRotate(
                RegisterFcmTokenCommand(
                    userId = userId,
                    deviceId = request.deviceId,
                    fcmToken = request.fcmToken,
                    appFamily = request.appFamily,
                    platform = request.platform,
                    appVersion = request.appVersion,
                    locale = request.locale,
                    timezone = request.timezone,
                )
            )
            ResponseEntity.ok(token.toResponse())
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ErrorResponse(
                    code = "invalid_device_token_request",
                    message = e.message ?: "Device token registration request is invalid.",
                )
            )
        }
    }

    @DeleteMapping("/{appFamily}/{deviceId}")
    fun revokeDevice(
        @AuthenticationPrincipal userId: String?,
        @PathVariable appFamily: NotificationAppFamily,
        @PathVariable deviceId: String,
    ): ResponseEntity<Void> {
        if (userId == null) {
            return ResponseEntity.status(401).build()
        }

        deviceTokenService.revokeDevice(
            userId = userId,
            deviceId = deviceId,
            appFamily = appFamily,
        )
        return ResponseEntity.noContent().build()
    }
}

private fun DeviceFcmTokenSnapshot.toResponse(): DeviceTokenController.DeviceFcmTokenResponse =
    DeviceTokenController.DeviceFcmTokenResponse(
        id = id,
        deviceId = deviceId,
        appFamily = appFamily,
        platform = platform,
        status = status,
        appVersion = appVersion,
        locale = locale,
        timezone = timezone,
        lastSeenAt = lastSeenAt?.toString(),
    )
