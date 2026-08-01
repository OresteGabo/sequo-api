package dev.orestegabo.sequo_api.domain

import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DomainConfig {

    @Bean
    fun deliveryPricingService() = DeliveryPricingService()

    @Bean
    fun paymentProcessor(): PaymentProcessor {
        // Mock handlers - in production, these would call the actual carrier APIs
        val yasTogo = YasTogoPaymentMethod(
            validateHandler = { req -> 
                PaymentValidationResult.validated("YAS-${req.paymentReference}") 
            }
        )
        val moovAfrica = MoovAfricaPaymentMethod(
            validateHandler = { req -> 
                PaymentValidationResult.validated("MOOV-${req.paymentReference}") 
            }
        )
        
        return PaymentProcessor(listOf(yasTogo, moovAfrica))
    }
}
