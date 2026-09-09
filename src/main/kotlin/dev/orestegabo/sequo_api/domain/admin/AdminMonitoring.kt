package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.delivery.CourierAvailabilityService
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordDestination
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordStatus
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderStatus
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxRepository
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxStatus
import dev.orestegabo.sequo_api.domain.relay.RelayParcelRecordRepository
import dev.orestegabo.sequo_api.domain.relay.RelayParcelStatus
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutStatus
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AdminOperationsSnapshot(
    val generatedAt: Instant,
    val deliveryCapacity: DeliveryCapacitySnapshot,
    val merchantFulfillment: MerchantFulfillmentMonitoringSnapshot,
    val relayOperations: RelayOperationsSnapshot,
    val notificationOutbox: NotificationOutboxMonitoringSnapshot,
    val payoutQueue: PayoutQueueSnapshot,
    val returnBottlenecks: ReturnBottleneckSnapshot,
)

data class DeliveryCapacitySnapshot(
    val activeMissions: Long,
    val unassignedMissions: Long,
    val pausedCouriers: Int,
    val pausedCourierAlerts: List<PausedCourierAlert>,
    val relayMissionsInProgress: Long,
    val customerAddressMissionsInProgress: Long,
    val problemMissions: List<DeliveryMissionAlert>,
)

data class PausedCourierAlert(
    val courierId: String,
    val pausedReason: String?,
    val pausedBy: String?,
    val pausedAt: Instant?,
    val pausedUntil: Instant?,
    val updatedAt: Instant,
)

data class DeliveryMissionAlert(
    val missionId: String,
    val orderId: String,
    val courierId: String?,
    val status: DeliveryMissionRecordStatus,
    val destinationType: DeliveryMissionRecordDestination,
    val updatedAt: Instant,
)

data class MerchantFulfillmentMonitoringSnapshot(
    val sellerBacklog: Long,
    val readyForPickup: Long,
    val blockedBySellerRejection: Long,
    val oldestSellerItems: List<MerchantSubOrderAlert>,
)

data class MerchantSubOrderAlert(
    val subOrderId: String,
    val orderId: String,
    val merchantId: String,
    val status: MerchantSubOrderStatus,
    val updatedAt: Instant,
)

data class RelayOperationsSnapshot(
    val delayedParcels: Long,
    val returnReviewParcels: Long,
    val problemParcels: Long,
    val oldestAttentionItems: List<RelayParcelAlert>,
)

data class RelayParcelAlert(
    val parcelId: String,
    val relayPointId: String,
    val orderId: String?,
    val returnId: String?,
    val status: RelayParcelStatus,
    val updatedAt: Instant,
)

data class NotificationOutboxMonitoringSnapshot(
    val waitingEvents: Long,
    val retryableFailures: Long,
    val finalFailures: Long,
    val oldestAttentionItems: List<NotificationOutboxAlert>,
)

data class NotificationOutboxAlert(
    val outboxId: String,
    val eventId: String,
    val status: NotificationOutboxStatus,
    val aggregateType: String,
    val aggregateId: String,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val updatedAt: Instant,
)

data class PayoutQueueSnapshot(
    val merchantPayoutCandidates: Long,
    val deliveryShortfallMissions: Long,
)

data class ReturnBottleneckSnapshot(
    val returnsWaitingRelayAction: Long,
    val returnsWaitingSequoCollection: Long,
    val returnsInProblem: Long,
)

@Service
class AdminMonitoringService(
    private val deliveryMissions: DeliveryMissionRepository,
    private val courierAvailability: CourierAvailabilityService,
    private val merchantSubOrders: MerchantSubOrderRepository,
    private val relayParcels: RelayParcelRecordRepository,
    private val notificationOutbox: NotificationOutboxRepository,
    private val settlements: SettlementPersistenceService,
) {
    @Transactional(readOnly = true)
    fun operationsSnapshot(generatedAt: Instant = Instant.now()): AdminOperationsSnapshot =
        AdminOperationsSnapshot(
            generatedAt = generatedAt,
            deliveryCapacity = deliveryCapacity(generatedAt),
            merchantFulfillment = merchantFulfillment(),
            relayOperations = relayOperations(),
            notificationOutbox = notificationOutbox(),
            payoutQueue = payoutQueue(),
            returnBottlenecks = returnBottlenecks(),
        )

    private fun deliveryCapacity(generatedAt: Instant): DeliveryCapacitySnapshot {
        val activeStatuses = setOf(
            DeliveryMissionRecordStatus.CREATED,
            DeliveryMissionRecordStatus.OFFERED_TO_COURIER,
            DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER,
            DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER,
            DeliveryMissionRecordStatus.DEPOSITED_AT_RELAY,
            DeliveryMissionRecordStatus.PROBLEM_REPORTED,
        )
        val pausedCouriers = courierAvailability.listActivePausedCouriers(generatedAt)
        return DeliveryCapacitySnapshot(
            activeMissions = deliveryMissions.countByStatusIn(activeStatuses),
            unassignedMissions = deliveryMissions.countByCourierIdIsNullAndStatusIn(activeStatuses),
            pausedCouriers = pausedCouriers.size,
            pausedCourierAlerts = pausedCouriers.map {
                PausedCourierAlert(
                    courierId = it.courierId,
                    pausedReason = it.pausedReason,
                    pausedBy = it.pausedBy,
                    pausedAt = it.pausedAt,
                    pausedUntil = it.pausedUntil,
                    updatedAt = it.updatedAt,
                )
            },
            relayMissionsInProgress = deliveryMissions.countByDestinationTypeAndStatusIn(
                DeliveryMissionRecordDestination.RELAY_POINT,
                activeStatuses,
            ),
            customerAddressMissionsInProgress = deliveryMissions.countByDestinationTypeAndStatusIn(
                DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
                activeStatuses,
            ),
            problemMissions = deliveryMissions.findTop20ByStatusInOrderByUpdatedAtDesc(
                setOf(DeliveryMissionRecordStatus.PROBLEM_REPORTED)
            ).map { mission ->
                DeliveryMissionAlert(
                    missionId = requireNotNull(mission.id),
                    orderId = mission.orderId,
                    courierId = mission.courierId,
                    status = mission.status,
                    destinationType = mission.destinationType,
                    updatedAt = mission.updatedAt,
                )
            },
        )
    }

    private fun merchantFulfillment(): MerchantFulfillmentMonitoringSnapshot {
        val sellerBacklogStatuses = setOf(
            MerchantSubOrderStatus.MERCHANT_PENDING,
            MerchantSubOrderStatus.ACCEPTED,
            MerchantSubOrderStatus.PREPARING,
        )
        return MerchantFulfillmentMonitoringSnapshot(
            sellerBacklog = merchantSubOrders.countByStatusIn(sellerBacklogStatuses),
            readyForPickup = merchantSubOrders.countByStatusIn(setOf(MerchantSubOrderStatus.PACKED_READY)),
            blockedBySellerRejection = merchantSubOrders.countByStatusIn(setOf(MerchantSubOrderStatus.REJECTED)),
            oldestSellerItems = merchantSubOrders.findTop20ByStatusInOrderByUpdatedAtAsc(sellerBacklogStatuses)
                .map { subOrder ->
                    MerchantSubOrderAlert(
                        subOrderId = requireNotNull(subOrder.id),
                        orderId = subOrder.orderId,
                        merchantId = subOrder.merchantId,
                        status = subOrder.status,
                        updatedAt = subOrder.updatedAt,
                    )
                },
        )
    }

    private fun relayOperations(): RelayOperationsSnapshot =
        RelayOperationsSnapshot(
            delayedParcels = relayParcels.countByStatusIn(setOf(RelayParcelStatus.Delayed)),
            returnReviewParcels = relayParcels.countByStatusIn(setOf(RelayParcelStatus.ReturnToSellerReview)),
            problemParcels = relayParcels.countByStatusIn(setOf(RelayParcelStatus.Problem)),
            oldestAttentionItems = relayParcels.findTop50ByStatusInOrderByUpdatedAtAsc(
                setOf(RelayParcelStatus.Delayed, RelayParcelStatus.ReturnToSellerReview, RelayParcelStatus.Problem)
            ).map { parcel ->
                RelayParcelAlert(
                    parcelId = parcel.id,
                    relayPointId = parcel.relayPointId,
                    orderId = parcel.orderId,
                    returnId = parcel.returnId,
                    status = parcel.status,
                    updatedAt = parcel.updatedAt,
                )
            },
        )

    private fun notificationOutbox(): NotificationOutboxMonitoringSnapshot =
        NotificationOutboxMonitoringSnapshot(
            waitingEvents = notificationOutbox.countByStatusIn(setOf(NotificationOutboxStatus.PENDING)),
            retryableFailures = notificationOutbox.countByStatusIn(setOf(NotificationOutboxStatus.FAILED_RETRYABLE)),
            finalFailures = notificationOutbox.countByStatusIn(setOf(NotificationOutboxStatus.FAILED_FINAL)),
            oldestAttentionItems = notificationOutbox.findTop20ByStatusInOrderByUpdatedAtAsc(
                setOf(NotificationOutboxStatus.PENDING, NotificationOutboxStatus.FAILED_RETRYABLE, NotificationOutboxStatus.FAILED_FINAL)
            ).map { event ->
                NotificationOutboxAlert(
                    outboxId = requireNotNull(event.id),
                    eventId = event.eventId,
                    status = event.status,
                    aggregateType = event.aggregateType,
                    aggregateId = event.aggregateId,
                    attemptCount = event.attemptCount,
                    nextAttemptAt = event.nextAttemptAt,
                    updatedAt = event.updatedAt,
                )
            },
        )

    private fun payoutQueue(): PayoutQueueSnapshot =
        PayoutQueueSnapshot(
            merchantPayoutCandidates = settlements.countPayoutsByStatus(
                setOf(MerchantPayoutStatus.Accrued, MerchantPayoutStatus.Eligible, MerchantPayoutStatus.Failed)
            ),
            deliveryShortfallMissions = deliveryMissions.countByShortfallCfaGreaterThanAndStatusIn(
                0,
                setOf(DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, DeliveryMissionRecordStatus.RELEASED_BY_RELAY)
            ),
        )

    private fun returnBottlenecks(): ReturnBottleneckSnapshot =
        ReturnBottleneckSnapshot(
            returnsWaitingRelayAction = relayParcels.countByReturnIdIsNotNullAndStatusIn(
                setOf(RelayParcelStatus.Deposited, RelayParcelStatus.Delayed, RelayParcelStatus.ReturnToSellerReview)
            ),
            returnsWaitingSequoCollection = relayParcels.countByReturnIdIsNotNullAndStatusIn(
                setOf(RelayParcelStatus.PickedUp)
            ),
            returnsInProblem = relayParcels.countByReturnIdIsNotNullAndStatusIn(setOf(RelayParcelStatus.Problem)),
        )
}

@RestController
@RequestMapping("/api/admin/monitoring")
class AdminMonitoringController(
    private val service: AdminMonitoringService,
) {
    @GetMapping("/operations")
    fun operations(authentication: Authentication?): ResponseEntity<Any> =
        adminRequired(authentication) { ResponseEntity.ok(service.operationsSnapshot()) }

    private fun adminRequired(authentication: Authentication?, operation: () -> ResponseEntity<Any>): ResponseEntity<Any> =
        if (authentication == null) {
            ResponseEntity.status(401).build()
        } else if (!authentication.hasAnyRole(RoleGroups.AdminOperations)) {
            ResponseEntity.status(403).build()
        } else {
            operation()
        }
}
