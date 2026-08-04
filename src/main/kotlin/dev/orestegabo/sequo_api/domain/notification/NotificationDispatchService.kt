package dev.orestegabo.sequo_api.domain.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class CreateNotificationCommand(
    val eventId: String,
    val recipientUserId: String,
    val appFamily: NotificationAppFamily,
    val eventType: NotificationEventType,
    val severity: NotificationSeverity,
    val title: String,
    val body: String,
    val actionUrl: String? = null,
    val payload: String? = null,
) {
    init {
        require(eventId.isNotBlank()) { "eventId cannot be blank." }
        require(recipientUserId.isNotBlank()) { "recipientUserId cannot be blank." }
        require(title.isNotBlank()) { "title cannot be blank." }
        require(body.isNotBlank()) { "body cannot be blank." }
        require(title.length <= 255) { "title cannot exceed 255 characters." }
        require(body.length <= 1000) { "body cannot exceed 1000 characters." }
        require(actionUrl == null || actionUrl.length <= 500) { "actionUrl cannot exceed 500 characters." }
        require(payload == null || payload.length <= 4000) { "payload cannot exceed 4000 characters." }
    }
}

data class NotificationDeliveryContext(
    val activeWebSocketSessions: Int = 0,
    val activeFcmTokenCount: Int = 0,
    val fcmDeliveryFailed: Boolean = false,
    val smsFallbackAllowed: Boolean = false,
    val userSmsEnabled: Boolean = true,
    val smsBudgetRemaining: Int = 0,
)

data class NotificationMessageSnapshot(
    val id: String,
    val eventId: String,
    val recipientUserId: String,
    val appFamily: NotificationAppFamily,
    val eventType: NotificationEventType,
    val severity: NotificationSeverity,
    val title: String,
    val body: String,
    val actionUrl: String?,
    val payload: String?,
    val createdAt: Instant,
    val deliveries: List<NotificationDeliverySnapshot>,
    val smsSuppressedReason: String?,
)

data class NotificationDeliverySnapshot(
    val id: String,
    val messageId: String,
    val channel: NotificationChannel,
    val targetRef: String,
    val status: NotificationDeliveryStatus,
    val attemptCount: Int,
    val sentAt: Instant?,
)

@Service
class NotificationDispatchService(
    private val messageRepository: NotificationMessageRepository,
    private val deliveryRepository: NotificationDeliveryRepository,
    private val channelPolicy: NotificationChannelPolicy,
) {
    @Transactional
    fun createMessageAndPlanDeliveries(
        command: CreateNotificationCommand,
        context: NotificationDeliveryContext,
        occurredAt: Instant = Instant.now(),
    ): NotificationMessageSnapshot {
        val existingMessage = messageRepository.findByEventIdAndRecipientUserIdAndAppFamilyAndEventType(
            eventId = command.eventId,
            recipientUserId = command.recipientUserId,
            appFamily = command.appFamily,
            eventType = command.eventType,
        )
        if (existingMessage != null) {
            return existingMessage.toSnapshot(
                deliveries = deliveryRepository.findByMessageId(requireNotNull(existingMessage.id)),
                smsSuppressedReason = null,
            )
        }

        val message = messageRepository.save(
            NotificationMessage(
                eventId = command.eventId,
                recipientUserId = command.recipientUserId,
                appFamily = command.appFamily,
                eventType = command.eventType,
                severity = command.severity,
                title = command.title,
                body = command.body,
                actionUrl = command.actionUrl,
                payload = command.payload,
                createdAt = occurredAt,
            )
        )

        val plan = channelPolicy.plan(
            NotificationChannelRequest(
                eventType = command.eventType,
                severity = command.severity,
                activeWebSocketSessions = context.activeWebSocketSessions,
                activeFcmTokenCount = context.activeFcmTokenCount,
                fcmDeliveryFailed = context.fcmDeliveryFailed,
                smsFallbackAllowed = context.smsFallbackAllowed,
                userSmsEnabled = context.userSmsEnabled,
                smsBudgetRemaining = context.smsBudgetRemaining,
            )
        )

        val deliveries = plan.channels.map { channel ->
            deliveryRepository.save(
                NotificationDelivery(
                    messageId = requireNotNull(message.id) { "Persisted notification message id is required." },
                    channel = channel,
                    targetRef = channel.targetRef(command.recipientUserId, command.appFamily),
                    status = channel.initialStatus(),
                    sentAt = if (channel == NotificationChannel.IN_APP) occurredAt else null,
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                )
            )
        }

        return message.toSnapshot(
            deliveries = deliveries,
            smsSuppressedReason = plan.smsSuppressedReason,
        )
    }
}

private fun NotificationChannel.targetRef(
    recipientUserId: String,
    appFamily: NotificationAppFamily,
): String =
    when (this) {
        NotificationChannel.IN_APP -> "user:$recipientUserId"
        NotificationChannel.WEBSOCKET -> "ws:user:$recipientUserId:${appFamily.name}"
        NotificationChannel.FCM -> "fcm:user:$recipientUserId:${appFamily.name}"
        NotificationChannel.SMS -> "sms:user:$recipientUserId"
        NotificationChannel.EMAIL -> "email:user:$recipientUserId"
        NotificationChannel.WHATSAPP -> "whatsapp:user:$recipientUserId"
    }

private fun NotificationChannel.initialStatus(): NotificationDeliveryStatus =
    if (this == NotificationChannel.IN_APP) {
        NotificationDeliveryStatus.SENT
    } else {
        NotificationDeliveryStatus.PENDING
    }

private fun NotificationMessage.toSnapshot(
    deliveries: List<NotificationDelivery>,
    smsSuppressedReason: String?,
): NotificationMessageSnapshot =
    NotificationMessageSnapshot(
        id = requireNotNull(id) { "Persisted notification message id is required." },
        eventId = eventId,
        recipientUserId = recipientUserId,
        appFamily = appFamily,
        eventType = eventType,
        severity = severity,
        title = title,
        body = body,
        actionUrl = actionUrl,
        payload = payload,
        createdAt = createdAt,
        deliveries = deliveries.map { it.toSnapshot() },
        smsSuppressedReason = smsSuppressedReason,
    )

private fun NotificationDelivery.toSnapshot(): NotificationDeliverySnapshot =
    NotificationDeliverySnapshot(
        id = requireNotNull(id) { "Persisted notification delivery id is required." },
        messageId = messageId,
        channel = channel,
        targetRef = targetRef,
        status = status,
        attemptCount = attemptCount,
        sentAt = sentAt,
    )
