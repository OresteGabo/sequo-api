package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.domain.delivery.DeliveryMission
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordDestination
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordMode
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordStatus
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRepository
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
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class AdminMonitoringServiceTest @Autowired constructor(
    private val service: AdminMonitoringService,
    private val deliveryMissions: DeliveryMissionRepository,
    private val merchantSubOrders: MerchantSubOrderRepository,
    private val relayParcels: RelayParcelRecordRepository,
    private val notificationOutbox: NotificationOutboxRepository,
    private val settlements: SettlementPersistenceService,
) {
    private val now = Instant.parse("2026-09-08T10:00:00Z")

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
