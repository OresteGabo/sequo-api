package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordDestination
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordMode
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordStatus
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.mock

class RiderRadarRealtimeServiceTest {
    private val realtimePublisher = mock(RealtimeNotificationPublisher::class.java)
    private val service = RiderRadarRealtimeService(realtimePublisher)

    @Test
    fun publishesMissionOfferToDistinctEligibleRiders() {
        val mission = sampleMission()
        val offeredAt = Instant.parse("2026-09-09T10:00:00Z")

        val result = service.publishMissionOffer(
            mission = mission,
            eligibleRiderUserIds = listOf("rider-1", " rider-2 ", "rider-1", ""),
            offeredAt = offeredAt,
        )

        assertEquals(listOf("rider-1", "rider-2"), result.offeredRiderIds)
        val invocations = mockingDetails(realtimePublisher).invocations.toList()
        assertEquals(2, invocations.size)
        assertEquals(listOf("rider-1", "rider-2"), invocations.map { it.arguments[0] })
        assertEquals(listOf(RealtimeUserQueue.RIDER_MISSIONS, RealtimeUserQueue.RIDER_MISSIONS), invocations.map { it.arguments[1] })
    }

    @Test
    fun rejectsMissionOfferWithoutEligibleRiders() {
        assertFailsWith<IllegalArgumentException> {
            service.publishMissionOffer(sampleMission(), emptyList())
        }
    }

    private fun sampleMission(): DeliveryMissionSnapshot =
        DeliveryMissionSnapshot(
            id = "mission-1",
            deliveryCode = "DLV-1",
            orderId = "order-1",
            merchantSubOrderId = "sub-1",
            courierId = null,
            deliveryMode = DeliveryMissionRecordMode.EXPRESS,
            status = DeliveryMissionRecordStatus.OFFERED_TO_COURIER,
            destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            assignedAt = null,
            acceptedAt = null,
            pickupAt = null,
            deliveredAt = null,
            relayDepositedAt = null,
            shortfallCfa = 0,
            pickupProofMetadata = null,
            pickupProofActorId = null,
            dropoffProofMetadata = null,
            dropoffProofActorId = null,
            problemMetadata = null,
            createdAt = Instant.parse("2026-09-09T09:00:00Z"),
            updatedAt = Instant.parse("2026-09-09T09:00:00Z"),
        )
}
