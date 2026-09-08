package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.delivery.CreateMerchantSubOrderCommand
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentService
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderSnapshot
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Version @Column(nullable = false) var version: Long = 0,
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

interface CustomerOrderRecordRepository : JpaRepository<CustomerOrderRecord, String> {
    fun findByCheckoutId(checkoutId: String): CustomerOrderRecord?
}

interface CustomerOrderLineRecordRepository : JpaRepository<CustomerOrderLineRecord, String> {
    fun findByOrderIdOrderByLineIndexAsc(orderId: String): List<CustomerOrderLineRecord>
}

data class PersistedOrderFulfillment(
    val order: CustomerOrderSnapshot,
    val lines: List<CustomerOrderLineSnapshot>,
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

data class OrderFulfillmentResponse(
    val processing: OrderProcessingResult.AcceptedForFulfillment,
    val fulfillment: PersistedOrderFulfillment,
)

@Service
class OrderFulfillmentPersistenceService(
    private val orders: CustomerOrderRecordRepository,
    private val lines: CustomerOrderLineRecordRepository,
    private val merchantFulfillment: MerchantFulfillmentService,
) {
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
        }
        val merchantSubOrders = createMissingMerchantSubOrders(accepted.order.orderId, request.lines)

        return PersistedOrderFulfillment(
            order = orderRecord.toSnapshot(),
            lines = lines.findByOrderIdOrderByLineIndexAsc(orderRecord.id).map { it.toSnapshot() },
            merchantSubOrders = merchantSubOrders,
        )
    }

    private fun createMissingMerchantSubOrders(
        orderId: String,
        orderLines: List<OrderLineRequest>,
    ): List<MerchantSubOrderSnapshot> =
        orderLines.groupBy { it.sellerId }
            .map { (merchantId, sellerLines) ->
                val subOrderCode = "$orderId:$merchantId"
                merchantFulfillment.findBySubOrderCode(subOrderCode)
                    ?: merchantFulfillment.create(sellerLines.toSubOrderCommand(orderId, merchantId, subOrderCode))
            }
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
    createdAt = createdAt,
    updatedAt = createdAt,
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
): CreateMerchantSubOrderCommand {
    val subtotal = sumOf { Math.multiplyExact(it.effectiveUnitPriceCfa, it.quantity) }
    val commission = subtotal * 1500 / 10_000
    return CreateMerchantSubOrderCommand(
        subOrderCode = subOrderCode,
        orderId = orderId,
        merchantId = merchantId,
        itemSubtotalCfa = subtotal,
        commissionRateBps = 1500,
        commissionCfa = commission,
        merchantNetCfa = subtotal - commission,
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
