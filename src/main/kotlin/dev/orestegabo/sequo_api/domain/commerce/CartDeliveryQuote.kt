package dev.orestegabo.sequo_api.domain.commerce

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressKind
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressRepository
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingInput
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.routing.GeoPoint
import dev.orestegabo.sequo_api.domain.routing.RoutingDistanceRequest
import dev.orestegabo.sequo_api.domain.routing.StraightLineRoutingDistanceProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class CartDeliveryQuote(
    val addressId: String,
    val distanceMeters: Int,
    val distanceSource: String,
    val billableKm: Int,
    val productTotalCfa: Int,
    val optionTotalCfa: Int,
    val serviceFeeCfa: Int,
    val deliveryFeeCfa: Int,
    val totalCfa: Int,
    val currency: String = "XOF",
)

@Service
class CartDeliveryQuoteService(
    private val commerce: CommerceService,
    private val addresses: CustomerAddressRepository,
    private val pricing: DeliveryPricingService,
    @Value("\${sequo.cart.straight-line-dispatch-origin:}") configuredOrigin: String,
) {
    private val origin = configuredOrigin.takeIf { it.isNotBlank() }?.let {
        val coordinates = it.split(",")
        require(coordinates.size == 2) { "Cart dispatch origin must be latitude,longitude." }
        GeoPoint(coordinates[0].trim().toDouble(), coordinates[1].trim().toDouble())
    }
    private val distanceProvider = StraightLineRoutingDistanceProvider()

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun quote(userId: String, addressId: String): CartDeliveryQuote {
        val address = requireNotNull(
            addresses.findByIdAndUserId(ApiInputPolicy.requiredIdentifier(addressId, "addressId"), userId)
                ?.takeIf { it.active && it.kind != CustomerAddressKind.RELAY },
        ) { "Delivery address is unavailable." }
        val dispatchOrigin = requireNotNull(origin) { "Delivery estimates are unavailable." }
        val latitude = requireNotNull(address.latitude) { "Delivery address requires coordinates." }
        val longitude = requireNotNull(address.longitude) { "Delivery address requires coordinates." }
        val cart = commerce.cart(userId)
        require(cart.items.isNotEmpty()) { "Cart is empty." }
        require(cart.items.all { it.available }) { "Remove or replace unavailable cart items." }
        val meters = distanceProvider.estimate(RoutingDistanceRequest(dispatchOrigin, GeoPoint(latitude, longitude)))
        val delivery = pricing.calculate(DeliveryPricingInput(distanceKm = meters / 1000.0))
        val total = cart.subtotalCfa.toLong() + delivery.finalDeliveryFeeCfa
        require(total <= Int.MAX_VALUE) { "Cart quote is too large." }
        return CartDeliveryQuote(
            addressId = address.id,
            distanceMeters = meters,
            distanceSource = distanceProvider.name,
            billableKm = delivery.billableKm,
            productTotalCfa = cart.items.sumOf { requireNotNull(it.productTotalCfa) },
            optionTotalCfa = cart.items.sumOf { requireNotNull(it.optionTotalCfa) },
            serviceFeeCfa = 0,
            deliveryFeeCfa = delivery.finalDeliveryFeeCfa,
            totalCfa = total.toInt(),
        )
    }
}

@RestController
class CartDeliveryQuoteController(private val quotes: CartDeliveryQuoteService) {
    @GetMapping("/api/cart/quote")
    fun quote(@AuthenticationPrincipal userId: String?, @RequestParam addressId: String) =
        authenticated(userId) { quotes.quote(it, addressId) }
}
