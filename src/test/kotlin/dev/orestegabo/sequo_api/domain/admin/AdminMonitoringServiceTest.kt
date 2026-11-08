package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.domain.delivery.DeliveryMission
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordDestination
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordMode
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordStatus
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRepository
import dev.orestegabo.sequo_api.domain.delivery.CourierAvailabilityService
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrder
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderStatus
import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import dev.orestegabo.sequo_api.domain.notification.NotificationOutbox
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxRepository
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxStatus
import dev.orestegabo.sequo_api.domain.relay.RelayParcelCategory
import dev.orestegabo.sequo_api.domain.relay.RelayParcelRecord
import dev.orestegabo.sequo_api.domain.relay.RelayParcelRecordRepository
import dev.orestegabo.sequo_api.domain.relay.RelayParcelStatus
import dev.orestegabo.sequo_api.domain.returns.ReturnInspectionDecision
import dev.orestegabo.sequo_api.domain.returns.ReturnJourneyFixtures
import dev.orestegabo.sequo_api.domain.returns.ReturnRequestRecord
import dev.orestegabo.sequo_api.domain.returns.ReturnRequestRecordRepository
import dev.orestegabo.sequo_api.domain.returns.ReturnStatus
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutAccrualCommand
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import dev.orestegabo.sequo_api.domain.settlement.SettlementWorkflowType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class AdminMonitoringServiceTest @Autowired constructor(
    private val service: AdminMonitoringService,
    private val deliveryMissions: DeliveryMissionRepository,
    private val courierAvailability: CourierAvailabilityService,
    private val merchantSubOrders: MerchantSubOrderRepository,
    private val relayParcels: RelayParcelRecordRepository,
    private val notificationOutbox: NotificationOutboxRepository,
    private val settlements: SettlementPersistenceService,
    private val returns: ReturnRequestRecordRepository,
    private val context: ApplicationContext,
) {
    private val now = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun returnWorkflowQueuesCountPersistedRequestsWithoutReusingLegacyParcels() {
        val stages = listOf(
            ReturnStatus.AwaitingRelayDropoff to null,
            ReturnStatus.AwaitingRelayDropoff to null,
            ReturnStatus.DroppedAtRelay to null,
            ReturnStatus.CollectedBySequo to null,
            ReturnStatus.ReceivedBySequo to null,
            ReturnStatus.ReceivedBySequo to ReturnInspectionDecision.ACCEPTED,
            ReturnStatus.RefundPending to ReturnInspectionDecision.ACCEPTED,
            ReturnStatus.Requested to null,
            ReturnStatus.Rejected to ReturnInspectionDecision.REJECTED,
        )
        stages.forEachIndexed { index, (status, decision) ->
            val order = ReturnJourneyFixtures.order(context, now.minusSeconds(3600))
            returns.save(ReturnRequestRecord(
                id = "monitoring-return-$index", orderId = order.orderId, customerId = order.owner.id,
                status = status, reason = "LOCAL_FIXTURE_ONLY monitoring stage", requestedRefundCfa = 3000,
                returnPinHash = "not-an-exposed-credential", createdAt = now, updatedAt = now,
                inspectionDecision = decision,
            ))
        }
        // Both representations can exist for a supported legacy return. Never sum them.
        relayParcels.save(parcel("monitoring-linked-parcel", RelayParcelStatus.Deposited, "monitoring-return-2", now))
        relayParcels.save(parcel("monitoring-legacy-parcel", RelayParcelStatus.PickedUp, "legacy-return-only", now))

        val queues = service.operationsSnapshot(now).returnBottlenecks
        assertAll(
            { assertEquals(4, queues.returnsAwaitingHandoff) },
            { assertEquals(1, queues.returnsAwaitingInspection) },
            { assertEquals(1, queues.returnsWaitingRelayAction) },
            { assertEquals(1, queues.returnsWaitingSequoCollection) },
        )
    }

    @Test
    fun operationsSnapshotAggregatesOperationalQueuesAndAttentionItems() {
        val old = now.minusSeconds(3600)
        merchantSubOrders.save(subOrder("admin-sub-1", MerchantSubOrderStatus.MERCHANT_PENDING, old))
        merchantSubOrders.save(subOrder("admin-sub-2", MerchantSubOrderStatus.PACKED_READY, now))
        merchantSubOrders.save(subOrder("admin-sub-4", MerchantSubOrderStatus.REJECTED, now))
        settlements.accrueMerchantPayout(
            MerchantPayoutAccrualCommand(
                accrualId = "admin-payout-1",
                merchantId = "admin-sub-3-merchant",
                orderId = "admin-sub-3-order",
                sourceOrderItemId = "admin-sub-3-item",
                merchantNetCfa = 900,
                commissionCfa = 100,
                platformMarginCfa = 50,
                packageReceivedAt = now,
                workflowType = SettlementWorkflowType.DeliveryConfirmed,
            )
        )

        deliveryMissions.save(mission("admin-mission-1", DeliveryMissionRecordStatus.CREATED, null, old))
        deliveryMissions.save(
            mission(
                id = "admin-mission-2",
                status = DeliveryMissionRecordStatus.PROBLEM_REPORTED,
                courierId = "courier-admin",
                updatedAt = now,
                problemMetadata = "customer_unavailable",
            )
        )
        deliveryMissions.save(
            mission(
                id = "admin-mission-3",
                status = DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER,
                courierId = "courier-admin",
                updatedAt = now,
                shortfallCfa = 250,
            )
        )
        courierAvailability.pause(
            courierId = "courier-admin-paused",
            actorUserId = "admin-monitor",
            reason = "Temporary support pause.",
            pausedUntil = now.plusSeconds(3600),
            at = now.minusSeconds(120),
        )
        courierAvailability.pause(
            courierId = "courier-admin-expired-pause",
            actorUserId = "admin-monitor",
            reason = "Expired support pause.",
            pausedUntil = now.minusSeconds(60),
            at = now.minusSeconds(3600),
        )

        relayParcels.save(parcel("admin-parcel-1", RelayParcelStatus.Delayed, returnId = null, updatedAt = old))
        relayParcels.save(parcel("admin-parcel-2", RelayParcelStatus.Problem, returnId = "return-admin-1", updatedAt = now))
        relayParcels.save(parcel("admin-parcel-3", RelayParcelStatus.PickedUp, returnId = "return-admin-2", updatedAt = now))

        notificationOutbox.save(
            NotificationOutbox(
                eventId = "admin-event-1",
                eventType = NotificationEventType.DELIVERY_PROBLEM_REPORTED,
                aggregateType = "DELIVERY_MISSION",
                aggregateId = "admin-mission-2",
                status = NotificationOutboxStatus.FAILED_RETRYABLE,
                attemptCount = 2,
                nextAttemptAt = now.plusSeconds(60),
                createdAt = old,
                updatedAt = old,
            )
        )

        val snapshot = service.operationsSnapshot(now)

        assertAll(
            { assertEquals(2, snapshot.deliveryCapacity.activeMissions) },
            { assertEquals(1, snapshot.deliveryCapacity.unassignedMissions) },
            { assertEquals(1, snapshot.deliveryCapacity.pausedCouriers) },
            { assertEquals(1, snapshot.deliveryCapacity.problemMissions.size) },
            { assertEquals(1, snapshot.merchantFulfillment.sellerBacklog) },
            { assertEquals(1, snapshot.merchantFulfillment.readyForPickup) },
            { assertEquals(1, snapshot.merchantFulfillment.blockedBySellerRejection) },
            { assertEquals(1, snapshot.relayOperations.delayedParcels) },
            { assertEquals(1, snapshot.relayOperations.problemParcels) },
            { assertEquals(1, snapshot.notificationOutbox.retryableFailures) },
            { assertEquals(1, snapshot.payoutQueue.merchantPayoutCandidates) },
            { assertEquals(1, snapshot.payoutQueue.deliveryShortfallMissions) },
            { assertEquals(1, snapshot.returnBottlenecks.returnsWaitingSequoCollection) },
            { assertEquals(1, snapshot.returnBottlenecks.returnsInProblem) },
            { assertEquals("courier-admin-paused", snapshot.deliveryCapacity.pausedCourierAlerts.single().courierId) },
            { assertTrue(snapshot.relayOperations.oldestAttentionItems.any { it.parcelId == "admin-parcel-1" }) },
        )
    }

    private fun subOrder(codePrefix: String, status: MerchantSubOrderStatus, updatedAt: Instant) =
        MerchantSubOrder(
            subOrderCode = "$codePrefix-code",
            orderId = "$codePrefix-order",
            merchantId = "$codePrefix-merchant",
            status = status,
            itemSubtotalCfa = 1_000,
            commissionCfa = 100,
            merchantNetCfa = 900,
            createdAt = updatedAt,
            updatedAt = updatedAt,
        )

    private fun mission(
        id: String,
        status: DeliveryMissionRecordStatus,
        courierId: String?,
        updatedAt: Instant,
        shortfallCfa: Int = 0,
        problemMetadata: String? = null,
    ) = DeliveryMission(
        deliveryCode = "$id-code",
        orderId = "$id-order",
        courierId = courierId,
        deliveryMode = DeliveryMissionRecordMode.EXPRESS,
        destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
        status = status,
        customerDeliveryFeeCfa = 500,
        courierFeeCfa = 500 + shortfallCfa,
        shortfallCfa = shortfallCfa,
        problemMetadata = problemMetadata,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    private fun parcel(
        id: String,
        status: RelayParcelStatus,
        returnId: String?,
        updatedAt: Instant,
    ) = RelayParcelRecord(
        id = id,
        relayPointId = "relay-admin",
        returnId = returnId,
        depositCode = "$id-deposit",
        category = RelayParcelCategory.GeneralGoods,
        status = status,
        depositedAt = updatedAt,
        pickedUpAt = if (status == RelayParcelStatus.PickedUp) updatedAt else null,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )
}
