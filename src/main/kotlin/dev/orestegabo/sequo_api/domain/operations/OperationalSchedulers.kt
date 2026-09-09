package dev.orestegabo.sequo_api.domain.operations

import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionService
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionSnapshot
import dev.orestegabo.sequo_api.domain.delivery.DeliveryDispatchRunResult
import dev.orestegabo.sequo_api.domain.delivery.DeliveryReadinessDispatchService
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentService
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentSlaWarning
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxWorker
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxWorkerRunResult
import dev.orestegabo.sequo_api.domain.auth.RefreshSessionService
import dev.orestegabo.sequo_api.domain.relay.RelayParcel
import dev.orestegabo.sequo_api.domain.relay.RelayParcelApplicationService
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutAccrual
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "sequo.notifications.outbox-worker",
    name = ["enabled"],
    havingValue = "true",
)
class NotificationOutboxScheduler(
    private val worker: NotificationOutboxWorker,
    @Value("\${sequo.notifications.outbox-worker.limit:20}") private val limit: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val workerId = "notification-outbox-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${sequo.notifications.outbox-worker.fixed-delay-ms:30000}")
    fun run() {
        runOnce()
    }

    fun runOnce(): NotificationOutboxWorkerRunResult {
        val result = worker.dispatchReady(workerId = workerId, limit = limit.coerceIn(1, 100))
        if (result.claimedEvents > 0 || result.failedEventIds.isNotEmpty()) {
            logger.info(
                "Notification outbox run scanned={}, claimed={}, dispatched={}, failed={}",
                result.scannedEvents,
                result.claimedEvents,
                result.dispatchedMessages,
                result.failedEventIds.size,
            )
        }
        return result
    }
}

@Component
@ConditionalOnProperty(
    prefix = "sequo.auth.refresh-session-cleanup",
    name = ["enabled"],
    havingValue = "true",
)
class RefreshSessionCleanupScheduler(
    private val refreshSessions: RefreshSessionService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${sequo.auth.refresh-session-cleanup.fixed-delay-ms:86400000}")
    fun run() {
        val deleted = refreshSessions.deleteExpired()
        if (deleted > 0) logger.info("Refresh-session cleanup removed {} expired sessions.", deleted)
    }
}

@Component
@ConditionalOnProperty(
    prefix = "sequo.relay.delayed-parcel-scheduler",
    name = ["enabled"],
    havingValue = "true",
)
class RelayDelayedParcelScheduler(
    private val relayParcels: RelayParcelApplicationService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${sequo.relay.delayed-parcel-scheduler.fixed-delay-ms:3600000}")
    fun run() {
        runOnce()
    }

    fun runOnce(): List<RelayParcel> {
        val updated = relayParcels.evaluateDelayedForAllRelayPoints(Instant.now())
        if (updated.isNotEmpty()) {
            logger.info("Relay delayed parcel scheduler updated {} parcels.", updated.size)
        }
        return updated
    }
}

@Component
@ConditionalOnProperty(
    prefix = "sequo.delivery.mission-expiry-scheduler",
    name = ["enabled"],
    havingValue = "true",
)
class DeliveryMissionExpiryScheduler(
    private val deliveryMissions: DeliveryMissionService,
    @Value("\${sequo.delivery.mission-expiry-scheduler.offer-timeout-minutes:20}") private val offerTimeoutMinutes: Long,
    @Value("\${sequo.delivery.mission-expiry-scheduler.pickup-timeout-minutes:45}") private val pickupTimeoutMinutes: Long,
    @Value("\${sequo.delivery.mission-expiry-scheduler.limit:100}") private val limit: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${sequo.delivery.mission-expiry-scheduler.fixed-delay-ms:60000}")
    fun run() {
        runOnce()
    }

    fun runOnce(): List<DeliveryMissionSnapshot> {
        val expired = deliveryMissions.expireStaleMissions(
            evaluatedAt = Instant.now(),
            offerTimeout = Duration.ofMinutes(offerTimeoutMinutes),
            pickupTimeout = Duration.ofMinutes(pickupTimeoutMinutes),
            limit = limit.coerceIn(1, 200),
        )
        if (expired.isNotEmpty()) {
            logger.info("Delivery mission expiry scheduler moved {} missions to support problem state.", expired.size)
        }
        return expired
    }
}

@Component
@ConditionalOnProperty(
    prefix = "sequo.delivery.readiness-dispatch-scheduler",
    name = ["enabled"],
    havingValue = "true",
)
class DeliveryReadinessDispatchScheduler(
    private val dispatch: DeliveryReadinessDispatchService,
    @Value("\${sequo.delivery.readiness-dispatch-scheduler.limit:50}") private val limit: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${sequo.delivery.readiness-dispatch-scheduler.fixed-delay-ms:60000}")
    fun run() {
        runOnce()
    }

    fun runOnce(): DeliveryDispatchRunResult {
        val result = dispatch.dispatchReadySubOrders(limit = limit.coerceIn(1, 200), at = Instant.now())
        if (result.createdMissions.isNotEmpty() || result.skippedSubOrders.isNotEmpty()) {
            logger.info(
                "Delivery readiness dispatch run id={} scanned={}, created={}, existing={}, skipped={}",
                result.dispatchRunId,
                result.scannedReadySubOrders,
                result.createdMissions.size,
                result.existingMissions.size,
                result.skippedSubOrders.size,
            )
        }
        return result
    }
}

@Component
@ConditionalOnProperty(
    prefix = "sequo.merchant-fulfillment.sla-warning-scheduler",
    name = ["enabled"],
    havingValue = "true",
)
class MerchantFulfillmentSlaWarningScheduler(
    private val merchantFulfillment: MerchantFulfillmentService,
    @Value("\${sequo.merchant-fulfillment.sla-warning-scheduler.limit:100}") private val limit: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${sequo.merchant-fulfillment.sla-warning-scheduler.fixed-delay-ms:3600000}")
    fun run() {
        runOnce()
    }

    fun runOnce(): List<MerchantFulfillmentSlaWarning> {
        val warnings = merchantFulfillment.publishOverdueSlaWarnings(Instant.now(), limit.coerceIn(1, 200))
        if (warnings.isNotEmpty()) {
            logger.info("Merchant fulfillment SLA warning scheduler published {} warnings.", warnings.size)
        }
        return warnings
    }
}

@Component
@ConditionalOnProperty(
    prefix = "sequo.settlements.eligibility-scheduler",
    name = ["enabled"],
    havingValue = "true",
)
class SettlementEligibilityScheduler(
    private val settlements: SettlementPersistenceService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${sequo.settlements.eligibility-scheduler.fixed-delay-ms:3600000}")
    fun run() {
        runOnce()
    }

    fun runOnce(): List<MerchantPayoutAccrual> {
        val promoted = settlements.evaluateEligible(Instant.now())
        if (promoted.isNotEmpty()) {
            logger.info("Settlement eligibility scheduler promoted {} merchant payouts.", promoted.size)
        }
        return promoted
    }
}
