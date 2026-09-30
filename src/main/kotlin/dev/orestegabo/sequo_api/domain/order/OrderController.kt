package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.payment.PaymentProcessor
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val paymentProcessor: PaymentProcessor,
    private val pricingService: DeliveryPricingService,
    private val fulfillmentPersistence: OrderFulfillmentPersistenceService,
    private val pendingPaymentCheckouts: PendingPaymentCheckoutService,
    private val customerPickupConfirmationService: CustomerPickupConfirmationService,
) {
    private val factory = OrderProcessorFactory(
        RegularOrderProcessor(paymentProcessor, pricingService),
        PrimeOrderProcessor(paymentProcessor, pricingService)
    )

    @PostMapping("/process")
    fun processOrder(
        @AuthenticationPrincipal userId: String?,
        @RequestBody request: OrderProcessingRequest
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()

        val secureRequest = request.copy(customerId = userId)
        
        val processor = factory.processorFor(secureRequest.serviceLevel)
        val result = processor.process(secureRequest)
        
        return when (result) {
            is OrderProcessingResult.AcceptedForFulfillment -> ResponseEntity.ok(
                OrderFulfillmentResponse(
                    processing = result,
                    fulfillment = fulfillmentPersistence.persistAcceptedOrder(secureRequest, result),
                )
            )
            is OrderProcessingResult.AwaitingPaymentValidation -> {
                pendingPaymentCheckouts.recordAwaitingWebhook(secureRequest, result)
                ResponseEntity.accepted().body(result)
            }
            is OrderProcessingResult.Rejected -> ResponseEntity.badRequest().body(result)
        }
    }

    @GetMapping
    fun listOrders(
        @AuthenticationPrincipal userId: String?,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()
        return ResponseEntity.ok(fulfillmentPersistence.listForCustomer(userId))
    }

    @GetMapping("/{orderId}")
    fun getOrder(
        @AuthenticationPrincipal userId: String?,
        @PathVariable orderId: String,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()
        val safeOrderId = ApiInputPolicy.requiredIdentifier(orderId, "orderId")
        return fulfillmentPersistence.getForCustomer(safeOrderId, userId)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    @PostMapping("/{orderId}/pickup-confirmations")
    fun confirmCustomerPickup(
        @AuthenticationPrincipal userId: String?,
        @PathVariable orderId: String,
        @RequestBody request: ConfirmCustomerPickupRequest,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()

        val safeOrderId = ApiInputPolicy.requiredIdentifier(orderId, "orderId")
        return customerPickupConfirmationService.confirm(
            ConfirmCustomerPickupCommand(
                orderId = safeOrderId,
                customerId = userId,
                actorUserId = userId,
                idempotencyKey = ApiInputPolicy.requiredIdempotencyKey(request.idempotencyKey),
                proofMetadata = ApiInputPolicy.optionalLongText(request.proofMetadata, "proofMetadata"),
                )
        ).toResponse()
    }

    @GetMapping("/{orderId}/pickup-confirmations")
    fun pickupConfirmations(
        @AuthenticationPrincipal userId: String?,
        @PathVariable orderId: String,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()

        return try {
            val safeOrderId = ApiInputPolicy.requiredIdentifier(orderId, "orderId")
            ResponseEntity.ok(customerPickupConfirmationService.listForOrder(safeOrderId, userId))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                CustomerPickupErrorResponse(
                    code = "invalid_customer_pickup_request",
                    message = e.message ?: "Invalid customer pickup request.",
                )
            )
        }
    }
}

data class ConfirmCustomerPickupRequest(
    val idempotencyKey: String,
    val proofMetadata: String? = null,
)

data class CustomerPickupErrorResponse(val code: String, val message: String)

private fun CustomerPickupConfirmationResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is CustomerPickupConfirmationResult.Success -> ResponseEntity.ok(confirmation)
        is CustomerPickupConfirmationResult.Rejected -> ResponseEntity.badRequest().body(
            CustomerPickupErrorResponse(code, message)
        )
    }
