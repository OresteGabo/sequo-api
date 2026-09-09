package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxRepository
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:delivery_problem_notifications;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class DeliveryProblemNotificationTest @Autowired constructor(
    private val deliveryMissions: DeliveryMissionService,
    private val outbox: NotificationOutboxRepository,
) {
    @Test
    fun courierProblemReportPublishesOutboxEventAfterCommit() {
        val mission = assignedAndAcceptedMission(
            deliveryCode = "DELIVERY-PROBLEM-NOTIF-1",
            courierId = "courier-problem-notif-1",
        )

        deliveryMissions.transition(
            mission.id,
            "courier-problem-notif-1",
            DeliveryMissionEvent.ReportProblem,
            problemReason = "Customer unavailable at address.",
        )

        val event = outbox.findByEventId("${mission.id}:delivery-problem-reported")
        assertNotNull(event)
        assertEquals(NotificationOutboxStatus.PENDING, event.status)
        assertEquals(NotificationEventType.DELIVERY_PROBLEM_REPORTED, event.eventType)
        assertEquals("DELIVERY_MISSION", event.aggregateType)
        assertEquals(mission.id, event.aggregateId)
        requireNotNull(event.payload).also {
            assertTrue(it.contains("courier-problem-notif-1"))
            assertTrue(it.contains("Customer unavailable at address."))
            assertTrue(it.contains("/admin/delivery/missions/${mission.id}"))
        }
    }

    @Test
    fun automaticNoShowExpiryPublishesOnlyOneProblemOutboxEvent() {
        val evaluatedAt = Instant.parse("2026-09-09T10:00:00Z")
        val mission = assignedAndOfferedMission(
            deliveryCode = "DELIVERY-PROBLEM-NOTIF-2",
            courierId = "courier-problem-notif-2",
            at = evaluatedAt.minus(Duration.ofHours(1)),
        )

        deliveryMissions.expireStaleMissions(
            evaluatedAt = evaluatedAt,
            offerTimeout = Duration.ofMinutes(20),
            pickupTimeout = Duration.ofMinutes(45),
        )
        deliveryMissions.expireStaleMissions(
            evaluatedAt = evaluatedAt.plusSeconds(60),
            offerTimeout = Duration.ofMinutes(20),
            pickupTimeout = Duration.ofMinutes(45),
        )

        val event = outbox.findByEventId("${mission.id}:delivery-problem-reported")
        assertNotNull(event)
        assertEquals(NotificationOutboxStatus.PENDING, event.status)
        assertEquals(1, outbox.findAll().count { it.aggregateId == mission.id })
    }

    @Test
    fun unassignedProblemDoesNotPublishRecipientlessOutboxEvent() {
        val mission = deliveryMissions.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "DELIVERY-PROBLEM-NOTIF-3",
                orderId = "order-delivery-problem-notif-3",
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            )
        )

        deliveryMissions.forceProblem(mission.id, "admin-problem-notif", "Support-created problem before assignment.")

        assertNull(outbox.findByEventId("${mission.id}:delivery-problem-reported"))
    }

    private fun assignedAndAcceptedMission(
        deliveryCode: String,
        courierId: String,
    ): DeliveryMissionSnapshot {
        val mission = assignedAndOfferedMission(deliveryCode, courierId)
        deliveryMissions.transition(mission.id, courierId, DeliveryMissionEvent.CourierAccepts)
        deliveryMissions.transition(
            mission.id,
            courierId,
            DeliveryMissionEvent.CourierPicksUpFromSeller,
            proof = "pickup-proof",
        )
        return requireNotNull(deliveryMissions.get(mission.id))
    }

    private fun assignedAndOfferedMission(
        deliveryCode: String,
        courierId: String,
        at: Instant = Instant.parse("2026-09-09T09:00:00Z"),
    ): DeliveryMissionSnapshot {
        val mission = deliveryMissions.create(
            CreateDeliveryMissionCommand(
                deliveryCode = deliveryCode,
                orderId = "order-$deliveryCode",
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            ),
            at = at,
        )
        deliveryMissions.assignCourier(mission.id, courierId, at)
        deliveryMissions.transition(mission.id, "admin-problem-notif", DeliveryMissionEvent.OfferToCourier, at = at)
        return requireNotNull(deliveryMissions.get(mission.id))
    }
}
