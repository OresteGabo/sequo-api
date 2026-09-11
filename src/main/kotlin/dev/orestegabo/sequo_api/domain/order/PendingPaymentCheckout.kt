package dev.orestegabo.sequo_api.domain.order

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.payment.PaymentProviderId
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationResult
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookAcceptedEvent
import dev.orestegabo.sequo_api.domain.payment.PaymentWebhookProviderStatus
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingBreakdown
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

enum class PendingPaymentCheckoutStatus {
    AWAITING_WEBHOOK,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Entity
@Table(name = "pending_payment_checkouts")
class PendingPaymentCheckoutRecord(
    @Id @Column(name = "checkout_id") val checkoutId: String,
    @Column(name = "customer_id", nullable = false) val customerId: String,
    @Column(name = "payment_provider", nullable = false, length = 64) val paymentProvider: String,
    @Column(name = "payment_reference", nullable = false) val paymentReference: String,
    @Column(name = "amount_cfa", nullable = false) val amountCfa: Int,
    @Column(name = "request_payload", nullable = false, columnDefinition = "text") val requestPayload: String,
    @Column(name = "pricing_payload", nullable = false, columnDefinition = "text") val pricingPayload: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var status: PendingPaymentCheckoutStatus,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "completed_at") var completedAt: Instant? = null,
    @Column(name = "provider_reference") var providerReference: String? = null,
)

interface PendingPaymentCheckoutRepository : JpaRepository<PendingPaymentCheckoutRecord, String>

sealed class PaymentReconciliationResult {
    data class Fulfilled(val fulfillment: PersistedOrderFulfillment) : PaymentReconciliationResult()
    data class Ignored(val reason: String) : PaymentReconciliationResult()
}

@Service
class PendingPaymentCheckoutService(
    private val pendingCheckouts: PendingPaymentCheckoutRepository,
    private val fulfillmentPersistence: OrderFulfillmentPersistenceService,
) {
    private val objectMapper = ObjectMapper()

    @Transactional
    fun recordAwaitingWebhook(
        request: OrderProcessingRequest,
        result: OrderProcessingResult.AwaitingPaymentValidation,
        createdAt: Instant = Instant.now(),
    ) {
        val existing = pendingCheckouts.findById(request.checkoutId).orElse(null)
        if (existing != null) {
            require(existing.customerId == request.customerId) { "Pending checkout customer mismatch." }
            require(existing.paymentProvider == request.paymentProvider.value) { "Pending checkout provider mismatch." }
            require(existing.paymentReference == request.paymentReference) { "Pending checkout payment reference mismatch." }
            require(existing.amountCfa == result.pricing.totalCfa) { "Pending checkout amount mismatch." }
            return
        }

        pendingCheckouts.save(
            PendingPaymentCheckoutRecord(
                checkoutId = request.checkoutId,
                customerId = request.customerId,
                paymentProvider = request.paymentProvider.value,
                paymentReference = request.paymentReference,
                amountCfa = result.pricing.totalCfa,
                requestPayload = objectMapper.writeValueAsString(request.toPendingPayload()),
                pricingPayload = objectMapper.writeValueAsString(result.pricing),
                status = PendingPaymentCheckoutStatus.AWAITING_WEBHOOK,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reconcile(event: PaymentWebhookAcceptedEvent): PaymentReconciliationResult {
        val pending = pendingCheckouts.findById(event.checkoutId).orElse(null)
            ?: return PaymentReconciliationResult.Ignored("pending_checkout_not_found")

        if (pending.status == PendingPaymentCheckoutStatus.COMPLETED) {
            val order = fulfillmentPersistence.getForCustomer(pending.checkoutId.toOrderId(), pending.customerId)
            return if (order != null) {
                PaymentReconciliationResult.Fulfilled(
                    PersistedOrderFulfillment(
                        order = order.order,
                        lines = order.lines,
                        pricingSnapshot = order.pricingSnapshot,
                        merchantSubOrders = order.merchantSubOrders,
                    )
                )
            } else {
                PaymentReconciliationResult.Ignored("completed_checkout_without_order")
            }
        }
        if (pending.status != PendingPaymentCheckoutStatus.AWAITING_WEBHOOK) {
            return PaymentReconciliationResult.Ignored("checkout_not_awaiting_webhook")
        }

        if (event.provider.value != pending.paymentProvider) return PaymentReconciliationResult.Ignored("provider_mismatch")
        if (event.paymentReference != pending.paymentReference) return PaymentReconciliationResult.Ignored("payment_reference_mismatch")
        if (event.amountCfa != pending.amountCfa) return PaymentReconciliationResult.Ignored("amount_mismatch")

        when (event.status) {
            PaymentWebhookProviderStatus.PENDING -> return PaymentReconciliationResult.Ignored("webhook_still_pending")
            PaymentWebhookProviderStatus.FAILED,
            PaymentWebhookProviderStatus.CANCELLED -> {
                val now = event.receivedAt
                pending.status = if (event.status == PaymentWebhookProviderStatus.FAILED) {
                    PendingPaymentCheckoutStatus.FAILED
                } else {
                    PendingPaymentCheckoutStatus.CANCELLED
                }
                pending.updatedAt = now
                pending.completedAt = now
                pending.providerReference = event.providerReference
                pendingCheckouts.save(pending)
                return PaymentReconciliationResult.Ignored("payment_${event.status.name.lowercase()}")
            }
            PaymentWebhookProviderStatus.VALIDATED -> Unit
        }

        val request = objectMapper.readPendingRequest(pending.requestPayload)
        val pricing = objectMapper.readPricingSnapshot(pending.pricingPayload)
        val accepted = request.toAcceptedForFulfillment(pricing, event.providerReference ?: event.paymentReference)
        val fulfilled = fulfillmentPersistence.persistAcceptedOrder(request, accepted, event.occurredAt)

        pending.status = PendingPaymentCheckoutStatus.COMPLETED
        pending.updatedAt = event.receivedAt
        pending.completedAt = event.receivedAt
        pending.providerReference = event.providerReference ?: event.paymentReference
        pendingCheckouts.save(pending)

        return PaymentReconciliationResult.Fulfilled(fulfilled)
    }
}

@Component
class PaymentWebhookReconciliationListener(
    private val pendingPaymentCheckouts: PendingPaymentCheckoutService,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onPaymentWebhookAccepted(event: PaymentWebhookAcceptedEvent) {
        pendingPaymentCheckouts.reconcile(event)
    }
}

data class PendingOrderProcessingRequestPayload(
    val checkoutId: String,
    val customerId: String,
    val serviceLevel: OrderServiceLevel,
    val route: OrderRoute,
    val lines: List<PendingOrderLineRequestPayload>,
    val deliveryDistanceKm: Double,
    val referralCreditCfa: Int,
    val paymentProvider: String,
    val paymentReference: String,
    val countryCode: String,
)

data class PendingOrderLineRequestPayload(
    val productId: String,
    val sellerId: String,
    val sellerName: String,
    val productName: String,
    val category: OrderProductCategory,
    val quantity: Int,
    val unitPriceCfa: Int,
    val negotiatedUnitPriceCfa: Int?,
    val photoEvidenceType: String,
    val capturedAtEpochMillis: Long?,
)

private fun OrderProcessingRequest.toPendingPayload() = PendingOrderProcessingRequestPayload(
    checkoutId = checkoutId,
    customerId = customerId,
    serviceLevel = serviceLevel,
    route = route,
    lines = lines.map { it.toPendingPayload() },
    deliveryDistanceKm = deliveryDistanceKm,
    referralCreditCfa = referralCreditCfa,
    paymentProvider = paymentProvider.value,
    paymentReference = paymentReference,
    countryCode = countryCode,
)

private fun OrderLineRequest.toPendingPayload() = PendingOrderLineRequestPayload(
    productId = productId,
    sellerId = sellerId,
    sellerName = sellerName,
    productName = productName,
    category = category,
    quantity = quantity,
    unitPriceCfa = unitPriceCfa,
    negotiatedUnitPriceCfa = negotiatedUnitPriceCfa,
    photoEvidenceType = when (val evidence = photoEvidence) {
        is ProductPhotoEvidence.LiveCameraCapture -> "LIVE_CAMERA_CAPTURE"
        ProductPhotoEvidence.GenericCatalogImage -> "GENERIC_CATALOG_IMAGE"
        ProductPhotoEvidence.Missing -> "MISSING"
        ProductPhotoEvidence.GalleryUpload -> "GALLERY_UPLOAD"
    },
    capturedAtEpochMillis = (photoEvidence as? ProductPhotoEvidence.LiveCameraCapture)?.capturedAtEpochMillis,
)

private fun PendingOrderProcessingRequestPayload.toRequest() = OrderProcessingRequest(
    checkoutId = checkoutId,
    customerId = customerId,
    serviceLevel = serviceLevel,
    route = route,
    lines = lines.map { it.toRequest() },
    deliveryDistanceKm = deliveryDistanceKm,
    referralCreditCfa = referralCreditCfa,
    paymentProvider = PaymentProviderId(paymentProvider),
    paymentReference = paymentReference,
    countryCode = countryCode,
)

private fun PendingOrderLineRequestPayload.toRequest() = OrderLineRequest(
    productId = productId,
    sellerId = sellerId,
    sellerName = sellerName,
    productName = productName,
    category = category,
    quantity = quantity,
    unitPriceCfa = unitPriceCfa,
    negotiatedUnitPriceCfa = negotiatedUnitPriceCfa,
    photoEvidence = when (photoEvidenceType) {
        "LIVE_CAMERA_CAPTURE" -> ProductPhotoEvidence.LiveCameraCapture(capturedAtEpochMillis ?: 0)
        "GENERIC_CATALOG_IMAGE" -> ProductPhotoEvidence.GenericCatalogImage
        "MISSING" -> ProductPhotoEvidence.Missing
        "GALLERY_UPLOAD" -> ProductPhotoEvidence.GalleryUpload
        else -> throw IllegalArgumentException("Unsupported pending checkout photo evidence type.")
    },
)

private fun ObjectMapper.readPendingRequest(payload: String): OrderProcessingRequest {
    val root = readTree(payload)
    return OrderProcessingRequest(
        checkoutId = root.requiredText("checkoutId"),
        customerId = root.requiredText("customerId"),
        serviceLevel = OrderServiceLevel.valueOf(root.requiredText("serviceLevel")),
        route = OrderRoute.valueOf(root.requiredText("route")),
        lines = root.requiredArray("lines").map { line ->
            PendingOrderLineRequestPayload(
                productId = line.requiredText("productId"),
                sellerId = line.requiredText("sellerId"),
                sellerName = line.requiredText("sellerName"),
                productName = line.requiredText("productName"),
                category = OrderProductCategory.valueOf(line.requiredText("category")),
                quantity = line.requiredInt("quantity"),
                unitPriceCfa = line.requiredInt("unitPriceCfa"),
                negotiatedUnitPriceCfa = line.optionalInt("negotiatedUnitPriceCfa"),
                photoEvidenceType = line.requiredText("photoEvidenceType"),
                capturedAtEpochMillis = line.optionalLong("capturedAtEpochMillis"),
            ).toRequest()
        },
        deliveryDistanceKm = root.requiredDouble("deliveryDistanceKm"),
        referralCreditCfa = root.requiredInt("referralCreditCfa"),
        paymentProvider = PaymentProviderId(root.requiredText("paymentProvider")),
        paymentReference = root.requiredText("paymentReference"),
        countryCode = root.requiredText("countryCode"),
    )
}

private fun ObjectMapper.readPricingSnapshot(payload: String): OrderPricingSnapshot {
    val root = readTree(payload)
    val delivery = root.requiredObject("delivery")
    return OrderPricingSnapshot(
        itemSubtotalCfa = root.requiredInt("itemSubtotalCfa"),
        delivery = DeliveryPricingBreakdown(
            billableKm = delivery.requiredInt("billableKm"),
            baseFeeCfa = delivery.requiredInt("baseFeeCfa"),
            subscriptionDiscountCfa = delivery.requiredInt("subscriptionDiscountCfa"),
            referralCreditAppliedCfa = delivery.requiredInt("referralCreditAppliedCfa"),
            finalDeliveryFeeCfa = delivery.requiredInt("finalDeliveryFeeCfa"),
        ),
        totalCfa = root.requiredInt("totalCfa"),
    )
}

private fun com.fasterxml.jackson.databind.JsonNode.requiredText(name: String): String =
    get(name)?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
        ?: throw IllegalArgumentException("Pending checkout field $name is required.")

private fun com.fasterxml.jackson.databind.JsonNode.requiredInt(name: String): Int =
    get(name)?.takeIf { it.isInt }?.intValue()
        ?: throw IllegalArgumentException("Pending checkout field $name must be an integer.")

private fun com.fasterxml.jackson.databind.JsonNode.optionalInt(name: String): Int? =
    get(name)?.takeIf { it.isInt }?.intValue()

private fun com.fasterxml.jackson.databind.JsonNode.optionalLong(name: String): Long? =
    get(name)?.takeIf { it.isNumber }?.longValue()

private fun com.fasterxml.jackson.databind.JsonNode.requiredDouble(name: String): Double =
    get(name)?.takeIf { it.isNumber }?.doubleValue()
        ?: throw IllegalArgumentException("Pending checkout field $name must be numeric.")

private fun com.fasterxml.jackson.databind.JsonNode.requiredObject(name: String): com.fasterxml.jackson.databind.JsonNode =
    get(name)?.takeIf { it.isObject }
        ?: throw IllegalArgumentException("Pending checkout field $name must be an object.")

private fun com.fasterxml.jackson.databind.JsonNode.requiredArray(name: String): List<com.fasterxml.jackson.databind.JsonNode> {
    val node = get(name)?.takeIf { it.isArray }
        ?: throw IllegalArgumentException("Pending checkout field $name must be an array.")
    return node.toList()
}

private fun OrderProcessingRequest.toAcceptedForFulfillment(
    pricing: OrderPricingSnapshot,
    providerReference: String,
): OrderProcessingResult.AcceptedForFulfillment {
    val provisionalOrder = ProcessedOrder(
        orderId = checkoutId.toOrderId(),
        checkoutId = checkoutId,
        serviceLevel = serviceLevel,
        pricing = pricing,
        payment = PaymentValidationResult.validated(providerReference),
        fulfillmentPlan = planFulfillment(),
        confirmation = OrderConfirmation(title = "", message = ""),
    )
    val order = provisionalOrder.copy(
        confirmation = OrderConfirmation(
            title = "Order paid",
            message = "Sequo confirmed ${pricing.totalCfa} CFA and sent the order for seller acceptance.",
        )
    )
    return OrderProcessingResult.AcceptedForFulfillment(
        order = order,
        events = listOf(
            OrderFlowEvent(OrderFlowStage.ValidateOrder, OrderFlowStepStatus.Passed, "Pending checkout was restored for provider reconciliation."),
            OrderFlowEvent(OrderFlowStage.CalculateTotal, OrderFlowStepStatus.Passed, "Stored checkout total was reused for reconciliation."),
            OrderFlowEvent(OrderFlowStage.ProcessPayment, OrderFlowStepStatus.Passed, "Payment was validated by provider webhook."),
            OrderFlowEvent(OrderFlowStage.SendConfirmation, OrderFlowStepStatus.Passed, "Customer confirmation was prepared."),
            OrderFlowEvent(OrderFlowStage.SendToMerchant, OrderFlowStepStatus.Passed, "Paid order is ready for seller acceptance."),
        ),
    )
}

private fun OrderProcessingRequest.planFulfillment(): FulfillmentPlan {
    val requiresConsolidation = route == OrderRoute.GroupedSequo || lines.map { it.sellerId }.distinct().size > 1
    val status = when (route) {
        OrderRoute.FastDelivery -> "Paid, waiting for seller preparation"
        OrderRoute.GroupedSequo -> "Paid, waiting for Sequo consolidation"
        OrderRoute.Pickup -> "Paid, waiting for pickup readiness"
        OrderRoute.PointDeRelai -> "Paid, waiting for Point de Relai routing"
    }
    val priority = if (serviceLevel.isPrime) FulfillmentPriority.Prime else FulfillmentPriority.Standard

    return FulfillmentPlan(
        route = route,
        serviceLevel = serviceLevel,
        priority = priority,
        requiresConsolidation = requiresConsolidation,
        customerFacingStatus = status,
    )
}

private fun String.toOrderId(): String =
    if (startsWith("SQ-")) this else "SQ-$this"
