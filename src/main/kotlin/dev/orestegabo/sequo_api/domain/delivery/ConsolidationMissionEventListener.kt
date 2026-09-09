package dev.orestegabo.sequo_api.domain.delivery

import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

data class ConsolidationPackageCollectedEvent(
    val orderId: String,
    val subOrderId: String,
    val collectedAt: Instant,
)

@Component
class ConsolidationMissionEventListener(
    private val consolidation: ConsolidationPersistenceService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onPackageCollected(event: ConsolidationPackageCollectedEvent) {
        runCatching {
            consolidation.markSellerPackageCollectedForOrder(
                orderId = event.orderId,
                subOrderId = event.subOrderId,
                at = event.collectedAt,
            )
        }.onFailure { error ->
            logger.warn(
                "Could not synchronize consolidation collection for order {} and sub-order {}: {}",
                event.orderId,
                event.subOrderId,
                error.message,
            )
        }
    }
}
