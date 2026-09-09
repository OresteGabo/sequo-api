package dev.orestegabo.sequo_api.domain.notification

import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class NotificationProviderSendCommand(
    val deliveryId: String,
    val channel: NotificationChannel,
    val targetRef: String,
    val title: String,
    val body: String,
    val actionUrl: String?,
    val payload: String?,
)

sealed class NotificationProviderSendResult {
    data class Sent(val providerReference: String) : NotificationProviderSendResult()
    data class RetryableFailure(
        val failureCode: String,
        val failureMessage: String,
        val retryAfter: Duration = Duration.ofMinutes(5),
    ) : NotificationProviderSendResult()
    data class FinalFailure(
        val failureCode: String,
        val failureMessage: String,
    ) : NotificationProviderSendResult()
}

interface FcmNotificationSender {
    fun send(command: NotificationProviderSendCommand): NotificationProviderSendResult
}

interface SmsNotificationSender {
    fun send(command: NotificationProviderSendCommand): NotificationProviderSendResult
}

data class NotificationProviderDeliveryResult(
    val deliveryId: String,
    val status: NotificationDeliveryStatus,
    val providerReference: String?,
    val failureCode: String?,
    val nextAttemptAt: Instant?,
)

@Service
class NotificationProviderDeliveryService(
    private val deliveryRepository: NotificationDeliveryRepository,
    private val messageRepository: NotificationMessageRepository,
    private val fcmSender: FcmNotificationSender,
    private val smsSender: SmsNotificationSender,
) {
    @Transactional
    fun send(deliveryId: String, now: Instant = Instant.now()): NotificationProviderDeliveryResult? {
        require(deliveryId.isNotBlank()) { "deliveryId cannot be blank." }
        val delivery = deliveryRepository.findById(deliveryId).orElse(null) ?: return null
        if (delivery.status !in setOf(NotificationDeliveryStatus.PENDING, NotificationDeliveryStatus.FAILED_RETRYABLE)) {
            return delivery.toProviderResult()
        }
        val message = messageRepository.findById(delivery.messageId).orElse(null) ?: return null
        val command = NotificationProviderSendCommand(
            deliveryId = requireNotNull(delivery.id),
            channel = delivery.channel,
            targetRef = delivery.targetRef,
            title = message.title,
            body = message.body,
            actionUrl = message.actionUrl,
            payload = message.payload,
        )

        delivery.status = NotificationDeliveryStatus.PROCESSING
        delivery.attemptCount += 1
        delivery.updatedAt = now
        val result = senderFor(delivery.channel).send(command)
        delivery.applyProviderResult(result, now)
        return deliveryRepository.save(delivery).toProviderResult()
    }

    private fun senderFor(channel: NotificationChannel): NotificationProviderSender =
        when (channel) {
            NotificationChannel.FCM -> NotificationProviderSender { fcmSender.send(it) }
            NotificationChannel.SMS -> NotificationProviderSender { smsSender.send(it) }
            else -> NotificationProviderSender {
                NotificationProviderSendResult.FinalFailure(
                    failureCode = "unsupported_provider_channel",
                    failureMessage = "Provider delivery is not supported for $channel.",
                )
            }
        }
}

@Component
class PendingFcmNotificationSender : FcmNotificationSender {
    override fun send(command: NotificationProviderSendCommand): NotificationProviderSendResult =
        NotificationProviderSendResult.RetryableFailure(
            failureCode = "fcm_provider_not_configured",
            failureMessage = "Firebase provider is not configured yet.",
        )
}

@Component
class PendingSmsNotificationSender : SmsNotificationSender {
    override fun send(command: NotificationProviderSendCommand): NotificationProviderSendResult =
        NotificationProviderSendResult.RetryableFailure(
            failureCode = "sms_provider_not_configured",
            failureMessage = "SMS provider is not configured yet.",
        )
}

private fun interface NotificationProviderSender {
    fun send(command: NotificationProviderSendCommand): NotificationProviderSendResult
}

private fun NotificationDelivery.applyProviderResult(
    result: NotificationProviderSendResult,
    now: Instant,
) {
    when (result) {
        is NotificationProviderSendResult.Sent -> {
            status = NotificationDeliveryStatus.SENT
            providerReference = result.providerReference
            failureCode = null
            failureMessage = null
            nextAttemptAt = null
            sentAt = now
        }
        is NotificationProviderSendResult.RetryableFailure -> {
            status = NotificationDeliveryStatus.FAILED_RETRYABLE
            failureCode = result.failureCode
            failureMessage = result.failureMessage.take(500)
            nextAttemptAt = now.plus(result.retryAfter)
        }
        is NotificationProviderSendResult.FinalFailure -> {
            status = NotificationDeliveryStatus.FAILED_FINAL
            failureCode = result.failureCode
            failureMessage = result.failureMessage.take(500)
            nextAttemptAt = null
        }
    }
    updatedAt = now
}

private fun NotificationDelivery.toProviderResult(): NotificationProviderDeliveryResult =
    NotificationProviderDeliveryResult(
        deliveryId = requireNotNull(id),
        status = status,
        providerReference = providerReference,
        failureCode = failureCode,
        nextAttemptAt = nextAttemptAt,
    )
