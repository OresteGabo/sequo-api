package dev.orestegabo.sequo_api.domain

import dev.orestegabo.sequo_api.domain.delivery.DeliveryAssignmentPolicy
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionWorkflow
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentWorkflow
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.relay.RelayParcelPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DomainConfig {

    @Bean
    fun deliveryPricingService() = DeliveryPricingService()

    @Bean
    fun deliveryAssignmentPolicy() = DeliveryAssignmentPolicy()

    @Bean
    fun merchantFulfillmentWorkflow() = MerchantFulfillmentWorkflow()

    @Bean
    fun deliveryMissionWorkflow() = DeliveryMissionWorkflow()

    @Bean
    fun relayParcelPolicy() = RelayParcelPolicy()

    @Bean
    fun paymentProcessor(): PaymentProcessor {
        val yasTogo = YasTogoPaymentMethod(
            validateHandler = { PaymentValidationResult.pending("Yas Togo payment validation adapter is not configured.") }
        )
        val moovAfrica = MoovAfricaPaymentMethod(
            validateHandler = { PaymentValidationResult.pending("Moov Africa payment validation adapter is not configured.") }
        )

        return PaymentProcessor(listOf(yasTogo, moovAfrica))
    }
}
