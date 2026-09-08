package dev.orestegabo.sequo_api.domain.notification

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class NotificationOutboxServiceTest @Autowired constructor(
    private val service: NotificationOutboxService,
    private val repository: NotificationOutboxRepository,
) {
    @Test
    fun enqueueIsIdempotentAndWorkerCanCompleteEvent() {
        val createdAt = Instant.parse("2026-09-08T10:00:00Z")
        val command = command("event-outbox-1")
        val first = service.enqueue(command, createdAt)
        val duplicate = service.enqueue(command, createdAt.plusSeconds(10))
        val claimed = service.claim(command.eventId, "worker-a", createdAt.plusSeconds(1))
        val sent = service.markSent(command.eventId, "worker-a", createdAt.plusSeconds(2))

        assertEquals(first.id, duplicate.id)
        assertEquals(1, repository.count())
        assertEquals(NotificationOutboxStatus.PROCESSING, claimed?.status)
        assertEquals(1, claimed?.attemptCount)
        assertEquals(NotificationOutboxStatus.SENT, sent?.status)
        assertTrue(sent?.processedAt != null)
    }

    @Test
    fun activeLeaseAndWrongWorkerCannotProcessEvent() {
        val now = Instant.parse("2026-09-08T10:00:00Z")
        service.enqueue(command("event-outbox-2"), now)
        service.claim("event-outbox-2", "worker-a", now)

        assertNull(service.claim("event-outbox-2", "worker-b", now.plusSeconds(1)))
        assertNull(service.markSent("event-outbox-2", "worker-b", now.plusSeconds(1)))
    }

    @Test
    fun failedEventRetriesWithBackoffThenBecomesFinal() {
        val now = Instant.parse("2026-09-08T10:00:00Z")
        service.enqueue(command("event-outbox-3"), now)
        service.claim("event-outbox-3", "worker-a", now)
        val retry = service.markFailed("event-outbox-3", "worker-a", now.plusSeconds(1))

        assertEquals(NotificationOutboxStatus.FAILED_RETRYABLE, retry?.status)
        assertEquals(now.plusSeconds(31), retry?.nextAttemptAt)
        assertNull(service.claim("event-outbox-3", "worker-a", now.plusSeconds(30)))
        val claimedAgain = service.claim("event-outbox-3", "worker-a", now.plusSeconds(31))
        assertEquals(2, claimedAgain?.attemptCount)
    }

    @Test
    fun invalidCommandAndLeaseConfigurationAreRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { command("") }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            NotificationOutboxService(repository, maxAttempts = 0)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            NotificationOutboxService(repository, retryDelay = Duration.ZERO)
        }
    }

    private fun command(eventId: String): EnqueueNotificationEventCommand =
        EnqueueNotificationEventCommand(
            eventId = eventId,
            eventType = NotificationEventType.ORDER_CREATED,
            aggregateType = "ORDER",
            aggregateId = "order-1",
            payload = "{\"orderId\":\"order-1\"}",
        )
}
