package dev.orestegabo.sequo_api.domain.notification

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.messaging.simp.SimpMessagingTemplate

class RealtimeNotificationPublisherTest {
    private val messagingTemplate = mock(SimpMessagingTemplate::class.java)
    private val publisher = RealtimeNotificationPublisher(messagingTemplate)

    @Test
    fun publishesEnvelopeToUserQueue() {
        val message = sampleMessage()

        publisher.publishToUser("user-1", RealtimeUserQueue.NOTIFICATIONS, message)

        val payloadCaptor = ArgumentCaptor.forClass(Any::class.java)
        verify(messagingTemplate).convertAndSendToUser(
            eq("user-1"),
            eq("/queue/notifications"),
            payloadCaptor.capture(),
        )
        val envelope = payloadCaptor.value as RealtimeNotificationEnvelope
        assertEquals(message.id, envelope.messageId)
        assertEquals(message.eventType, envelope.eventType)
    }

    private fun sampleMessage(): NotificationMessageSnapshot =
        NotificationMessageSnapshot(
            id = "message-1",
            eventId = "event-1",
            recipientUserId = "user-1",
            appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
            eventType = NotificationEventType.ORDER_CREATED,
            severity = NotificationSeverity.INFO,
            title = "Order created",
            body = "Your order has been created.",
            actionUrl = "sequo://orders/1",
            payload = """{"orderId":"1"}""",
            readAt = null,
            archivedAt = null,
            createdAt = Instant.parse("2026-09-09T08:00:00Z"),
            deliveries = emptyList(),
            smsSuppressedReason = null,
        )
}
