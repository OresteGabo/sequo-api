package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.delivery.CreateMerchantSubOrderCommand
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionRecordStatus
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionSnapshot
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentService
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderSnapshot
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionConfigurationService
import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import dev.orestegabo.sequo_api.domain.notification.NotificationWorkflowEvent
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import dev.orestegabo.sequo_api.domain.settlement.DeliveryShortfallCommand
import dev.orestegabo.sequo_api.domain.settlement.MerchantPayoutAccrualCommand
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import dev.orestegabo.sequo_api.domain.settlement.SettlementWorkflowType
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class CustomerOrderStatus {
    ACCEPTED_FOR_FULFILLMENT,
    DELIVERED,
    CANCELLED,
    RETURN_REQUESTED,
    REFUNDED,
}

enum class CustomerOrderEventType {
    ACCEPTED_FOR_FULFILLMENT,
    DELIVERED,
    RETURN_WINDOW_OPENED,
}

@Entity
@Table(name = "customer_orders")
class CustomerOrderRecord(
    @Id val id: String,
    @Column(name = "checkout_id", nullable = false, unique = true) val checkoutId: String,
    @Column(name = "customer_id", nullable = false) val customerId: String,
    @Enumerated(EnumType.STRING) @Column(name = "service_level", nullable = false) val serviceLevel: OrderServiceLevel,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val route: OrderRoute,
    @Enumerated(EnumType.STRING) @Column(name = "fulfillment_priority", nullable = false) val fulfillmentPriority: FulfillmentPriority,
    @Column(name = "requires_consolidation", nullable = false) val requiresConsolidation: Boolean,
    @Column(name = "customer_facing_status", nullable = false) val customerFacingStatus: String,
    @Column(name = "item_subtotal_cfa", nullable = false) val itemSubtotalCfa: Int,
    @Column(name = "delivery_fee_cfa", nullable = false) val deliveryFeeCfa: Int,
    @Column(name = "total_cfa", nullable = false) val totalCfa: Int,
    @Column(name = "payment_provider", nullable = false) val paymentProvider: String,
    @Column(name = "payment_reference", nullable = false) val paymentReference: String,
    @Column(name = "provider_reference") val providerReference: String?,
    @Enumerated(EnumType.STRING) @Column(name = "payment_status", nullable = false) val paymentStatus: PaymentValidationStatus,
    @Enumerated(EnumType.STRING) @Column(name = "order_status", nullable = false) var orderStatus: CustomerOrderStatus,
    @Column(name = "delivered_at") var deliveredAt: Instant? = null,
    @Column(name = "return_window_ends_at") var returnWindowEndsAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "order_events")
class CustomerOrderEventRecord(
    @Id val id: String,
    @Column(name = "order_id", nullable = false) val orderId: String,
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false) val eventType: CustomerOrderEventType,
    @Column(name = "source_type", nullable = false) val sourceType: String,
    @Column(name = "source_id", nullable = false) val sourceId: String,
    @Column(name = "actor_user_id") val actorUserId: String?,
    @Column(nullable = true) val metadata: String?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)

@Entity
@Table(name = "customer_order_lines")
class CustomerOrderLineRecord(
    @Id val id: String,
    @Column(name = "order_id", nullable = false) val orderId: String,
    @Column(name = "product_id", nullable = false) val productId: String,
    @Column(name = "seller_id", nullable = false) val sellerId: String,
    @Column(name = "seller_name", nullable = false) val sellerName: String,
    @Column(name = "product_name", nullable = false) val productName: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val category: OrderProductCategory,
    @Column(nullable = false) val quantity: Int,
    @Column(name = "unit_price_cfa", nullable = false) val unitPriceCfa: Int,
    @Column(name = "negotiated_unit_price_cfa") val negotiatedUnitPriceCfa: Int?,
    @Column(name = "effective_unit_price_cfa", nullable = false) val effectiveUnitPriceCfa: Int,
    @Column(name = "line_total_cfa", nullable = false) val lineTotalCfa: Int,
    @Column(name = "photo_evidence_type", nullable = false) val photoEvidenceType: String,
    @Column(name = "line_index", nullable = false) val lineIndex: Int,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)

@Entity
@Table(name = "order_pricing_snapshots")
class OrderPricingSnapshotRecord(
    @Id @Column(name = "order_id") val orderId: String,
    @Column(name = "checkout_id", nullable = false) val checkoutId: String,
    @Column(name = "customer_id", nullable = false) val customerId: String,
    @Column(name = "pricing_version", nullable = false) val pricingVersion: String,
    @Column(name = "quote_id") val quoteId: String?,
    @Column(nullable = false) val currency: String,
    @Enumerated(EnumType.STRING) @Column(name = "service_level", nullable = false) val serviceLevel: OrderServiceLevel,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val route: OrderRoute,
    @Column(name = "merchant_ids", nullable = false, length = 2000) val merchantIds: String,
    @Column(name = "item_subtotal_cfa", nullable = false) val itemSubtotalCfa: Int,
    @Column(name = "platform_margin_total_cfa", nullable = false) val platformMarginTotalCfa: Int,
    @Column(name = "service_fee_total_cfa", nullable = false) val serviceFeeTotalCfa: Int,
    @Column(name = "delivery_distance_km", nullable = false) val deliveryDistanceKm: Double,
    @Column(name = "raw_distance_meters") val rawDistanceMeters: Int?,
    @Column(name = "distance_source") val distanceSource: String?,
    @Column(name = "delivery_billable_km", nullable = false) val deliveryBillableKm: Int,
    @Column(name = "delivery_base_fee_cfa", nullable = false) val deliveryBaseFeeCfa: Int,
    @Column(name = "subscription_discount_cfa", nullable = false) val subscriptionDiscountCfa: Int,
    @Column(name = "referral_credit_applied_cfa", nullable = false) val referralCreditAppliedCfa: Int,
    @Column(name = "customer_delivery_fee_cfa", nullable = false) val customerDeliveryFeeCfa: Int,
    @Column(name = "courier_fee_estimate_cfa") val courierFeeEstimateCfa: Int?,
    @Column(name = "delivery_shortfall_estimate_cfa") val deliveryShortfallEstimateCfa: Int?,
    @Column(name = "bargaining_lock_ids", length = 2000) val bargainingLockIds: String?,
    @Column(name = "total_cfa", nullable = false) val totalCfa: Int,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)

interface CustomerOrderRecordRepository : JpaRepository<CustomerOrderRecord, String> {
    fun findByCheckoutId(checkoutId: String): CustomerOrderRecord?
    fun findByCustomerIdOrderByCreatedAtDesc(customerId: String): List<CustomerOrderRecord>
}

interface CustomerOrderLineRecordRepository : JpaRepository<CustomerOrderLineRecord, String> {
    fun findByOrderIdOrderByLineIndexAsc(orderId: String): List<CustomerOrderLineRecord>
}

interface CustomerOrderEventRecordRepository : JpaRepository<CustomerOrderEventRecord, String> {
    fun findByOrderIdOrderByCreatedAtAsc(orderId: String): List<CustomerOrderEventRecord>
}

interface OrderPricingSnapshotRecordRepository : JpaRepository<OrderPricingSnapshotRecord, String>

data class PersistedOrderFulfillment(
    val order: CustomerOrderSnapshot,
    val lines: List<CustomerOrderLineSnapshot>,
    val pricingSnapshot: OrderPricingAuditSnapshot?,
    val merchantSubOrders: List<MerchantSubOrderSnapshot>,
)

data class CustomerOrderSnapshot(
    val id: String,
    val checkoutId: String,
    val customerId: String,
    val serviceLevel: OrderServiceLevel,
    val route: OrderRoute,
    val fulfillmentPriority: FulfillmentPriority,
    val requiresConsolidation: Boolean,
    val customerFacingStatus: String,
    val itemSubtotalCfa: Int,
    val deliveryFeeCfa: Int,
    val totalCfa: Int,
    val paymentProvider: String,
    val paymentReference: String,
    val providerReference: String?,
    val paymentStatus: PaymentValidationStatus,
    val orderStatus: CustomerOrderStatus,
    val deliveredAt: Instant?,
    val returnWindowEndsAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CustomerOrderLineSnapshot(
    val id: String,
    val orderId: String,
    val productId: String,
    val sellerId: String,
    val sellerName: String,
    val productName: String,
    val category: OrderProductCategory,
    val quantity: Int,
    val unitPriceCfa: Int,
    val negotiatedUnitPriceCfa: Int?,
    val effectiveUnitPriceCfa: Int,
    val lineTotalCfa: Int,
    val photoEvidenceType: String,
    val lineIndex: Int,
)

data class CustomerOrderEventSnapshot(
    val id: String,
    val orderId: String,
    val eventType: CustomerOrderEventType,
    val sourceType: String,
    val sourceId: String,
    val actorUserId: String?,
    val metadata: String?,
    val createdAt: Instant,
)

data class CustomerOrderTimelineItem(
    val eventType: CustomerOrderEventType,
    val createdAt: Instant,
)

data class OrderPricingAuditSnapshot(
    val orderId: String,
    val checkoutId: String,
    val customerId: String,
    val pricingVersion: String,
    val quoteId: String?,
    val currency: String,
    val serviceLevel: OrderServiceLevel,
    val route: OrderRoute,
    val merchantIds: List<String>,
    val itemSubtotalCfa: Int,
    val platformMarginTotalCfa: Int,
    val serviceFeeTotalCfa: Int,
    val deliveryDistanceKm: Double,
    val rawDistanceMeters: Int?,
    val distanceSource: String?,
    val deliveryBillableKm: Int,
    val deliveryBaseFeeCfa: Int,
    val subscriptionDiscountCfa: Int,
    val referralCreditAppliedCfa: Int,
    val customerDeliveryFeeCfa: Int,
    val courierFeeEstimateCfa: Int?,
    val deliveryShortfallEstimateCfa: Int?,
    val bargainingLockIds: List<String>,
    val totalCfa: Int,
    val createdAt: Instant,
)

data class CustomerOrderDetails(
    val order: CustomerOrderSnapshot,
    val lines: List<CustomerOrderLineSnapshot>,
    val pricingSnapshot: OrderPricingAuditSnapshot?,
    val merchantSubOrders: List<MerchantSubOrderSnapshot>,
    val timeline: List<CustomerOrderTimelineItem>,
)

data class OrderFulfillmentResponse(
    val processing: OrderProcessingResult.AcceptedForFulfillment,
    val fulfillment: PersistedOrderFulfillment,
)

@Service
class OrderFulfillmentPersistenceService(
    private val orders: CustomerOrderRecordRepository,
    private val lines: CustomerOrderLineRecordRepository,
    private val events: CustomerOrderEventRecordRepository,
    private val pricingSnapshots: OrderPricingSnapshotRecordRepository,
    private val merchantFulfillment: MerchantFulfillmentService,
    private val commissions: MerchantCommissionConfigurationService,
) {
    @Transactional(readOnly = true)
    fun listForCustomer(customerId: String): List<CustomerOrderDetails> {
        require(customerId.isNotBlank()) { "customerId cannot be blank." }
        return orders.findByCustomerIdOrderByCreatedAtDesc(customerId).map(::toCustomerDetails)
    }

    @Transactional(readOnly = true)
    fun getForCustomer(orderId: String, customerId: String): CustomerOrderDetails? {
        require(orderId.isNotBlank()) { "orderId cannot be blank." }
        require(customerId.isNotBlank()) { "customerId cannot be blank." }
        val order = orders.findById(orderId).orElse(null) ?: return null
        if (order.customerId != customerId) return null
        return toCustomerDetails(order)
    }

    @Transactional
    fun persistAcceptedOrder(
        request: OrderProcessingRequest,
        accepted: OrderProcessingResult.AcceptedForFulfillment,
        createdAt: Instant = Instant.now(),
    ): PersistedOrderFulfillment {
        val existingOrder = orders.findByCheckoutId(request.checkoutId)
        val orderRecord = existingOrder ?: orders.save(accepted.toOrderRecord(request, createdAt))
        if (existingOrder == null) {
            request.lines.mapIndexed { index, line -> line.toLineRecord(accepted.order.orderId, index, createdAt) }
                .forEach(lines::save)
            pricingSnapshots.save(accepted.toPricingSnapshotRecord(request, createdAt))
            events.save(
                CustomerOrderEventRecord(
                    id = "${accepted.order.orderId}:accepted",
                    orderId = accepted.order.orderId,
                    eventType = CustomerOrderEventType.ACCEPTED_FOR_FULFILLMENT,
                    sourceType = "CHECKOUT",
                    sourceId = request.checkoutId,
                    actorUserId = request.customerId,
                    metadata = "Paid order accepted for merchant fulfillment.",
                    createdAt = createdAt,
                )
            )
        }
        val merchantSubOrders = createMissingMerchantSubOrders(accepted.order.orderId, request.lines)

        return PersistedOrderFulfillment(
            order = orderRecord.toSnapshot(),
            lines = lines.findByOrderIdOrderByLineIndexAsc(orderRecord.id).map { it.toSnapshot() },
            pricingSnapshot = pricingSnapshots.findById(orderRecord.id).orElse(null)?.toSnapshot(),
            merchantSubOrders = merchantSubOrders,
        )
    }

    private fun toCustomerDetails(order: CustomerOrderRecord): CustomerOrderDetails =
        CustomerOrderDetails(
            order = order.toSnapshot(),
            lines = lines.findByOrderIdOrderByLineIndexAsc(order.id).map { it.toSnapshot() },
            pricingSnapshot = pricingSnapshots.findById(order.id).orElse(null)?.toSnapshot(),
            merchantSubOrders = merchantFulfillment.listForOrder(order.id),
            timeline = events.findByOrderIdOrderByCreatedAtAsc(order.id).map {
                CustomerOrderTimelineItem(it.eventType, it.createdAt)
            },
        )

    private fun createMissingMerchantSubOrders(
        orderId: String,
        orderLines: List<OrderLineRequest>,
    ): List<MerchantSubOrderSnapshot> =
        orderLines.groupBy { it.sellerId }
            .map { (merchantId, sellerLines) ->
                val subOrderCode = "$orderId:$merchantId"
                merchantFulfillment.findBySubOrderCode(subOrderCode)
                    ?: merchantFulfillment.create(
                        sellerLines.toSubOrderCommand(
                            orderId = orderId,
                            merchantId = merchantId,
                            subOrderCode = subOrderCode,
                            commissions = commissions,
                        )
                    )
    }
}

@Service
class OrderDeliveryLifecycleService(
    private val orders: CustomerOrderRecordRepository,
    private val lines: CustomerOrderLineRecordRepository,
    private val events: CustomerOrderEventRecordRepository,
    private val merchantSubOrders: MerchantSubOrderRepository,
    private val settlements: SettlementPersistenceService,
    private val publisher: ApplicationEventPublisher,
) {
    private val objectMapper = ObjectMapper()

    @Transactional
    fun markDeliveredFromMission(
        mission: DeliveryMissionSnapshot,
        actorUserId: String,
        deliveredAt: Instant = mission.deliveredAt ?: Instant.now(),
    ): CustomerOrderSnapshot? {
        if (mission.status !in deliveryTerminalStatuses) return null
        val order = orders.findById(mission.orderId).orElse(null) ?: return null
        postDeliveryShortfall(mission, deliveredAt)
        if (order.orderStatus == CustomerOrderStatus.DELIVERED) {
            accrueMerchantPayouts(order, order.deliveredAt ?: deliveredAt)
            return order.toSnapshot()
        }

        val returnWindowEndsAt = deliveredAt.plusSeconds(72 * 60 * 60)
        order.orderStatus = CustomerOrderStatus.DELIVERED
        order.deliveredAt = deliveredAt
        order.returnWindowEndsAt = returnWindowEndsAt
        order.updatedAt = deliveredAt
        val saved = orders.save(order)

        saveEvent(
            CustomerOrderEventRecord(
                id = "${order.id}:delivered:${mission.id}",
                orderId = order.id,
                eventType = CustomerOrderEventType.DELIVERED,
                sourceType = "DELIVERY_MISSION",
                sourceId = mission.id,
                actorUserId = actorUserId,
                metadata = "Delivery mission ${mission.deliveryCode} completed.",
                createdAt = deliveredAt,
            )
        )
        saveEvent(
            CustomerOrderEventRecord(
                id = "${order.id}:return-window:${mission.id}",
                orderId = order.id,
                eventType = CustomerOrderEventType.RETURN_WINDOW_OPENED,
                sourceType = "DELIVERY_MISSION",
                sourceId = mission.id,
                actorUserId = actorUserId,
                metadata = "Return window ends at $returnWindowEndsAt.",
                createdAt = deliveredAt,
            )
        )
        accrueMerchantPayouts(saved, deliveredAt)
        publisher.publishEvent(saved.deliveryNotificationEvent(mission, lines.findByOrderIdOrderByLineIndexAsc(order.id)))
        return saved.toSnapshot()
    }

    @Transactional(readOnly = true)
    fun listEvents(orderId: String): List<CustomerOrderEventSnapshot> {
        require(orderId.isNotBlank()) { "orderId is required." }
        return events.findByOrderIdOrderByCreatedAtAsc(orderId).map { it.toSnapshot() }
    }

    private fun saveEvent(event: CustomerOrderEventRecord) {
        if (!events.existsById(event.id)) events.save(event)
    }

    private fun accrueMerchantPayouts(order: CustomerOrderRecord, deliveredAt: Instant) {
        merchantSubOrders.findByOrderId(order.id).forEach { subOrder ->
            val subOrderId = checkNotNull(subOrder.id) { "Persisted merchant sub-order id is required for settlement." }
            settlements.accrueMerchantPayout(
                MerchantPayoutAccrualCommand(
                    accrualId = "${order.id}:merchant-payout:$subOrderId",
                    merchantId = subOrder.merchantId,
                    orderId = order.id,
                    sourceOrderItemId = subOrderId,
                    merchantNetCfa = subOrder.merchantNetCfa,
                    commissionCfa = subOrder.commissionCfa,
                    platformMarginCfa = 0,
                    packageReceivedAt = deliveredAt,
                    workflowType = SettlementWorkflowType.DeliveryConfirmed,
                    activeReturnHold = true,
                )
            )
        }
    }

    private fun postDeliveryShortfall(mission: DeliveryMissionSnapshot, deliveredAt: Instant) {
        val courierId = mission.courierId?.takeIf { it.isNotBlank() } ?: return
        settlements.postDeliveryShortfall(
            DeliveryShortfallCommand(
                entryId = "${mission.id}:delivery-shortfall",
                deliveryMissionId = mission.id,
                customerDeliveryFeeCfa = mission.customerDeliveryFeeCfa,
                finalCourierFeeCfa = mission.courierFeeCfa,
                courierId = courierId,
                createdAt = deliveredAt,
            )
        )
    }

    private fun CustomerOrderRecord.deliveryNotificationEvent(
        mission: DeliveryMissionSnapshot,
        orderLines: List<CustomerOrderLineRecord>,
    ): NotificationWorkflowEvent =
        NotificationWorkflowEvent(
            eventId = "${id}:delivery-completed",
            eventType = NotificationEventType.DIRECT_DELIVERED,
            aggregateType = "ORDER",
            aggregateId = id,
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "customerUserId" to customerId,
                    "merchantUserIds" to orderLines.map { it.sellerId }.distinct(),
                    "title" to "Order delivered",
                    "body" to "Order $id was delivered. The return window is open for 72 hours.",
                    "actionUrl" to "/orders/$id",
                    "messagePayload" to mapOf(
                        "orderId" to id,
                        "deliveryMissionId" to mission.id,
                        "returnWindowEndsAt" to returnWindowEndsAt.toString(),
                    ),
                )
            ),
        )
}

private fun OrderProcessingResult.AcceptedForFulfillment.toOrderRecord(
    request: OrderProcessingRequest,
    createdAt: Instant,
) = CustomerOrderRecord(
    id = order.orderId,
    checkoutId = order.checkoutId,
    customerId = request.customerId,
    serviceLevel = order.serviceLevel,
    route = order.fulfillmentPlan.route,
    fulfillmentPriority = order.fulfillmentPlan.priority,
    requiresConsolidation = order.fulfillmentPlan.requiresConsolidation,
    customerFacingStatus = order.fulfillmentPlan.customerFacingStatus,
    itemSubtotalCfa = order.pricing.itemSubtotalCfa,
    deliveryFeeCfa = order.pricing.delivery.finalDeliveryFeeCfa,
    totalCfa = order.pricing.totalCfa,
    paymentProvider = request.paymentProvider.value,
    paymentReference = request.paymentReference,
    providerReference = order.payment.providerReference,
    paymentStatus = order.payment.status,
    orderStatus = CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT,
    deliveredAt = null,
    returnWindowEndsAt = null,
    createdAt = createdAt,
    updatedAt = createdAt,
)

private fun OrderProcessingResult.AcceptedForFulfillment.toPricingSnapshotRecord(
    request: OrderProcessingRequest,
    createdAt: Instant,
) = OrderPricingSnapshotRecord(
    orderId = order.orderId,
    checkoutId = order.checkoutId,
    customerId = request.customerId,
    pricingVersion = CURRENT_ORDER_PRICING_VERSION,
    quoteId = null,
    currency = "CFA",
    serviceLevel = order.serviceLevel,
    route = order.fulfillmentPlan.route,
    merchantIds = request.lines.map { it.sellerId }.distinct().sorted().joinToString(","),
    itemSubtotalCfa = order.pricing.itemSubtotalCfa,
    platformMarginTotalCfa = 0,
    serviceFeeTotalCfa = 0,
    deliveryDistanceKm = request.deliveryDistanceKm,
    rawDistanceMeters = null,
    distanceSource = "REQUEST_DISTANCE_KM",
    deliveryBillableKm = order.pricing.delivery.billableKm,
    deliveryBaseFeeCfa = order.pricing.delivery.baseFeeCfa,
    subscriptionDiscountCfa = order.pricing.delivery.subscriptionDiscountCfa,
    referralCreditAppliedCfa = order.pricing.delivery.referralCreditAppliedCfa,
    customerDeliveryFeeCfa = order.pricing.delivery.finalDeliveryFeeCfa,
    courierFeeEstimateCfa = null,
    deliveryShortfallEstimateCfa = null,
    bargainingLockIds = request.lines.mapIndexedNotNull { index, line ->
        line.negotiatedUnitPriceCfa?.let { "${order.orderId}:line:$index" }
    }.joinToString(",").ifBlank { null },
    totalCfa = order.pricing.totalCfa,
    createdAt = createdAt,
)

private fun OrderLineRequest.toLineRecord(orderId: String, index: Int, createdAt: Instant): CustomerOrderLineRecord {
    val effectiveUnitPrice = effectiveUnitPriceCfa
    val lineTotal = Math.multiplyExact(effectiveUnitPrice, quantity)
    return CustomerOrderLineRecord(
        id = "$orderId:line:$index",
        orderId = orderId,
        productId = productId,
        sellerId = sellerId,
        sellerName = sellerName,
        productName = productName,
        category = category,
        quantity = quantity,
        unitPriceCfa = unitPriceCfa,
        negotiatedUnitPriceCfa = negotiatedUnitPriceCfa,
        effectiveUnitPriceCfa = effectiveUnitPrice,
        lineTotalCfa = lineTotal,
        photoEvidenceType = photoEvidenceType(),
        lineIndex = index,
        createdAt = createdAt,
    )
}

private fun List<OrderLineRequest>.toSubOrderCommand(
    orderId: String,
    merchantId: String,
    subOrderCode: String,
    commissions: MerchantCommissionConfigurationService,
): CreateMerchantSubOrderCommand {
    val subtotal = sumOf { Math.multiplyExact(it.effectiveUnitPriceCfa, it.quantity) }
    val commission = commissions.calculateForMerchant(
        merchantId = merchantId,
        baseAmountCfa = subtotal,
        platformMarginCfa = 0,
    )
    return CreateMerchantSubOrderCommand(
        subOrderCode = subOrderCode,
        orderId = orderId,
        merchantId = merchantId,
        itemSubtotalCfa = subtotal,
        commissionRateBps = commission.commissionRateBps,
        commissionCfa = commission.commissionCfa,
        merchantNetCfa = commission.merchantNetCfa,
    )
}

private fun OrderLineRequest.photoEvidenceType(): String =
    when (photoEvidence) {
        is ProductPhotoEvidence.LiveCameraCapture -> "LIVE_CAMERA_CAPTURE"
        ProductPhotoEvidence.GenericCatalogImage -> "GENERIC_CATALOG_IMAGE"
        ProductPhotoEvidence.Missing -> "MISSING"
        ProductPhotoEvidence.GalleryUpload -> "GALLERY_UPLOAD"
    }

private fun CustomerOrderRecord.toSnapshot() = CustomerOrderSnapshot(
    id = id,
    checkoutId = checkoutId,
    customerId = customerId,
    serviceLevel = serviceLevel,
    route = route,
    fulfillmentPriority = fulfillmentPriority,
    requiresConsolidation = requiresConsolidation,
    customerFacingStatus = customerFacingStatus,
    itemSubtotalCfa = itemSubtotalCfa,
    deliveryFeeCfa = deliveryFeeCfa,
    totalCfa = totalCfa,
    paymentProvider = paymentProvider,
    paymentReference = paymentReference,
    providerReference = providerReference,
    paymentStatus = paymentStatus,
    orderStatus = orderStatus,
    deliveredAt = deliveredAt,
    returnWindowEndsAt = returnWindowEndsAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun CustomerOrderLineRecord.toSnapshot() = CustomerOrderLineSnapshot(
    id = id,
    orderId = orderId,
    productId = productId,
    sellerId = sellerId,
    sellerName = sellerName,
    productName = productName,
    category = category,
    quantity = quantity,
    unitPriceCfa = unitPriceCfa,
    negotiatedUnitPriceCfa = negotiatedUnitPriceCfa,
    effectiveUnitPriceCfa = effectiveUnitPriceCfa,
    lineTotalCfa = lineTotalCfa,
    photoEvidenceType = photoEvidenceType,
    lineIndex = lineIndex,
)

private fun OrderPricingSnapshotRecord.toSnapshot() = OrderPricingAuditSnapshot(
    orderId = orderId,
    checkoutId = checkoutId,
    customerId = customerId,
    pricingVersion = pricingVersion,
    quoteId = quoteId,
    currency = currency,
    serviceLevel = serviceLevel,
    route = route,
    merchantIds = merchantIds.splitCsv(),
    itemSubtotalCfa = itemSubtotalCfa,
    platformMarginTotalCfa = platformMarginTotalCfa,
    serviceFeeTotalCfa = serviceFeeTotalCfa,
    deliveryDistanceKm = deliveryDistanceKm,
    rawDistanceMeters = rawDistanceMeters,
    distanceSource = distanceSource,
    deliveryBillableKm = deliveryBillableKm,
    deliveryBaseFeeCfa = deliveryBaseFeeCfa,
    subscriptionDiscountCfa = subscriptionDiscountCfa,
    referralCreditAppliedCfa = referralCreditAppliedCfa,
    customerDeliveryFeeCfa = customerDeliveryFeeCfa,
    courierFeeEstimateCfa = courierFeeEstimateCfa,
    deliveryShortfallEstimateCfa = deliveryShortfallEstimateCfa,
    bargainingLockIds = bargainingLockIds?.splitCsv() ?: emptyList(),
    totalCfa = totalCfa,
    createdAt = createdAt,
)

private fun CustomerOrderEventRecord.toSnapshot() = CustomerOrderEventSnapshot(
    id = id,
    orderId = orderId,
    eventType = eventType,
    sourceType = sourceType,
    sourceId = sourceId,
    actorUserId = actorUserId,
    metadata = metadata,
    createdAt = createdAt,
)

private val deliveryTerminalStatuses = setOf(
    DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER,
    DeliveryMissionRecordStatus.RELEASED_BY_RELAY,
)

private fun String.splitCsv(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotBlank() }

private const val CURRENT_ORDER_PRICING_VERSION = "order-pricing-v1"
