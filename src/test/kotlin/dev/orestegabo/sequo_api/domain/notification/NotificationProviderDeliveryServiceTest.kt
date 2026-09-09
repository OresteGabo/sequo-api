package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification_provider_delivery;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class NotificationProviderDeliveryServiceTest @Autowired constructor(
    private val service: NotificationProviderDeliveryService,
    private val messageRepository: NotificationMessageRepository,
    private val deliveryRepository: NotificationDeliveryRepository,
    private val userRepository: UserRepository,
) {
    @MockitoBean private lateinit var fcmSender: FcmNotificationSender
    @MockitoBean private lateinit var smsSender: SmsNotificationSender

    @BeforeTest
    fun cleanDatabase() {
        deliveryRepository.deleteAll()
        messageRepository.deleteAll()
        userRepository.deleteAll()
        Mockito.reset(fcmSender, smsSender)
    }

    @Test
    fun sentProviderDeliveryStoresProviderReferenceAndSentAt() {
        val delivery = createDelivery(NotificationChannel.FCM)
        Mockito.`when`(fcmSender.send(anyProviderCommand()))
            .thenReturn(NotificationProviderSendResult.Sent("fcm-message-1"))

        val result = service.send(requireNotNull(delivery.id), Instant.parse("2026-09-09T12:00:00Z"))

        val persisted = deliveryRepository.findById(requireNotNull(delivery.id)).orElseThrow()
        assertEquals(NotificationDeliveryStatus.SENT, result?.status)
        assertEquals("fcm-message-1", persisted.providerReference)
        assertEquals(Instant.parse("2026-09-09T12:00:00Z"), persisted.sentAt)
        assertNull(persisted.failureCode)
    }

    @Test
    fun retryableProviderFailureStoresBackoffAudit() {
        val delivery = createDelivery(NotificationChannel.SMS)
        Mockito.`when`(smsSender.send(anyProviderCommand()))
            .thenReturn(
                NotificationProviderSendResult.RetryableFailure(
                    failureCode = "provider_timeout",
                    failureMessage = "Provider timed out.",
                    retryAfter = Duration.ofMinutes(3),
                )
            )

        val result = service.send(requireNotNull(delivery.id), Instant.parse("2026-09-09T12:00:00Z"))

        assertEquals(NotificationDeliveryStatus.FAILED_RETRYABLE, result?.status)
        assertEquals("provider_timeout", result?.failureCode)
        assertEquals(Instant.parse("2026-09-09T12:03:00Z"), result?.nextAttemptAt)
    }

    @Test
    fun unsupportedProviderChannelFailsFinally() {
        val delivery = createDelivery(NotificationChannel.EMAIL)

        val result = service.send(requireNotNull(delivery.id), Instant.parse("2026-09-09T12:00:00Z"))

        assertEquals(NotificationDeliveryStatus.FAILED_FINAL, result?.status)
        assertEquals("unsupported_provider_channel", result?.failureCode)
    }

    @Test
    fun listsOnlyReadyProviderDeliveries() {
        val readyFcm = createDelivery(NotificationChannel.FCM, "ready-fcm")
        val readySms = createDelivery(NotificationChannel.SMS, "ready-sms")
        val websocket = createDelivery(NotificationChannel.WEBSOCKET, "websocket")
        val delayedSms = createDelivery(NotificationChannel.SMS, "delayed-sms").apply {
            status = NotificationDeliveryStatus.FAILED_RETRYABLE
            nextAttemptAt = Instant.parse("2026-09-09T13:00:00Z")
            deliveryRepository.save(this)
        }

        val ready = service.listReady(limit = 10, now = Instant.parse("2026-09-09T12:00:00Z"))

        assertEquals(
            setOf(requireNotNull(readyFcm.id), requireNotNull(readySms.id)),
            ready.map { it.deliveryId }.toSet(),
        )
        assertEquals(true, deliveryRepository.existsById(requireNotNull(websocket.id)))
        assertEquals(true, deliveryRepository.existsById(requireNotNull(delayedSms.id)))
    }

    private fun createDelivery(channel: NotificationChannel, suffix: String = channel.name.lowercase()): NotificationDelivery {
        val userId = createUser("provider-delivery-$suffix@sequo.test")
        val message = messageRepository.save(
            NotificationMessage(
                eventId = "provider-event-$suffix",
                recipientUserId = userId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationEventType.ORDER_CREATED,
                severity = NotificationSeverity.INFO,
                title = "Order update",
                body = "Your order status changed.",
            )
        )
        return deliveryRepository.save(
            NotificationDelivery(
                messageId = requireNotNull(message.id),
                channel = channel,
                targetRef = "${channel.name.lowercase()}:user:$userId",
            )
        )
    }

    private fun anyProviderCommand(): NotificationProviderSendCommand =
        Mockito.any(NotificationProviderSendCommand::class.java) ?: NotificationProviderSendCommand(
            deliveryId = "delivery",
            channel = NotificationChannel.FCM,
            targetRef = "fcm:user:user-1",
            title = "Title",
            body = "Body",
            actionUrl = null,
            payload = null,
        )

    private fun createUser(email: String): String =
        requireNotNull(
            userRepository.save(
                User(
                    email = email,
                    passwordHash = "hash",
                    name = "Provider Delivery",
                    provider = AuthProvider.EMAIL,
                )
            ).id
        )
}
