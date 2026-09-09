package dev.orestegabo.sequo_api.domain.order

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
            is OrderProcessingResult.AwaitingPaymentValidation -> ResponseEntity.accepted().body(result)
            is OrderProcessingResult.Rejected -> ResponseEntity.badRequest().body(result)
        }
    }

    @PostMapping("/{orderId}/pickup-confirmations")
    fun confirmCustomerPickup(
        @AuthenticationPrincipal userId: String?,
        @PathVariable orderId: String,
        @RequestBody request: ConfirmCustomerPickupRequest,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()

        return try {
            customerPickupConfirmationService.confirm(
                ConfirmCustomerPickupCommand(
                    orderId = orderId,
                    customerId = userId,
                    actorUserId = userId,
                    idempotencyKey = request.idempotencyKey,
                    proofMetadata = request.proofMetadata,
                )
            ).toResponse()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                CustomerPickupErrorResponse(
                    code = "invalid_customer_pickup_request",
                    message = e.message ?: "Invalid customer pickup request.",
                )
            )
        }
    }

    @GetMapping("/{orderId}/pickup-confirmations")
    fun pickupConfirmations(
        @AuthenticationPrincipal userId: String?,
        @PathVariable orderId: String,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()

        return try {
            ResponseEntity.ok(customerPickupConfirmationService.listForOrder(orderId, userId))
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
