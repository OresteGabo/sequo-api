package dev.orestegabo.sequo_api.domain.delivery

import java.time.Instant
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class ConsolidationMissionEventListenerTest {

    private val consolidation = Mockito.mock(ConsolidationPersistenceService::class.java)
    private val listener = ConsolidationMissionEventListener(consolidation)

    @Test
    fun synchronizesSuccessfulMissionPickupWithConsolidationManifest() {
        val collectedAt = Instant.parse("2026-09-09T04:00:00Z")

        listener.onPackageCollected(
            ConsolidationPackageCollectedEvent(
                orderId = "order-1",
                subOrderId = "sub-order-2",
                collectedAt = collectedAt,
            ),
        )

        Mockito.verify(consolidation).markSellerPackageCollectedForOrder(
            orderId = "order-1",
            subOrderId = "sub-order-2",
            at = collectedAt,
        )
    }

    @Test
    fun doesNotReplayFailureToMissionTransaction() {
        Mockito.`when`(
            consolidation.markSellerPackageCollectedForOrder(
                orderId = "order-1",
                subOrderId = "sub-order-2",
                at = Instant.EPOCH,
            ),
        ).thenThrow(IllegalArgumentException("manifest not found"))

        listener.onPackageCollected(
            ConsolidationPackageCollectedEvent(
                orderId = "order-1",
                subOrderId = "sub-order-2",
                collectedAt = Instant.EPOCH,
            ),
        )

        Mockito.verify(consolidation).markSellerPackageCollectedForOrder(
            orderId = "order-1",
            subOrderId = "sub-order-2",
            at = Instant.EPOCH,
        )
    }
}
