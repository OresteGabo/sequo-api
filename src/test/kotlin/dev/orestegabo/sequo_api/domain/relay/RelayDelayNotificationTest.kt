package dev.orestegabo.sequo_api.domain.relay

import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxRepository
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:relay_delay_notifications;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class RelayDelayNotificationTest @Autowired constructor(
    private val relayParcels: RelayParcelApplicationService,
    private val outbox: NotificationOutboxRepository,
) {
    private val now = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun `delayed relay evaluation publishes one outbox event per status transition`() {
        relayParcels.createParcel(
            RelayParcelCreateCommand(
                parcelId = "parcel-delay-notification-1",
                relayPointId = "relay-delay-notification",
                orderId = "order-delay-notification",
                category = RelayParcelCategory.GeneralGoods,
                depositCode = "deposit-delay-notification-1",
                availableLockers = listOf(RelayLocker("locker-delay-notification", "relay-delay-notification", active = true, occupied = false)),
                createdAt = now.minusSeconds(15 * 24 * 60 * 60),
            )
        )

        relayParcels.evaluateDelayed("relay-delay-notification", now)
        relayParcels.evaluateDelayed("relay-delay-notification", now)

        val delayedEvent = outbox.findByEventId("parcel-delay-notification-1:relay-delay:Delayed")
        assertNotNull(delayedEvent)
        assertEquals(NotificationOutboxStatus.PENDING, delayedEvent.status)
        assertEquals(NotificationEventType.RELAY_PARCEL_DELAYED, delayedEvent.eventType)
        assertTrue(requireNotNull(delayedEvent.payload).contains("relay-delay-notification"))
        assertEquals(1, outbox.findAll().count { it.aggregateId == "parcel-delay-notification-1" })

        relayParcels.evaluateDelayed("relay-delay-notification", now.plusSeconds(14 * 24 * 60 * 60))

        val reviewEvent = outbox.findByEventId("parcel-delay-notification-1:relay-delay:ReturnToSellerReview")
        assertNotNull(reviewEvent)
        assertEquals(NotificationOutboxStatus.PENDING, reviewEvent.status)
        assertEquals(2, outbox.findAll().count { it.aggregateId == "parcel-delay-notification-1" })
    }
}
