package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.payment.PaymentFeature
import dev.orestegabo.sequo_api.domain.payment.PaymentProcessor
import dev.orestegabo.sequo_api.domain.payment.PaymentProviderId
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationRequest
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationResult
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingBreakdown
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingInput
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService

enum class OrderServiceLevel {
    Regular,
    PrimeMonthly,
    PrimeMultiYear,
}

val OrderServiceLevel.isPrime: Boolean
    get() = this != OrderServiceLevel.Regular

enum class OrderRoute {
    FastDelivery,
    GroupedSequo,
    Pickup,
    PointDeRelai,
}

enum class OrderProductCategory {
    Food,
    Perishable,
    GeneralGoods,
    GenericSealedItem,
}

enum class OrderFlowStage(val label: String) {
    ValidateOrder("Validate order"),
    CalculateTotal("Calculate total"),
    ProcessPayment("Process payment"),
    SendConfirmation("Send confirmation"),
    SendToMerchant("Send to merchant"),
}

enum class OrderFlowStepStatus {
    Passed,
    Waiting,
    Failed,
}

enum class FulfillmentPriority {
    Standard,
    Prime,
}

sealed class ProductPhotoEvidence {
    data class LiveCameraCapture(val capturedAtEpochMillis: Long) : ProductPhotoEvidence()
    object GenericCatalogImage : ProductPhotoEvidence()
    object Missing : ProductPhotoEvidence()
    object GalleryUpload : ProductPhotoEvidence()
}

data class OrderLineRequest(
    val productId: String,
    val sellerId: String,
    val sellerName: String,
    val productName: String,
    val category: OrderProductCategory,
    val quantity: Int,
    val unitPriceCfa: Int,
    val negotiatedUnitPriceCfa: Int? = null,
    val photoEvidence: ProductPhotoEvidence,
) {
    val effectiveUnitPriceCfa: Int
        get() = negotiatedUnitPriceCfa ?: unitPriceCfa
}

data class OrderProcessingRequest(
    val checkoutId: String,
    val customerId: String,
    val serviceLevel: OrderServiceLevel,
    val route: OrderRoute,
    val lines: List<OrderLineRequest>,
    val deliveryDistanceKm: Double,
    val referralCreditCfa: Int = 0,
    val paymentProvider: PaymentProviderId,
    val paymentReference: String,
    val countryCode: String = "TG",
)

data class OrderPricingSnapshot(
    val itemSubtotalCfa: Int,
    val delivery: DeliveryPricingBreakdown,
    val totalCfa: Int,
)

data class OrderFlowEvent(
    val stage: OrderFlowStage,
    val status: OrderFlowStepStatus,
    val message: String,
)

data class FulfillmentPlan(
    val route: OrderRoute,
    val serviceLevel: OrderServiceLevel,
    val priority: FulfillmentPriority,
    val requiresConsolidation: Boolean,
    val customerFacingStatus: String,
)

data class OrderConfirmation(
    val title: String,
    val message: String,
)

data class ProcessedOrder(
    val orderId: String,
    val checkoutId: String,
    val serviceLevel: OrderServiceLevel,
    val pricing: OrderPricingSnapshot,
    val payment: PaymentValidationResult,
    val fulfillmentPlan: FulfillmentPlan,
    val confirmation: OrderConfirmation,
)

data class OrderRejectionReason(
    val code: String,
    val message: String,
)

sealed class OrderProcessingResult {
    abstract val events: List<OrderFlowEvent>

    data class AcceptedForFulfillment(
        val order: ProcessedOrder,
        override val events: List<OrderFlowEvent>,
    ) : OrderProcessingResult()

    data class AwaitingPaymentValidation(
        val pricing: OrderPricingSnapshot,
        val payment: PaymentValidationResult,
        override val events: List<OrderFlowEvent>,
    ) : OrderProcessingResult()

    data class Rejected(
        val reason: OrderRejectionReason,
        override val events: List<OrderFlowEvent>,
    ) : OrderProcessingResult()
}

abstract class OrderConfirmationSender {
    abstract fun send(order: ProcessedOrder): OrderConfirmation
}

class InAppOrderConfirmationSender : OrderConfirmationSender() {
    override fun send(order: ProcessedOrder): OrderConfirmation =
        OrderConfirmation(
            title = "Order paid",
            message = "Sequo confirmed ${order.pricing.totalCfa} CFA and sent the order for seller acceptance.",
        )
}

abstract class PrimeDeliveryDiscountPolicy {
    abstract fun discountPercent(serviceLevel: OrderServiceLevel): Int
}

class DefaultPrimeDeliveryDiscountPolicy(
    private val monthlyDiscountPercent: Int = 15,
    private val multiYearDiscountPercent: Int = 25,
) : PrimeDeliveryDiscountPolicy() {
    init {
        require(monthlyDiscountPercent in 0..100) { "monthlyDiscountPercent must be between 0 and 100" }
        require(multiYearDiscountPercent in 0..100) { "multiYearDiscountPercent must be between 0 and 100" }
        require(multiYearDiscountPercent > monthlyDiscountPercent) {
            "multiYearDiscountPercent must be deeper than monthlyDiscountPercent"
        }
    }

    override fun discountPercent(serviceLevel: OrderServiceLevel): Int =
        when (serviceLevel) {
            OrderServiceLevel.Regular -> 0
            OrderServiceLevel.PrimeMonthly -> monthlyDiscountPercent
            OrderServiceLevel.PrimeMultiYear -> multiYearDiscountPercent
        }
}

abstract class SequoOrderProcessor(
    private val paymentProcessor: PaymentProcessor,
    private val pricingService: DeliveryPricingService,
    private val confirmationSender: OrderConfirmationSender,
) {
    // Template Method: subclasses customize service-level differences while this class owns the invariant order flow.
    protected abstract val processorName: String

    fun process(request: OrderProcessingRequest): OrderProcessingResult {
        val events = mutableListOf<OrderFlowEvent>()

        validateOrder(request)?.let { rejection ->
            events.failed(OrderFlowStage.ValidateOrder, rejection.message)
            return OrderProcessingResult.Rejected(rejection, events.toList())
        }
        events.passed(OrderFlowStage.ValidateOrder, "Order checkout is valid for ${request.serviceLevel.name}.")

        val pricing = calculateTotal(request)
        events.passed(OrderFlowStage.CalculateTotal, "Total calculated from item subtotal and delivery snapshot.")

        val payment = processPayment(request, pricing)
        when (payment.status) {
            PaymentValidationStatus.Validated -> {
                events.passed(OrderFlowStage.ProcessPayment, "Payment validated by ${request.paymentProvider.value}.")
            }
            PaymentValidationStatus.Pending -> {
                events.waiting(OrderFlowStage.ProcessPayment, payment.message ?: "Waiting for provider validation.")
                return OrderProcessingResult.AwaitingPaymentValidation(
                    pricing = pricing,
                    payment = payment,
                    events = events.toList(),
                )
            }
            PaymentValidationStatus.Failed,
            PaymentValidationStatus.Cancelled,
            PaymentValidationStatus.BlockedByPolicy -> {
                val message = payment.message ?: "Payment was not validated."
                events.failed(OrderFlowStage.ProcessPayment, message)
                return OrderProcessingResult.Rejected(
                    reason = OrderRejectionReason("payment_not_validated", message),
                    events = events.toList(),
                )
            }
        }

        val provisionalOrder = ProcessedOrder(
            orderId = request.checkoutId.toOrderId(),
            checkoutId = request.checkoutId,
            serviceLevel = request.serviceLevel,
            pricing = pricing,
            payment = payment,
            fulfillmentPlan = planFulfillment(request),
            confirmation = OrderConfirmation(title = "", message = ""),
        )
        val confirmation = confirmationSender.send(provisionalOrder)
        val order = provisionalOrder.copy(confirmation = confirmation)
        events.passed(OrderFlowStage.SendConfirmation, "Customer confirmation was prepared.")
        events.passed(OrderFlowStage.SendToMerchant, "Paid order is ready for seller acceptance.")

        return OrderProcessingResult.AcceptedForFulfillment(order, events.toList())
    }

    protected open fun validateOrder(request: OrderProcessingRequest): OrderRejectionReason? {
        if (!supports(request.serviceLevel)) {
            return OrderRejectionReason(
                code = "unsupported_service_level",
                message = "$processorName cannot process ${request.serviceLevel.name} orders.",
            )
        }
        if (request.checkoutId.isBlank()) return OrderRejectionReason("missing_checkout_id", "Checkout id is required.")
        if (request.customerId.isBlank()) return OrderRejectionReason("missing_customer_id", "Customer id is required.")
        if (request.lines.isEmpty()) return OrderRejectionReason("empty_order", "Order must contain at least one item.")
        if (request.deliveryDistanceKm < 0.0) return OrderRejectionReason("invalid_distance", "Delivery distance must be non-negative.")
        if (request.referralCreditCfa < 0) return OrderRejectionReason("invalid_referral_credit", "Referral credit must be non-negative.")
        if (request.paymentReference.isBlank()) {
            return OrderRejectionReason("missing_payment_reference", "Payment reference is required before validation.")
        }

        request.lines.forEachIndexed { index, line ->
            validateLine(index, line)?.let { return it }
        }
        request.lines.subtotalCfaOrNull()
            ?: return OrderRejectionReason("subtotal_overflow", "Order item subtotal is too large.")
        return null
    }

    protected open fun calculateTotal(request: OrderProcessingRequest): OrderPricingSnapshot {
        val itemSubtotalCfa = request.lines.sumOf { it.checkedLineTotalCfa() }
        val delivery = if (request.route == OrderRoute.Pickup) {
            DeliveryPricingBreakdown(
                billableKm = 0,
                baseFeeCfa = 0,
                subscriptionDiscountCfa = 0,
                referralCreditAppliedCfa = 0,
                finalDeliveryFeeCfa = 0,
            )
        } else {
            pricingService.calculate(
                DeliveryPricingInput(
                    distanceKm = request.deliveryDistanceKm,
                    subscriptionDiscountPercent = deliveryDiscountPercent(request),
                    referralCreditCfa = request.referralCreditCfa,
                ),
            )
        }
        val totalCfa = checkedMoneySum(itemSubtotalCfa, delivery.finalDeliveryFeeCfa)

        return OrderPricingSnapshot(
            itemSubtotalCfa = itemSubtotalCfa,
            delivery = delivery,
            totalCfa = totalCfa,
        )
    }

    protected open fun processPayment(
        request: OrderProcessingRequest,
        pricing: OrderPricingSnapshot,
    ): PaymentValidationResult =
        paymentProcessor.validatePayment(
            PaymentValidationRequest(
                checkoutId = request.checkoutId,
                provider = request.paymentProvider,
                amountCfa = pricing.totalCfa,
                paymentReference = request.paymentReference,
                countryCode = request.countryCode,
                feature = PaymentFeature.CustomerCheckout,
            ),
        )

    protected open fun planFulfillment(request: OrderProcessingRequest): FulfillmentPlan {
        val requiresConsolidation = request.route == OrderRoute.GroupedSequo ||
            request.lines.map { it.sellerId }.distinct().size > 1
        val status = when (request.route) {
            OrderRoute.FastDelivery -> "Paid, waiting for seller preparation"
            OrderRoute.GroupedSequo -> "Paid, waiting for Sequo consolidation"
            OrderRoute.Pickup -> "Paid, waiting for pickup readiness"
            OrderRoute.PointDeRelai -> "Paid, waiting for Point de Relai routing"
        }

        return FulfillmentPlan(
            route = request.route,
            serviceLevel = request.serviceLevel,
            priority = fulfillmentPriority(request),
            requiresConsolidation = requiresConsolidation,
            customerFacingStatus = status,
        )
    }

    protected abstract fun supports(serviceLevel: OrderServiceLevel): Boolean

    protected abstract fun deliveryDiscountPercent(request: OrderProcessingRequest): Int

    protected abstract fun fulfillmentPriority(request: OrderProcessingRequest): FulfillmentPriority
}

class RegularOrderProcessor(
    paymentProcessor: PaymentProcessor,
    pricingService: DeliveryPricingService,
    confirmationSender: OrderConfirmationSender = InAppOrderConfirmationSender(),
) : SequoOrderProcessor(paymentProcessor, pricingService, confirmationSender) {
    override val processorName: String = "RegularOrderProcessor"

    override fun supports(serviceLevel: OrderServiceLevel): Boolean =
        serviceLevel == OrderServiceLevel.Regular

    override fun deliveryDiscountPercent(request: OrderProcessingRequest): Int = 0

    override fun fulfillmentPriority(request: OrderProcessingRequest): FulfillmentPriority =
        FulfillmentPriority.Standard
}

class PrimeOrderProcessor(
    paymentProcessor: PaymentProcessor,
    pricingService: DeliveryPricingService,
    confirmationSender: OrderConfirmationSender = InAppOrderConfirmationSender(),
    private val discountPolicy: PrimeDeliveryDiscountPolicy = DefaultPrimeDeliveryDiscountPolicy(),
) : SequoOrderProcessor(paymentProcessor, pricingService, confirmationSender) {
    override val processorName: String = "PrimeOrderProcessor"

    override fun supports(serviceLevel: OrderServiceLevel): Boolean =
        serviceLevel.isPrime

    override fun deliveryDiscountPercent(request: OrderProcessingRequest): Int =
        discountPolicy.discountPercent(request.serviceLevel)

    override fun fulfillmentPriority(request: OrderProcessingRequest): FulfillmentPriority =
        FulfillmentPriority.Prime
}

class OrderProcessorFactory(
    private val regularOrderProcessor: RegularOrderProcessor,
    private val primeOrderProcessor: PrimeOrderProcessor,
) {
    fun processorFor(serviceLevel: OrderServiceLevel): SequoOrderProcessor =
        if (serviceLevel.isPrime) primeOrderProcessor else regularOrderProcessor
}

private fun validateLine(index: Int, line: OrderLineRequest): OrderRejectionReason? {
    val prefix = "Line ${index + 1}"
    if (line.productId.isBlank()) return OrderRejectionReason("missing_product_id", "$prefix requires a product id.")
    if (line.sellerId.isBlank()) return OrderRejectionReason("missing_seller_id", "$prefix requires a seller id.")
    if (line.sellerName.isBlank()) return OrderRejectionReason("missing_seller_name", "$prefix requires a seller name.")
    if (line.productName.isBlank()) return OrderRejectionReason("missing_product_name", "$prefix requires a product name.")
    if (line.quantity <= 0) return OrderRejectionReason("invalid_quantity", "$prefix quantity must be positive.")
    if (line.unitPriceCfa <= 0) return OrderRejectionReason("invalid_unit_price", "$prefix unit price must be positive.")
    if (line.negotiatedUnitPriceCfa != null) {
        if (line.negotiatedUnitPriceCfa <= 0) {
            return OrderRejectionReason("invalid_negotiated_price", "$prefix negotiated price must be positive.")
        }
        if (line.negotiatedUnitPriceCfa > line.unitPriceCfa) {
            return OrderRejectionReason("invalid_negotiated_price", "$prefix negotiated price cannot exceed list price.")
        }
    }
    if (!line.hasAllowedPhotoEvidence()) {
        return OrderRejectionReason(
            code = "invalid_photo_evidence",
            message = "$prefix requires a real-time seller photo or an allowed generic catalog image.",
        )
    }
    line.checkedLineTotalCfaOrNull()
        ?: return OrderRejectionReason("line_total_overflow", "$prefix total is too large.")

    return null
}

private fun OrderLineRequest.hasAllowedPhotoEvidence(): Boolean =
    when (photoEvidence) {
        is ProductPhotoEvidence.LiveCameraCapture -> true
        ProductPhotoEvidence.GenericCatalogImage -> category == OrderProductCategory.GenericSealedItem
        ProductPhotoEvidence.Missing,
        ProductPhotoEvidence.GalleryUpload -> false
    }

private fun OrderLineRequest.checkedLineTotalCfa(): Int =
    checkedLineTotalCfaOrNull() ?: error("Line total overflow should be rejected during validation.")

private fun OrderLineRequest.checkedLineTotalCfaOrNull(): Int? {
    val total = effectiveUnitPriceCfa.toLong() * quantity.toLong()
    return if (total <= Int.MAX_VALUE) total.toInt() else null
}

private fun List<OrderLineRequest>.subtotalCfaOrNull(): Int? {
    var subtotal = 0L
    for (line in this) {
        subtotal += line.checkedLineTotalCfaOrNull() ?: return null
        if (subtotal > Int.MAX_VALUE) return null
    }
    return subtotal.toInt()
}

private fun checkedMoneySum(leftCfa: Int, rightCfa: Int): Int {
    val total = leftCfa.toLong() + rightCfa.toLong()
    require(total <= Int.MAX_VALUE) { "Order total is too large" }
    return total.toInt()
}

private fun String.toOrderId(): String =
    if (startsWith("SQ-")) this else "SQ-$this"

private fun MutableList<OrderFlowEvent>.passed(stage: OrderFlowStage, message: String) {
    add(OrderFlowEvent(stage, OrderFlowStepStatus.Passed, message))
}

private fun MutableList<OrderFlowEvent>.waiting(stage: OrderFlowStage, message: String) {
    add(OrderFlowEvent(stage, OrderFlowStepStatus.Waiting, message))
}

private fun MutableList<OrderFlowEvent>.failed(stage: OrderFlowStage, message: String) {
    add(OrderFlowEvent(stage, OrderFlowStepStatus.Failed, message))
}
