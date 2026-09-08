package dev.orestegabo.sequo_api.domain.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class NotificationInboxQuery(
    val userId: String,
    val includeArchived: Boolean = false,
    val limit: Int = 50,
) {
    init {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        require(limit in 1..100) { "limit must be between 1 and 100." }
    }
}

@Service
class NotificationReadService(
    private val messageRepository: NotificationMessageRepository,
    private val deliveryRepository: NotificationDeliveryRepository,
) {
    @Transactional(readOnly = true)
    fun listInbox(query: NotificationInboxQuery): List<NotificationMessageSnapshot> =
        messageRepository.findByRecipientUserIdOrderByCreatedAtDesc(query.userId)
            .asSequence()
            .filter { query.includeArchived || it.archivedAt == null }
            .take(query.limit)
            .map(::snapshot)
            .toList()

    @Transactional
    fun markRead(userId: String, messageId: String, at: Instant = Instant.now()): NotificationMessageSnapshot? =
        findOwned(userId, messageId)?.also { message ->
            if (message.readAt == null) message.readAt = at
        }?.let(::snapshot)

    @Transactional
    fun archive(userId: String, messageId: String, at: Instant = Instant.now()): NotificationMessageSnapshot? =
        findOwned(userId, messageId)?.also { message ->
            if (message.readAt == null) message.readAt = at
            if (message.archivedAt == null) message.archivedAt = at
        }?.let(::snapshot)

    @Transactional
    fun unarchive(userId: String, messageId: String): NotificationMessageSnapshot? =
        findOwned(userId, messageId)?.also { it.archivedAt = null }?.let(::snapshot)

    private fun findOwned(userId: String, messageId: String): NotificationMessage? {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        require(messageId.isNotBlank()) { "messageId cannot be blank." }
        return messageRepository.findByIdAndRecipientUserId(messageId, userId)
    }

    private fun snapshot(message: NotificationMessage): NotificationMessageSnapshot =
        message.toInboxSnapshot(deliveryRepository.findByMessageId(requireNotNull(message.id)))
}

private fun NotificationMessage.toInboxSnapshot(deliveries: List<NotificationDelivery>): NotificationMessageSnapshot =
    NotificationMessageSnapshot(
        id = requireNotNull(id),
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
        deliveries = deliveries.map {
            NotificationDeliverySnapshot(
                id = requireNotNull(it.id),
                messageId = it.messageId,
                channel = it.channel,
                targetRef = it.targetRef,
                status = it.status,
                attemptCount = it.attemptCount,
                sentAt = it.sentAt,
            )
        },
        smsSuppressedReason = null,
    )
