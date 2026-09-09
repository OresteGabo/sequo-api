package dev.orestegabo.sequo_api.domain.notification

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NotificationProviderDeliveryWorkerTest {
    @Test
    fun dispatchReadyAggregatesProviderDeliveryResults() {
        val provider = FakeProviderDeliveryService(
            ready = listOf(
                result("delivery-1", NotificationDeliveryStatus.PENDING),
                result("delivery-2", NotificationDeliveryStatus.PENDING),
                result("delivery-3", NotificationDeliveryStatus.PENDING),
            ),
            sent = mapOf(
                "delivery-1" to result("delivery-1", NotificationDeliveryStatus.SENT),
                "delivery-2" to result("delivery-2", NotificationDeliveryStatus.FAILED_RETRYABLE),
                "delivery-3" to result("delivery-3", NotificationDeliveryStatus.FAILED_FINAL),
            )
        )
        val worker = NotificationProviderDeliveryWorker(provider)

        val workerResult = worker.dispatchReady(limit = 10, now = Instant.parse("2026-09-09T12:00:00Z"))

        assertEquals(3, workerResult.scannedDeliveries)
        assertEquals(1, workerResult.sentDeliveries)
        assertEquals(1, workerResult.retryableFailures)
        assertEquals(1, workerResult.finalFailures)
        assertEquals(listOf("delivery-1", "delivery-2", "delivery-3"), workerResult.processedDeliveryIds)
    }

    @Test
    fun rejectsUnboundedBatchSize() {
        val worker = NotificationProviderDeliveryWorker(FakeProviderDeliveryService(emptyList(), emptyMap()))

        assertFailsWith<IllegalArgumentException> { worker.dispatchReady(limit = 0) }
        assertFailsWith<IllegalArgumentException> { worker.dispatchReady(limit = 201) }
    }

    private fun result(deliveryId: String, status: NotificationDeliveryStatus): NotificationProviderDeliveryResult =
        NotificationProviderDeliveryResult(
            deliveryId = deliveryId,
            status = status,
            providerReference = null,
            failureCode = null,
            nextAttemptAt = null,
        )

    private class FakeProviderDeliveryService(
        private val ready: List<NotificationProviderDeliveryResult>,
        private val sent: Map<String, NotificationProviderDeliveryResult>,
    ) : NotificationProviderDeliveryServiceContract {
        override fun listReady(limit: Int, now: Instant): List<NotificationProviderDeliveryResult> =
            ready.take(limit)

        override fun send(deliveryId: String, now: Instant): NotificationProviderDeliveryResult? =
            sent[deliveryId]
    }
}
