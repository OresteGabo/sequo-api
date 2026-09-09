package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification_dispatch_service;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class NotificationDispatchServiceTest @Autowired constructor(
    private val service: NotificationDispatchService,
    private val deliveryRepository: NotificationDeliveryRepository,
    private val messageRepository: NotificationMessageRepository,
    private val userRepository: UserRepository,
) {
    @BeforeTest
    fun cleanDatabase() {
        deliveryRepository.deleteAll()
        messageRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun nonCriticalForegroundEventCreatesInAppAndWebSocketDeliveriesOnly() {
        val userId = createUser("dispatch-foreground@sequo.test")

        val snapshot = service.createMessageAndPlanDeliveries(
            command = sampleCommand(
                eventId = "event-foreground",
                recipientUserId = userId,
                eventType = NotificationEventType.ORDER_PREPARING,
                severity = NotificationSeverity.INFO,
            ),
            context = NotificationDeliveryContext(
                activeWebSocketSessions = 1,
                activeFcmTokenCount = 1,
            ),
            occurredAt = Instant.parse("2026-08-02T14:00:00Z"),
        )

        assertEquals(
            setOf(NotificationChannel.IN_APP, NotificationChannel.WEBSOCKET),
            snapshot.deliveries.map { it.channel }.toSet(),
        )
        assertEquals("sms_not_critical", snapshot.smsSuppressedReason)
        assertEquals(NotificationDeliveryStatus.SENT, snapshot.deliveries.single { it.channel == NotificationChannel.IN_APP }.status)
        assertFalse(snapshot.deliveries.any { it.channel == NotificationChannel.SMS })
    }

    @Test
    fun criticalFallbackCreatesSmsDeliveryOnlyWhenBudgetAllowsIt() {
        val userId = createUser("dispatch-critical@sequo.test")

        val snapshot = service.createMessageAndPlanDeliveries(
            command = sampleCommand(
                eventId = "event-critical-sms",
                recipientUserId = userId,
                eventType = NotificationEventType.RELAY_PICKUP_CODE_CREATED,
                severity = NotificationSeverity.ACTION_REQUIRED,
            ),
            context = NotificationDeliveryContext(
                activeWebSocketSessions = 0,
                activeFcmTokenCount = 0,
                smsFallbackAllowed = true,
                smsBudgetRemaining = 1,
            ),
        )

        assertTrue(snapshot.deliveries.any { it.channel == NotificationChannel.SMS })
        assertEquals(NotificationDeliveryStatus.PENDING, snapshot.deliveries.single { it.channel == NotificationChannel.SMS }.status)
        assertEquals(null, snapshot.smsSuppressedReason)
    }

    @Test
    fun duplicateEventForSameRecipientReturnsExistingMessageWithoutDuplicateDeliveries() {
        val userId = createUser("dispatch-idempotent@sequo.test")
        val command = sampleCommand(
            eventId = "event-idempotent",
            recipientUserId = userId,
            eventType = NotificationEventType.ORDER_CREATED,
            severity = NotificationSeverity.INFO,
        )

        val first = service.createMessageAndPlanDeliveries(command, NotificationDeliveryContext())
        val second = service.createMessageAndPlanDeliveries(command, NotificationDeliveryContext(activeFcmTokenCount = 1))

        assertEquals(first.id, second.id)
        assertEquals(1, messageRepository.count())
        assertEquals(first.deliveries.map { it.id }, second.deliveries.map { it.id })
        assertEquals(1, deliveryRepository.count())
    }

    @Test
    fun rejectsUnsafeNotificationActionUrls() {
        val userId = createUser("dispatch-unsafe-url@sequo.test")

        assertFailsWith<IllegalArgumentException> {
            sampleCommand(
                eventId = "event-unsafe-url",
                recipientUserId = userId,
                eventType = NotificationEventType.ORDER_CREATED,
                severity = NotificationSeverity.INFO,
                actionUrl = "javascript:alert(1)",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            sampleCommand(
                eventId = "event-control-url",
                recipientUserId = userId,
                eventType = NotificationEventType.ORDER_CREATED,
                severity = NotificationSeverity.INFO,
                actionUrl = "sequo://orders/123\nnext",
            )
        }
    }

    @Test
    fun rejectsMalformedNotificationPayloads() {
        val userId = createUser("dispatch-invalid-payload@sequo.test")

        assertFailsWith<IllegalArgumentException> {
            sampleCommand(
                eventId = "event-broken-payload",
                recipientUserId = userId,
                eventType = NotificationEventType.ORDER_CREATED,
                severity = NotificationSeverity.INFO,
                payload = """{"orderId":""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            sampleCommand(
                eventId = "event-array-payload",
                recipientUserId = userId,
                eventType = NotificationEventType.ORDER_CREATED,
                severity = NotificationSeverity.INFO,
                payload = """["orderId"]""",
            )
        }
    }

    private fun createUser(email: String): String =
        requireNotNull(
            userRepository.save(
                User(
                    email = email,
                    passwordHash = "hash",
                    name = "Dispatch Customer",
                    provider = AuthProvider.EMAIL,
                )
            ).id
        )

    private fun sampleCommand(
        eventId: String,
        recipientUserId: String,
        eventType: NotificationEventType,
        severity: NotificationSeverity,
        actionUrl: String = "sequo://orders/123",
        payload: String = """{"orderId":"123"}""",
    ): CreateNotificationCommand =
        CreateNotificationCommand(
            eventId = eventId,
            recipientUserId = recipientUserId,
            appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
            eventType = eventType,
            severity = severity,
            title = "Order update",
            body = "Your order status changed.",
            actionUrl = actionUrl,
            payload = payload,
        )
}
