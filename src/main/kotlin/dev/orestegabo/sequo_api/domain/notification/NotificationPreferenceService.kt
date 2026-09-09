package dev.orestegabo.sequo_api.domain.notification

import java.time.Instant
import java.time.LocalTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class NotificationPreferenceSnapshot(
    val id: String?,
    val userId: String,
    val appFamily: NotificationAppFamily,
    val eventType: NotificationPreferenceEventType,
    val pushEnabled: Boolean,
    val inAppEnabled: Boolean,
    val smsEnabled: Boolean,
    val quietHoursStart: LocalTime?,
    val quietHoursEnd: LocalTime?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

@Service
class NotificationPreferenceService(
    private val repository: NotificationPreferenceRepository,
) {
    @Transactional(readOnly = true)
    fun resolve(
        userId: String,
        appFamily: NotificationAppFamily,
        eventType: NotificationEventType,
    ): NotificationPreferenceSnapshot {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        val specific = repository.findByUserIdAndAppFamilyAndEventType(
            userId,
            appFamily,
            NotificationPreferenceEventType.specific(eventType),
        )
        val default = repository.findByUserIdAndAppFamilyAndEventType(
            userId,
            appFamily,
            NotificationPreferenceEventType.ALL,
        )
        return (specific ?: default)?.toSnapshot()
            ?: defaultPreference(userId, appFamily)
    }

    private fun defaultPreference(
        userId: String,
        appFamily: NotificationAppFamily,
    ): NotificationPreferenceSnapshot =
        NotificationPreferenceSnapshot(
            id = null,
            userId = userId,
            appFamily = appFamily,
            eventType = NotificationPreferenceEventType.ALL,
            pushEnabled = true,
            inAppEnabled = true,
            smsEnabled = true,
            quietHoursStart = null,
            quietHoursEnd = null,
            createdAt = null,
            updatedAt = null,
        )
}

private fun NotificationPreference.toSnapshot(): NotificationPreferenceSnapshot =
    NotificationPreferenceSnapshot(
        id = id,
        userId = userId,
        appFamily = appFamily,
        eventType = eventType,
        pushEnabled = pushEnabled,
        inAppEnabled = inAppEnabled,
        smsEnabled = smsEnabled,
        quietHoursStart = quietHoursStart,
        quietHoursEnd = quietHoursEnd,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
