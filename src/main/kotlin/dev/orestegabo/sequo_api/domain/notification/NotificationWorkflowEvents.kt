package dev.orestegabo.sequo_api.domain.notification

import org.springframework.stereotype.Component
import org.springframework.context.event.EventListener
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

data class NotificationWorkflowEvent(
    val eventId: String,
    val eventType: NotificationEventType,
    val aggregateType: String,
    val aggregateId: String,
    val payload: String? = null,
) {
    init {
        require(eventId.isNotBlank()) { "eventId cannot be blank." }
        require(aggregateType.isNotBlank()) { "aggregateType cannot be blank." }
        require(aggregateId.isNotBlank()) { "aggregateId cannot be blank." }
        require(payload == null || payload.length <= 4000) { "payload cannot exceed 4000 characters." }
    }
}

@Component
class NotificationOutboxEventListener(
    private val outboxService: NotificationOutboxService,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @EventListener
    fun enqueue(event: NotificationWorkflowEvent) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        enqueueNow(event)
                    }
                }
            )
        } else {
            enqueueNow(event)
        }
    }

    private fun enqueueNow(event: NotificationWorkflowEvent) {
        transactionTemplate.executeWithoutResult {
            outboxService.enqueue(
                EnqueueNotificationEventCommand(
                    eventId = event.eventId,
                    eventType = event.eventType,
                    aggregateType = event.aggregateType,
                    aggregateId = event.aggregateId,
                    payload = event.payload,
                )
            )
        }
    }
}
