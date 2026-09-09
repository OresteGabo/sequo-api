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
) {
    fun isQuietAt(localTime: LocalTime): Boolean {
        val start = quietHoursStart ?: return false
        val end = quietHoursEnd ?: return false
        if (start == end) return false
        return if (start.isBefore(end)) {
            !localTime.isBefore(start) && localTime.isBefore(end)
        } else {
            !localTime.isBefore(start) || localTime.isBefore(end)
        }
    }
}

data class SaveNotificationPreferenceCommand(
    val userId: String,
    val appFamily: NotificationAppFamily,
    val eventType: NotificationPreferenceEventType = NotificationPreferenceEventType.ALL,
    val pushEnabled: Boolean = true,
    val inAppEnabled: Boolean = true,
    val smsEnabled: Boolean = true,
    val quietHoursStart: LocalTime? = null,
    val quietHoursEnd: LocalTime? = null,
) {
    init {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        require((quietHoursStart == null) == (quietHoursEnd == null)) {
            "quietHoursStart and quietHoursEnd must be set together."
        }
    }
}

@Service
class NotificationPreferenceService(
    private val repository: NotificationPreferenceRepository,
) {
    @Transactional
    fun savePreference(
        command: SaveNotificationPreferenceCommand,
        occurredAt: Instant = Instant.now(),
    ): NotificationPreferenceSnapshot {
        val existing = repository.findByUserIdAndAppFamilyAndEventType(
            command.userId,
            command.appFamily,
            command.eventType,
        )

        val preference = existing ?: NotificationPreference(
            userId = command.userId,
            appFamily = command.appFamily,
            eventType = command.eventType,
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )
        preference.pushEnabled = command.pushEnabled
        preference.inAppEnabled = command.inAppEnabled
        preference.smsEnabled = command.smsEnabled
        preference.quietHoursStart = command.quietHoursStart
        preference.quietHoursEnd = command.quietHoursEnd
        preference.updatedAt = occurredAt

        return repository.save(preference).toSnapshot()
    }

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
