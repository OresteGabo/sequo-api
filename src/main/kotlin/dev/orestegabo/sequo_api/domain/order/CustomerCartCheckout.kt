package dev.orestegabo.sequo_api.domain.order

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.api.ApiErrorResponse
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.commerce.CartDeliveryQuoteService
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceService
import dev.orestegabo.sequo_api.domain.payment.PaymentProcessor
import dev.orestegabo.sequo_api.domain.payment.SequoPaymentProviders
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import java.security.MessageDigest
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

internal const val CART_CHECKOUT_PREFIX = "cart-"

data class SubmitCartCheckoutRequest(val addressId: String, val quoteRevision: String, val paymentProvider: String, val relayPointId: String? = null, val deliveryMode: String? = null)

data class CustomerCheckoutSummary(
    val checkoutId: String,
    val orderId: String?,
    val status: PendingPaymentCheckoutStatus,
    val totalCfa: Int,
    val paymentProvider: String,
    val destinationLabel: String?,
    val createdAt: Instant,
    val lines: List<CheckoutLineSummary>,
)

data class CheckoutLineSummary(val productName: String, val quantity: Int)

internal class CheckoutQuoteChanged : RuntimeException()

@Service
class CustomerCartCheckoutService(
    private val quotes: CartDeliveryQuoteService,
    private val options: dev.orestegabo.sequo_api.domain.delivery.DeliveryOptionsService,
    private val users: UserRepository,
    private val checkouts: PendingPaymentCheckoutRepository,
    private val pending: PendingPaymentCheckoutService,
    private val fulfillment: OrderFulfillmentPersistenceService,
    private val merchants: CommerceMerchantRepository,
    private val commerce: CommerceService,
    paymentProcessor: PaymentProcessor,
    pricing: DeliveryPricingService,
) {
    private val processor = RegularOrderProcessor(paymentProcessor, pricing)
    private val mapper = ObjectMapper()

    @Transactional
    fun submit(customerId: String, request: SubmitCartCheckoutRequest): CustomerCheckoutSummary {
        require(request.quoteRevision.matches(Regex("[a-f0-9]{64}"))) { "A reviewed quote is required." }
        val addressId = ApiInputPolicy.requiredIdentifier(request.addressId, "addressId")
        val provider = when (request.paymentProvider) {
            SequoPaymentProviders.YasTogo.value -> SequoPaymentProviders.YasTogo
            SequoPaymentProviders.MoovAfrica.value -> SequoPaymentProviders.MoovAfrica
            else -> throw IllegalArgumentException("Payment provider is not supported.")
        }
        require(users.lockCheckoutOwner(customerId)?.active == true) { "Customer account is unavailable." }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(mapper.writeValueAsBytes(listOf(customerId, request.quoteRevision))).toHexString()
        val checkoutId = "$CART_CHECKOUT_PREFIX$digest"
        checkouts.findByCheckoutIdAndCustomerId(checkoutId, customerId)?.let {
            val existing = mapper.readPendingRequest(it.requestPayload)
            require(existing.homeDestination?.addressId == addressId && existing.relayDestination?.id == request.relayPointId && (request.deliveryMode == null || existing.deliveryOption?.id?.name == request.deliveryMode) && it.paymentProvider == provider.value) {
                "Checkout retry must use the original address and payment provider."
            }
            return it.summary(existing)
        }

        val reviewed = try {
            quotes.review(customerId, addressId, request.relayPointId, request.deliveryMode)
        } catch (_: IllegalArgumentException) {
            throw CheckoutQuoteChanged()
        }
        if (reviewed.quote.revision != request.quoteRevision) throw CheckoutQuoteChanged()
        val sellers = merchants.findAllById(reviewed.cart.items.mapNotNull { it.product.merchantId }.distinct()).associateBy { it.id }
        val lines = reviewed.cart.items.map { item ->
            val product = item.product
            val seller = requireNotNull(sellers[product.merchantId]?.takeIf { it.status == "ACTIVE" }) {
                "A basket item has no active seller."
            }
            val category = dev.orestegabo.sequo_api.domain.relay.orderCategory(product.category, product.kind)
            val capturedAt = product.capturedAtLabel?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val photo = when {
                product.cameraVerified && capturedAt != null -> ProductPhotoEvidence.LiveCameraCapture(capturedAt.toEpochMilli())
                product.kind == CatalogProductKind.GenericSealedItem && !product.photoRequired && !product.imageUrl.isNullOrBlank() ->
                    ProductPhotoEvidence.GenericCatalogImage
                else -> ProductPhotoEvidence.Missing
            }
            OrderLineRequest(
                product.id, seller.id, seller.name, product.name, category, item.quantity,
                requireNotNull(product.priceCfa) + requireNotNull(item.optionTotalCfa) / item.quantity,
                negotiatedUnitPriceCfa = item.negotiatedUnitPriceCfa,
                bargainingLockId = item.bargainingThreadId, photoEvidence = photo, selections = item.selections,
                relayCategory = dev.orestegabo.sequo_api.domain.relay.catalogRelayCategory(product.category),
                optionLabel = item.selections.flatMap { selection ->
                    product.customizations.firstOrNull { it.id == selection.groupId }?.options.orEmpty()
                        .filter { it.id in selection.optionIds }.map { it.name }
                }.takeIf { it.isNotEmpty() }?.joinToString(", "),
            )
        }
        val order = OrderProcessingRequest(
            checkoutId, customerId, OrderServiceLevel.Regular, if (reviewed.relayDestination == null) OrderRoute.FastDelivery else OrderRoute.PointDeRelai,
            lines, reviewed.quote.distanceMeters / 1000.0,
            paymentProvider = provider, paymentReference = checkoutId, homeDestination = reviewed.destination, relayDestination = reviewed.relayDestination,
            deliveryOption = reviewed.quote.deliveryOption, subscriberPriority = options.subscriberPriority(customerId),
        )
        val result = processor.process(order, reviewed.pricing)
        val status = when (result) {
            is OrderProcessingResult.AwaitingPaymentValidation -> PendingPaymentCheckoutStatus.AWAITING_WEBHOOK
            is OrderProcessingResult.AcceptedForFulfillment -> PendingPaymentCheckoutStatus.COMPLETED
            is OrderProcessingResult.Rejected -> PendingPaymentCheckoutStatus.FAILED
        }
        val saved = pending.recordCartCheckout(order, reviewed.pricing, status)
        if (result is OrderProcessingResult.AcceptedForFulfillment) {
            fulfillment.persistAcceptedOrder(order, result)
        }
        if (status != PendingPaymentCheckoutStatus.FAILED) commerce.clearCart(customerId)
        return saved.summary(order)
    }

    @Transactional(readOnly = true)
    fun list(customerId: String): List<CustomerCheckoutSummary> =
        checkouts.findByCustomerIdOrderByCreatedAtDesc(customerId).map { it.summary(mapper.readPendingRequest(it.requestPayload)) }

    @Transactional(readOnly = true)
    fun get(customerId: String, checkoutId: String): CustomerCheckoutSummary? =
        checkouts.findByCheckoutIdAndCustomerId(ApiInputPolicy.requiredIdentifier(checkoutId, "checkoutId"), customerId)
            ?.let { it.summary(mapper.readPendingRequest(it.requestPayload)) }

    private fun PendingPaymentCheckoutRecord.summary(request: OrderProcessingRequest) = CustomerCheckoutSummary(
        checkoutId,
        if (status == PendingPaymentCheckoutStatus.COMPLETED) checkoutId.toOrderId() else null,
        status, amountCfa, paymentProvider, request.relayDestination?.name ?: request.homeDestination?.label, createdAt,
        request.lines.map { CheckoutLineSummary(it.productName, it.quantity) },
    )
}

@RestController
@RequestMapping("/api/orders/checkouts")
class CustomerCartCheckoutController(private val checkouts: CustomerCartCheckoutService) {
    @PostMapping
    fun submit(@AuthenticationPrincipal customerId: String?, @RequestBody request: SubmitCartCheckoutRequest): ResponseEntity<Any> {
        if (customerId == null) return ResponseEntity.status(401).build()
        return try {
            val checkout = checkouts.submit(customerId, request)
            val status = when (checkout.status) {
                PendingPaymentCheckoutStatus.AWAITING_WEBHOOK -> HttpStatus.ACCEPTED
                PendingPaymentCheckoutStatus.FAILED, PendingPaymentCheckoutStatus.CANCELLED -> HttpStatus.UNPROCESSABLE_ENTITY
                PendingPaymentCheckoutStatus.COMPLETED -> HttpStatus.OK
            }
            ResponseEntity.status(status).body(checkout)
        } catch (_: CheckoutQuoteChanged) {
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse("quote_changed", "The basket or delivery quote changed. Review it before submitting again."))
        }
    }

    @GetMapping
    fun list(@AuthenticationPrincipal customerId: String?): ResponseEntity<Any> =
        if (customerId == null) ResponseEntity.status(401).build() else ResponseEntity.ok(checkouts.list(customerId))

    @GetMapping("/{checkoutId}")
    fun get(@AuthenticationPrincipal customerId: String?, @PathVariable checkoutId: String): ResponseEntity<Any> {
        if (customerId == null) return ResponseEntity.status(401).build()
        return checkouts.get(customerId, checkoutId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }
}
