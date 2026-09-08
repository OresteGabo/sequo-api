package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification_outbox_worker;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class NotificationOutboxWorkerTest @Autowired constructor(
    private val worker: NotificationOutboxWorker,
    private val outboxService: NotificationOutboxService,
    private val outboxRepository: NotificationOutboxRepository,
    private val messageRepository: NotificationMessageRepository,
    private val deliveryRepository: NotificationDeliveryRepository,
    private val userRepository: UserRepository,
) {
    @BeforeTest
    fun cleanDatabase() {
        deliveryRepository.deleteAll()
        messageRepository.deleteAll()
        outboxRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun dispatchReadyRoutesOutboxEventToRecipientsAndMarksItSent() {
        val customerId = createUser("worker-customer@sequo.test")
        val merchantId = createUser("worker-merchant@sequo.test")
        val adminId = createUser("worker-admin@sequo.test")
        val now = Instant.parse("2026-09-08T12:00:00Z")
        outboxService.enqueue(
            EnqueueNotificationEventCommand(
                eventId = "worker-payment-confirmed",
                eventType = NotificationEventType.PAYMENT_CONFIRMED,
                aggregateType = "ORDER",
                aggregateId = "order-worker-1",
                payload = """
                    {
                      "customerUserId": "$customerId",
                      "merchantUserIds": ["$merchantId"],
                      "adminUserIds": ["$adminId"],
                      "title": "Payment received",
                      "body": "The order is paid and can move to fulfillment.",
                      "actionUrl": "sequo://orders/order-worker-1",
                      "activeFcmTokenCountByUserId": { "$customerId": 1 },
                      "activeWebSocketSessionsByUserId": { "$adminId": 1 }
                    }
                """.trimIndent(),
            ),
            createdAt = now.minusSeconds(10),
        )

        val result = worker.dispatchReady("worker-a", now = now)

        assertEquals(1, result.scannedEvents)
        assertEquals(1, result.claimedEvents)
        assertEquals(3, result.dispatchedMessages)
        assertEquals(listOf("worker-payment-confirmed"), result.sentEventIds)
        assertEquals(NotificationOutboxStatus.SENT, outboxRepository.findByEventId("worker-payment-confirmed")?.status)
        assertEquals(3, messageRepository.count())

        val customerMessage = messageRepository.findByRecipientUserIdOrderByCreatedAtDesc(customerId).single()
        assertEquals("Payment received", customerMessage.title)
        assertTrue(deliveryRepository.findByMessageId(requireNotNull(customerMessage.id)).any { it.channel == NotificationChannel.FCM })

        val adminMessage = messageRepository.findByRecipientUserIdOrderByCreatedAtDesc(adminId).single()
        assertTrue(deliveryRepository.findByMessageId(requireNotNull(adminMessage.id)).any { it.channel == NotificationChannel.WEBSOCKET })
    }

    @Test
    fun malformedOrRecipientlessPayloadFailsWithoutCreatingMessages() {
        val now = Instant.parse("2026-09-08T12:30:00Z")
        outboxService.enqueue(
            EnqueueNotificationEventCommand(
                eventId = "worker-recipientless",
                eventType = NotificationEventType.ORDER_CREATED,
                aggregateType = "ORDER",
                aggregateId = "order-worker-2",
                payload = "{}",
            ),
            createdAt = now.minusSeconds(10),
        )
        outboxService.enqueue(
            EnqueueNotificationEventCommand(
                eventId = "worker-malformed-json",
                eventType = NotificationEventType.ORDER_CREATED,
                aggregateType = "ORDER",
                aggregateId = "order-worker-malformed",
                payload = "{",
            ),
            createdAt = now.minusSeconds(9),
        )

        val result = worker.dispatchReady("worker-a", now = now)

        assertEquals(setOf("worker-recipientless", "worker-malformed-json"), result.failedEventIds.toSet())
        assertEquals(0, result.dispatchedMessages)
        assertEquals(0, messageRepository.count())
        assertEquals(NotificationOutboxStatus.FAILED_RETRYABLE, outboxRepository.findByEventId("worker-recipientless")?.status)
        assertEquals(NotificationOutboxStatus.FAILED_RETRYABLE, outboxRepository.findByEventId("worker-malformed-json")?.status)
    }

    @Test
    fun readyListingSkipsFutureRetryEvents() {
        val now = Instant.parse("2026-09-08T13:00:00Z")
        outboxService.enqueue(
            EnqueueNotificationEventCommand(
                eventId = "worker-future-retry",
                eventType = NotificationEventType.ORDER_CREATED,
                aggregateType = "ORDER",
                aggregateId = "order-worker-3",
                payload = "{}",
            ),
            createdAt = now.minusSeconds(10),
        )
        outboxService.claim("worker-future-retry", "worker-a", now.minusSeconds(5))
        outboxService.markFailed("worker-future-retry", "worker-a", now.minusSeconds(4))

        assertEquals(emptyList(), outboxService.listReady(now = now).map { it.eventId })
        assertEquals(listOf("worker-future-retry"), outboxService.listReady(now = now.plusSeconds(30)).map { it.eventId })
    }

    private fun createUser(email: String): String =
        requireNotNull(
            userRepository.save(
                User(
                    email = email,
                    passwordHash = "hash",
                    name = email.substringBefore("@"),
                    provider = AuthProvider.EMAIL,
                )
            ).id
        )
}
