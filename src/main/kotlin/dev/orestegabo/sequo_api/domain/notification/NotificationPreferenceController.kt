package dev.orestegabo.sequo_api.domain.notification

import java.time.LocalTime
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/notifications/preferences")
class NotificationPreferenceController(
    private val preferenceService: NotificationPreferenceService,
) {
    data class PreferenceResponse(
        val id: String?,
        val appFamily: NotificationAppFamily,
        val eventType: NotificationPreferenceEventType,
        val pushEnabled: Boolean,
        val inAppEnabled: Boolean,
        val smsEnabled: Boolean,
        val quietHoursStart: String?,
        val quietHoursEnd: String?,
    )

    data class ErrorResponse(val code: String, val message: String)

    @GetMapping("/{appFamily}/effective")
    fun effectivePreference(
        @AuthenticationPrincipal userId: String?,
        @PathVariable appFamily: NotificationAppFamily,
        @RequestParam eventType: NotificationEventType,
    ): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        return try {
            ResponseEntity.ok(
                preferenceService.resolve(userId, appFamily, eventType).toResponse()
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ErrorResponse("invalid_notification_preference", e.message ?: "Invalid notification preference request.")
            )
        }
    }
}

private fun NotificationPreferenceSnapshot.toResponse(): NotificationPreferenceController.PreferenceResponse =
    NotificationPreferenceController.PreferenceResponse(
        id = id,
        appFamily = appFamily,
        eventType = eventType,
        pushEnabled = pushEnabled,
        inAppEnabled = inAppEnabled,
        smsEnabled = smsEnabled,
        quietHoursStart = quietHoursStart?.toString(),
        quietHoursEnd = quietHoursEnd?.toString(),
    )
