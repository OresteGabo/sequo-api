package dev.orestegabo.sequo_api.domain.notification

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalTime

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
        require(actionUrl == null || NotificationActionUrlPolicy.isAllowed(actionUrl)) {
            "actionUrl must use an allowed scheme."
        }
        require(payload == null || payload.length <= 4000) { "payload cannot exceed 4000 characters." }
        require(payload == null || NotificationPayloadPolicy.isJsonObject(payload)) {
            "payload must be a valid JSON object."
        }
    }
}

object NotificationActionUrlPolicy {
    private val AllowedSchemes = setOf("https", "sequo")

    fun isAllowed(value: String): Boolean {
        val scheme = value.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme in AllowedSchemes && !value.any { it.isISOControl() }
    }
}

object NotificationPayloadPolicy {
    private val ObjectMapper = ObjectMapper()

    fun isJsonObject(value: String): Boolean =
        runCatching { ObjectMapper.readTree(value) }
            .getOrNull()
            ?.isObject == true
}

data class NotificationDeliveryContext(
    val activeWebSocketSessions: Int = 0,
    val activeFcmTokenCount: Int = 0,
    val fcmDeliveryFailed: Boolean = false,
    val smsFallbackAllowed: Boolean = false,
    val userSmsEnabled: Boolean = true,
    val inAppEnabled: Boolean = true,
    val pushEnabled: Boolean = true,
    val smsEnabled: Boolean = true,
    val quietHoursActive: Boolean = false,
    val recipientLocalTime: LocalTime? = null,
    val smsBudgetRemaining: Int = 0,
) {
    fun withPreference(preference: NotificationPreferenceSnapshot): NotificationDeliveryContext =
        copy(
            inAppEnabled = preference.inAppEnabled,
            pushEnabled = preference.pushEnabled,
            smsEnabled = preference.smsEnabled,
            quietHoursActive = recipientLocalTime?.let(preference::isQuietAt) ?: false,
        )
}

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
    val readAt: Instant?,
    val archivedAt: Instant?,
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
    private val preferenceService: NotificationPreferenceService,
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

        val preference = preferenceService.resolve(
            userId = command.recipientUserId,
            appFamily = command.appFamily,
            eventType = command.eventType,
        )
        val deliveryContext = context.withPreference(preference)
        val plan = channelPolicy.plan(
            NotificationChannelRequest(
                eventType = command.eventType,
                severity = command.severity,
                activeWebSocketSessions = deliveryContext.activeWebSocketSessions,
                activeFcmTokenCount = deliveryContext.activeFcmTokenCount,
                fcmDeliveryFailed = deliveryContext.fcmDeliveryFailed,
                smsFallbackAllowed = deliveryContext.smsFallbackAllowed,
                userSmsEnabled = deliveryContext.userSmsEnabled,
                inAppEnabled = deliveryContext.inAppEnabled,
                pushEnabled = deliveryContext.pushEnabled,
                smsEnabled = deliveryContext.smsEnabled,
                quietHoursActive = deliveryContext.quietHoursActive,
                smsBudgetRemaining = deliveryContext.smsBudgetRemaining,
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
        readAt = readAt,
        archivedAt = archivedAt,
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
