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
    private val pricingService: DeliveryPricingService
) {
    private val factory = OrderProcessorFactory(
        RegularOrderProcessor(paymentProcessor, pricingService),
        PrimeOrderProcessor(paymentProcessor, pricingService)
    )

    @PostMapping("/process")
    fun processOrder(
        @AuthenticationPrincipal userId: String?,
        @RequestBody request: OrderProcessingRequest
    ): ResponseEntity<OrderProcessingResult> {
        if (userId == null) return ResponseEntity.status(401).build()

        val secureRequest = request.copy(customerId = userId)
        
        val processor = factory.processorFor(secureRequest.serviceLevel)
        val result = processor.process(secureRequest)
        
        return when (result) {
            is OrderProcessingResult.AcceptedForFulfillment -> ResponseEntity.ok(result)
            is OrderProcessingResult.AwaitingPaymentValidation -> ResponseEntity.accepted().body(result)
            is OrderProcessingResult.Rejected -> ResponseEntity.badRequest().body(result)
        }
    }
}
