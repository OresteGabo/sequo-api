package dev.orestegabo.sequo_api.domain.notification

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification_outbox_listener;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class NotificationOutboxEventListenerTest @Autowired constructor(
    private val publisher: ApplicationEventPublisher,
    private val outboxRepository: NotificationOutboxRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @BeforeTest
    fun cleanDatabase() {
        outboxRepository.deleteAll()
    }

    @Test
    fun committedWorkflowEventIsEnqueuedAfterTransactionCommit() {
        transactionTemplate.executeWithoutResult {
            publisher.publishEvent(
                NotificationWorkflowEvent(
                    eventId = "listener-event-1",
                    eventType = NotificationEventType.ORDER_READY_FOR_PICKUP,
                    aggregateType = "MERCHANT_SUB_ORDER",
                    aggregateId = "sub-order-listener-1",
                    payload = """{"riderUserIds":["rider-1"],"adminUserIds":["admin-1"]}""",
                )
            )
            assertNull(outboxRepository.findByEventId("listener-event-1"))
        }

        val outbox = outboxRepository.findByEventId("listener-event-1")
        assertNotNull(outbox)
        assertEquals(NotificationOutboxStatus.PENDING, outbox.status)
        assertEquals(NotificationEventType.ORDER_READY_FOR_PICKUP, outbox.eventType)
    }

    @Test
    fun duplicateWorkflowEventKeepsOneOutboxRecord() {
        val event = NotificationWorkflowEvent(
            eventId = "listener-event-duplicate",
            eventType = NotificationEventType.RELAY_PARCEL_DEPOSITED,
            aggregateType = "RELAY_PARCEL",
            aggregateId = "relay-parcel-1",
            payload = """{"customerUserId":"customer-1"}""",
        )

        publisher.publishEvent(event)
        publisher.publishEvent(event)

        assertEquals(1, outboxRepository.count())
        assertEquals(NotificationOutboxStatus.PENDING, outboxRepository.findByEventId("listener-event-duplicate")?.status)
    }
}
