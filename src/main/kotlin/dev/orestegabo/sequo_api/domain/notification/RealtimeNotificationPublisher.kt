package dev.orestegabo.sequo_api.domain.notification

import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

enum class RealtimeUserQueue(val destination: String) {
    NOTIFICATIONS("/queue/notifications"),
    RIDER_MISSIONS("/queue/rider-missions"),
    BARGAINING("/queue/bargaining"),
}

data class RealtimeNotificationEnvelope(
    val messageId: String,
    val eventId: String,
    val eventType: NotificationEventType,
    val severity: NotificationSeverity,
    val title: String,
    val body: String,
    val actionUrl: String?,
    val payload: String?,
)

@Service
class RealtimeNotificationPublisher(
    private val messagingTemplate: SimpMessagingTemplate,
) {
    fun publishToUser(
        userId: String,
        queue: RealtimeUserQueue,
        message: NotificationMessageSnapshot,
    ) {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        messagingTemplate.convertAndSendToUser(
            userId,
            queue.destination,
            message.toRealtimeEnvelope(),
        )
    }
}

fun NotificationMessageSnapshot.toRealtimeEnvelope(): RealtimeNotificationEnvelope =
    RealtimeNotificationEnvelope(
        messageId = id,
        eventId = eventId,
        eventType = eventType,
        severity = severity,
        title = title,
        body = body,
        actionUrl = actionUrl,
        payload = payload,
    )
