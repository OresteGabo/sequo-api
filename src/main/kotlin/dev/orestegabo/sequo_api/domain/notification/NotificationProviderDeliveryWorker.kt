package dev.orestegabo.sequo_api.domain.notification

import java.time.Instant
import org.springframework.stereotype.Service

data class NotificationProviderDeliveryWorkerResult(
    val scannedDeliveries: Int,
    val sentDeliveries: Int,
    val retryableFailures: Int,
    val finalFailures: Int,
    val processedDeliveryIds: List<String>,
)

@Service
class NotificationProviderDeliveryWorker(
    private val providerDeliveryService: NotificationProviderDeliveryServiceContract,
) {
    fun dispatchReady(limit: Int = 50, now: Instant = Instant.now()): NotificationProviderDeliveryWorkerResult {
        require(limit in 1..200) { "limit must be between 1 and 200." }
        val ready = providerDeliveryService.listReady(limit, now)
        val processed = ready.mapNotNull { providerDeliveryService.send(it.deliveryId, now) }

        return NotificationProviderDeliveryWorkerResult(
            scannedDeliveries = ready.size,
            sentDeliveries = processed.count { it.status == NotificationDeliveryStatus.SENT },
            retryableFailures = processed.count { it.status == NotificationDeliveryStatus.FAILED_RETRYABLE },
            finalFailures = processed.count { it.status == NotificationDeliveryStatus.FAILED_FINAL },
            processedDeliveryIds = processed.map { it.deliveryId },
        )
    }
}
