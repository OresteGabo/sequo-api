package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationSelection
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.relay.RelayDestination
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AdminOrderLineDto(
    val id: String, val productId: String, val sellerId: String, val sellerName: String,
    val productName: String, val category: OrderProductCategory, val quantity: Int,
    val unitPriceCfa: Int, val effectiveUnitPriceCfa: Int, val lineTotalCfa: Int,
    val selections: List<FoodCustomizationSelection>?, val optionLabel: String?,
)

data class AdminOrderHistoryDto(
    val id: String, val eventType: CustomerOrderEventType, val sourceType: String,
    val sourceId: String, val actorUserId: String?, val createdAt: Instant,
)

data class AdminOrderDetailDto(
    val order: AdminOrderDto, val lines: List<AdminOrderLineDto>,
    val homeDestination: HomeDeliveryDestination?, val relayDestination: RelayDestination?,
    val snapshotState: String, val history: List<AdminOrderHistoryDto>,
)

@Service
class AdminOrderDetailService(
    private val orders: CustomerOrderRecordRepository,
    private val lines: CustomerOrderLineRecordRepository,
    private val events: CustomerOrderEventRecordRepository,
    private val checkouts: PendingPaymentCheckoutRepository,
) {
    private val json = ObjectMapper()

    @Transactional(readOnly = true)
    fun detail(orderId: String): AdminOrderDetailDto? {
        val order = orders.findById(orderId).orElse(null) ?: return null
        // Read the immutable checkout snapshot, never the current catalog/profile or raw payload.
        val request = checkouts.findById(order.checkoutId).orElse(null)?.let { checkout ->
            if (checkout.customerId != order.customerId) null
            else runCatching { json.readPendingRequest(checkout.requestPayload) }.getOrNull()
                ?.takeIf { it.customerId == order.customerId && it.checkoutId == order.checkoutId }
        }
        val rows = lines.findByOrderIdOrderByLineIndexAsc(orderId)
        return AdminOrderDetailDto(order.toAdminDto(), rows.map { line ->
            val input = request?.lines?.getOrNull(line.lineIndex)?.takeIf {
                it.productId == line.productId && it.sellerId == line.sellerId && it.quantity == line.quantity
            }
            AdminOrderLineDto(line.id, line.productId, line.sellerId, line.sellerName, line.productName,
                line.category, line.quantity, line.unitPriceCfa, line.effectiveUnitPriceCfa, line.lineTotalCfa,
                input?.selections, input?.optionLabel)
        }, request?.homeDestination, request?.relayDestination,
            if (request == null) "UNAVAILABLE_LEGACY" else "RECORDED",
            events.findByOrderIdOrderByCreatedAtAsc(orderId).map {
                // Proof/free-text metadata may contain personal information; credentials are never projected.
                AdminOrderHistoryDto(it.id, it.eventType, it.sourceType, it.sourceId, it.actorUserId, it.createdAt)
            })
    }
}

@RestController
@RequestMapping("/api/admin/web/orders")
class AdminOrderDetailController(private val service: AdminOrderDetailService) {
    @GetMapping("/{orderId}")
    fun detail(authentication: Authentication?, @PathVariable orderId: String): ResponseEntity<Any> =
        adminControlRead(authentication) {
            service.detail(ApiInputPolicy.requiredIdentifier(orderId, "orderId"))
                ?.let { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(it) }
                ?: ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
        }
}

internal fun adminControlRead(authentication: Authentication?, operation: () -> ResponseEntity<Any>): ResponseEntity<Any> =
    if (authentication == null || !authentication.isAuthenticated) ResponseEntity.status(401).build()
    else if (!authentication.hasAnyRole(RoleGroups.AdminOnly)) ResponseEntity.status(403).build()
    else operation()
