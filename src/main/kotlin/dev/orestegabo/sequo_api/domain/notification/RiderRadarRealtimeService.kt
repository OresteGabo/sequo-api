package dev.orestegabo.sequo_api.domain.notification

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionSnapshot
import java.time.Instant
import org.springframework.stereotype.Service

data class RiderMissionOfferRealtimePayload(
    val missionId: String,
    val deliveryCode: String,
    val orderId: String,
    val deliveryMode: String,
    val destinationType: String,
    val shortfallCfa: Int,
    val offeredAt: String,
)

data class RiderRadarPublishResult(
    val missionId: String,
    val offeredRiderIds: List<String>,
)

@Service
class RiderRadarRealtimeService(
    private val realtimePublisher: RealtimeNotificationPublisher,
) {
    fun publishMissionOffer(
        mission: DeliveryMissionSnapshot,
        eligibleRiderUserIds: Collection<String>,
        offeredAt: Instant = Instant.now(),
    ): RiderRadarPublishResult {
        val riderIds = eligibleRiderUserIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(riderIds.isNotEmpty()) { "At least one eligible rider is required." }

        val payload = RiderMissionOfferRealtimePayload(
            missionId = mission.id,
            deliveryCode = mission.deliveryCode,
            orderId = mission.orderId,
            deliveryMode = mission.deliveryMode.name,
            destinationType = mission.destinationType.name,
            shortfallCfa = mission.shortfallCfa,
            offeredAt = offeredAt.toString(),
        )
        val message = NotificationMessageSnapshot(
            id = "rider-offer:${mission.id}:${offeredAt.toEpochMilli()}",
            eventId = "rider-offer:${mission.id}",
            recipientUserId = "",
            appFamily = NotificationAppFamily.SEQUO_RIDER,
            eventType = NotificationEventType.RIDER_MISSION_OFFERED,
            severity = NotificationSeverity.ACTION_REQUIRED,
            title = "New delivery mission",
            body = "A delivery mission is available.",
            actionUrl = "sequo://rider/missions/${mission.id}",
            payload = RealtimePayloadJson.riderMissionOffer(payload),
            readAt = null,
            archivedAt = null,
            createdAt = offeredAt,
            deliveries = emptyList(),
            smsSuppressedReason = null,
        )

        riderIds.forEach { riderId ->
            realtimePublisher.publishToUser(riderId, RealtimeUserQueue.RIDER_MISSIONS, message.copy(recipientUserId = riderId))
        }

        return RiderRadarPublishResult(mission.id, riderIds)
    }
}

object RealtimePayloadJson {
    private val objectMapper = ObjectMapper()

    fun riderMissionOffer(payload: RiderMissionOfferRealtimePayload): String =
        objectMapper.writeValueAsString(payload)
}
