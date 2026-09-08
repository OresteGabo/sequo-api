package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.OrderRoute
import dev.orestegabo.sequo_api.domain.order.OrderServiceLevel
import java.security.MessageDigest
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class DeliveryDispatchRunResult(
    val scannedReadySubOrders: Int,
    val createdMissions: List<DeliveryMissionSnapshot>,
    val existingMissions: List<DeliveryMissionSnapshot>,
    val skippedSubOrders: List<DeliveryDispatchSkippedSubOrder>,
)

data class DeliveryDispatchSkippedSubOrder(
    val subOrderId: String,
    val orderId: String,
    val merchantId: String,
    val reason: String,
)

@Service
class DeliveryReadinessDispatchService(
    private val subOrders: MerchantSubOrderRepository,
    private val missions: DeliveryMissionRepository,
    private val deliveryMissionService: DeliveryMissionService,
    private val orders: CustomerOrderRecordRepository,
) {
    @Transactional
    fun dispatchReadySubOrders(limit: Int = 50, at: Instant = Instant.now()): DeliveryDispatchRunResult {
        require(limit in 1..200) { "limit must be between 1 and 200." }
        val readySubOrders = subOrders.findTop200ByStatusOrderByUpdatedAtAsc(MerchantSubOrderStatus.PACKED_READY)
            .take(limit)
        val created = mutableListOf<DeliveryMissionSnapshot>()
        val existing = mutableListOf<DeliveryMissionSnapshot>()
        val skipped = mutableListOf<DeliveryDispatchSkippedSubOrder>()

        readySubOrders.forEach { subOrder ->
            val subOrderId = requireNotNull(subOrder.id)
            missions.findByMerchantSubOrderId(subOrderId)?.let {
                existing += it.toSnapshot()
                return@forEach
            }
            val order = orders.findById(subOrder.orderId).orElse(null)
            if (order == null) {
                skipped += subOrder.skipped("order_not_found")
                return@forEach
            }
            val plan = order.route.dispatchPlan()
            if (plan == null) {
                skipped += subOrder.skipped("customer_pickup_does_not_need_courier_mission")
                return@forEach
            }
            created += deliveryMissionService.create(
                CreateDeliveryMissionCommand(
                    deliveryCode = subOrder.deliveryCode(subOrderId),
                    orderId = subOrder.orderId,
                    merchantSubOrderId = subOrderId,
                    deliveryMode = plan.modeFor(order.serviceLevel),
                    destinationType = plan.destination,
                    customerDeliveryFeeCfa = order.deliveryFeeCfa,
                    courierFeeCfa = order.deliveryFeeCfa,
                    shortfallCfa = 0,
                ),
                at = at,
            )
        }

        return DeliveryDispatchRunResult(
            scannedReadySubOrders = readySubOrders.size,
            createdMissions = created,
            existingMissions = existing,
            skippedSubOrders = skipped,
        )
    }
}

private data class DispatchPlan(
    val destination: DeliveryMissionRecordDestination,
    val regularMode: DeliveryMissionRecordMode,
    val primeMode: DeliveryMissionRecordMode = regularMode,
) {
    fun modeFor(serviceLevel: OrderServiceLevel): DeliveryMissionRecordMode =
        if (serviceLevel == OrderServiceLevel.Regular) regularMode else primeMode
}

private fun OrderRoute.dispatchPlan(): DispatchPlan? =
    when (this) {
        OrderRoute.FastDelivery -> DispatchPlan(
            destination = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            regularMode = DeliveryMissionRecordMode.EXPRESS,
            primeMode = DeliveryMissionRecordMode.STANDARD,
        )
        OrderRoute.GroupedSequo -> DispatchPlan(
            destination = DeliveryMissionRecordDestination.SEQUO_CONSOLIDATION,
            regularMode = DeliveryMissionRecordMode.PROGRAMMED,
        )
        OrderRoute.PointDeRelai -> DispatchPlan(
            destination = DeliveryMissionRecordDestination.RELAY_POINT,
            regularMode = DeliveryMissionRecordMode.RELAY,
        )
        OrderRoute.Pickup -> null
    }

private fun MerchantSubOrder.skipped(reason: String) = DeliveryDispatchSkippedSubOrder(
    subOrderId = requireNotNull(id),
    orderId = orderId,
    merchantId = merchantId,
    reason = reason,
)

private fun MerchantSubOrder.deliveryCode(subOrderId: String): String =
    "DLV-${"$orderId:$subOrderId".sha256Hex().take(24)}"

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }
