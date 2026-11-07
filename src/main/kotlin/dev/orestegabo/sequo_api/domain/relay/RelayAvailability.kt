package dev.orestegabo.sequo_api.domain.relay

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.commerce.CommerceService
import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.order.OrderProductCategory
import dev.orestegabo.sequo_api.domain.order.readPendingRequest
import java.time.Instant
import java.time.ZoneId
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class RelayDestination(val id: String, val name: String, val city: String?, val neighborhood: String?,
    val landmark: String?, val latitude: Double, val longitude: Double)

fun orderCategory(category: String?, kind: CatalogProductKind): OrderProductCategory = when (category?.lowercase()) {
    "food" -> OrderProductCategory.Food
    "perishable" -> OrderProductCategory.Perishable
    "sensitive" -> if (kind == CatalogProductKind.GenericSealedItem) OrderProductCategory.GenericSealedItem else OrderProductCategory.Sensitive
    else -> if (kind == CatalogProductKind.GenericSealedItem) OrderProductCategory.GenericSealedItem else OrderProductCategory.GeneralGoods
}

fun OrderProductCategory.relayCategory(): RelayParcelCategory = when (this) {
    OrderProductCategory.Food -> RelayParcelCategory.Food
    OrderProductCategory.Perishable -> RelayParcelCategory.Perishable
    OrderProductCategory.Sensitive -> RelayParcelCategory.Sensitive
    else -> RelayParcelCategory.GeneralGoods
}

fun catalogRelayCategory(category: String?): RelayParcelCategory = when (category?.lowercase()) {
    "food", "grocery" -> RelayParcelCategory.Food
    "perishable" -> RelayParcelCategory.Perishable
    "sensitive", "pharmacy" -> RelayParcelCategory.Sensitive
    else -> RelayParcelCategory.GeneralGoods
}

fun relayCategoriesAllowed(categories: List<RelayParcelCategory>): Boolean =
    categories.isNotEmpty() && categories.all { RelayParcelPolicy().relayPickupAllowed(it) }

fun relayEligible(categories: List<OrderProductCategory>): Boolean =
    categories.isNotEmpty() && categories.all { RelayParcelPolicy().relayPickupAllowed(it.relayCategory()) }

fun readRelayDestination(payload: String): RelayDestination {
    val node = ObjectMapper().readTree(payload)
    fun optional(key: String) = node.path(key).takeIf { it.isTextual }?.asText()
    return RelayDestination(node["id"].asText(), node["name"].asText(), optional("city"), optional("neighborhood"),
        optional("landmark"), node["latitude"].asDouble(), node["longitude"].asDouble())
}

@Service
class RelayAvailabilityService(private val points: RelayPointRepository, private val lockers: RelayLockerRepository,
    private val hubs: HubMobileService) {
    @Transactional(readOnly = true)
    fun available(reroute: Boolean = false): List<RelayDestination> = points.findAll().mapNotNull { point ->
        if (point.status != "ACTIVE" || point.operatorUserId == null || point.latitude == null || point.longitude == null) return@mapNotNull null
        val latitude = requireNotNull(point.latitude); val longitude = requireNotNull(point.longitude)
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@mapNotNull null
        val control = hubs.getControlState(point.id)
        val required = setOf(HubControlTarget.LOCKER_INTAKE, HubControlTarget.CUSTOMER_PICKUP) +
            if (reroute) setOf(HubControlTarget.PLAN_B_DROP_OFF) else emptySet()
        if (control.hub.status != HubControlStatus.ACTIVE || control.services.any { it.target in required && it.status != HubControlStatus.ACTIVE }) return@mapNotNull null
        if (lockers.countByRelayPointIdAndStatus(point.id, HubLockerStatus.AVAILABLE) == 0L || !open(point.id)) return@mapNotNull null
        RelayDestination(point.id, point.name, point.city, point.neighborhood, point.landmark, latitude, longitude)
    }
    fun requireAvailable(id: String, reroute: Boolean = false): RelayDestination =
        requireNotNull(available(reroute).firstOrNull { it.id == id }) { "Relay point is unavailable." }

    private fun open(id: String): Boolean {
        val schedule = hubs.getOpeningHours(id)
        if (schedule.weeklyHours.isEmpty() && schedule.exceptions.isEmpty()) return true
        val local = Instant.now().atZone(ZoneId.of(schedule.timezone ?: "Africa/Lome"))
        val exception = schedule.exceptions.sortedByDescending { it.date }.firstOrNull {
            !local.toLocalDate().isBefore(it.date) && !local.toLocalDate().isAfter(it.effectiveUntil ?: it.date)
        }
        if (exception != null) return !exception.isClosed && exception.opensAt != null && exception.closesAt != null &&
            !local.toLocalTime().isBefore(exception.opensAt) && local.toLocalTime().isBefore(exception.closesAt)
        val day = schedule.weeklyHours.firstOrNull { it.dayOfWeek == local.dayOfWeek } ?: return false
        return day.isOpen && day.opensAt != null && day.closesAt != null &&
            !local.toLocalTime().isBefore(day.opensAt) && local.toLocalTime().isBefore(day.closesAt)
    }
}

@RestController
class CartRelayController(private val commerce: CommerceService, private val availability: RelayAvailabilityService) {
    @GetMapping("/api/cart/relay-points")
    fun points(@AuthenticationPrincipal userId: String?): List<RelayDestination> {
        requireNotNull(userId) { "Authentication is required." }
        val cart = commerce.cart(userId)
        return if (cart.items.all { it.available } && relayCategoriesAllowed(cart.items.map { catalogRelayCategory(it.product.category) }))
            availability.available() else emptyList()
    }
}

@Service
class RelayOrderEligibilityService(
    private val orders: dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository,
    private val lines: dev.orestegabo.sequo_api.domain.order.CustomerOrderLineRecordRepository,
    private val pending: dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRepository,
    private val products: dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository,
) {
    fun categories(orderId: String): List<RelayParcelCategory> {
        val order = orders.findById(orderId).orElse(null)
        val request = order?.let { pending.findById(it.checkoutId).orElse(null) }?.requestPayload?.let {
            ObjectMapper().readPendingRequest(it)
        }
        return lines.findByOrderIdOrderByLineIndexAsc(orderId).map { line ->
            request?.lines?.firstOrNull { it.productId == line.productId }?.relayCategory
                ?: products.findById(line.productId).orElse(null)?.let { catalogRelayCategory(it.category) }
                ?: line.category.relayCategory()
        }
    }
}
