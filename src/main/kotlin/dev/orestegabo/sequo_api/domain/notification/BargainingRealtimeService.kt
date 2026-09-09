package dev.orestegabo.sequo_api.domain.notification

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.bargaining.BargainingSession
import dev.orestegabo.sequo_api.domain.bargaining.BargainingStatus
import java.time.Instant
import org.springframework.stereotype.Service

data class BargainingRealtimePayload(
    val sessionId: String,
    val customerId: String,
    val merchantId: String,
    val productId: String,
    val variantId: String?,
    val status: BargainingStatus,
    val latestOfferId: String?,
    val latestAmountCfa: Int?,
    val publishedAt: String,
)

data class BargainingRealtimePublishResult(
    val sessionId: String,
    val participantUserIds: List<String>,
)

@Service
class BargainingRealtimeService(
    private val realtimePublisher: RealtimeNotificationPublisher,
) {
    fun publishUpdate(
        session: BargainingSession,
        publishedAt: Instant = Instant.now(),
    ): BargainingRealtimePublishResult {
        val participants = listOf(session.scope.customerId, session.scope.merchantId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        require(participants.size == 2) { "Bargaining realtime requires customer and merchant participants." }

        val latestOffer = session.offers.maxByOrNull { it.createdAt }
        val payload = BargainingRealtimePayload(
            sessionId = session.id,
            customerId = session.scope.customerId,
            merchantId = session.scope.merchantId,
            productId = session.scope.productId,
            variantId = session.scope.variantId,
            status = session.status,
            latestOfferId = latestOffer?.id,
            latestAmountCfa = latestOffer?.amountCfa,
            publishedAt = publishedAt.toString(),
        )
        val message = NotificationMessageSnapshot(
            id = "bargaining:${session.id}:${publishedAt.toEpochMilli()}",
            eventId = "bargaining:${session.id}",
            recipientUserId = "",
            appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
            eventType = session.status.toNotificationEventType(),
            severity = NotificationSeverity.ACTION_REQUIRED,
            title = "Bargaining update",
            body = "A bargaining session has been updated.",
            actionUrl = "sequo://bargaining/${session.id}",
            payload = BargainingRealtimePayloadJson.write(payload),
            readAt = null,
            archivedAt = null,
            createdAt = publishedAt,
            deliveries = emptyList(),
            smsSuppressedReason = null,
        )

        participants.forEach { participant ->
            realtimePublisher.publishToUser(participant, RealtimeUserQueue.BARGAINING, message.copy(recipientUserId = participant))
        }

        return BargainingRealtimePublishResult(session.id, participants)
    }
}

private fun BargainingStatus.toNotificationEventType(): NotificationEventType =
    when (this) {
        BargainingStatus.CustomerOffered -> NotificationEventType.BARGAINING_PROPOSAL_CREATED
        BargainingStatus.MerchantCountered -> NotificationEventType.BARGAINING_COUNTERED
        BargainingStatus.Accepted -> NotificationEventType.BARGAINING_ACCEPTED
        else -> NotificationEventType.BARGAINING_PROPOSAL_CREATED
    }

private object BargainingRealtimePayloadJson {
    private val objectMapper = ObjectMapper()

    fun write(payload: BargainingRealtimePayload): String =
        objectMapper.writeValueAsString(payload)
}
