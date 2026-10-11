package dev.orestegabo.sequo_api.domain

import dev.orestegabo.sequo_api.domain.delivery.DeliveryAssignmentPolicy
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionWorkflow
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentWorkflow
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingSettingsRepository
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.relay.RelayParcelPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DomainConfig {

    @Bean
    fun deliveryPricingService(settingsRepository: DeliveryPricingSettingsRepository) =
        DeliveryPricingService(settingsRepository)

    @Bean
    fun deliveryAssignmentPolicy() = DeliveryAssignmentPolicy()

    @Bean
    fun merchantFulfillmentWorkflow() = MerchantFulfillmentWorkflow()

    @Bean
    fun deliveryMissionWorkflow() = DeliveryMissionWorkflow()

    @Bean
    fun relayParcelPolicy() = RelayParcelPolicy()

    @Bean
    fun paymentProcessor(
        @Value("\${sequo.wallets.yas-togo.api-base-url:}") yasTogoApiBaseUrl: String,
        @Value("\${sequo.wallets.yas-togo.api-key:}") yasTogoApiKey: String,
        @Value("\${sequo.wallets.yas-togo.merchant-account-id:}") yasTogoMerchantAccountId: String,
        @Value("\${sequo.wallets.yas-togo.webhook-secret:}") yasTogoWebhookSecret: String,
        @Value("\${sequo.wallets.moov-africa.api-base-url:}") moovAfricaApiBaseUrl: String,
        @Value("\${sequo.wallets.moov-africa.api-key:}") moovAfricaApiKey: String,
        @Value("\${sequo.wallets.moov-africa.merchant-account-id:}") moovAfricaMerchantAccountId: String,
        @Value("\${sequo.wallets.moov-africa.webhook-secret:}") moovAfricaWebhookSecret: String,
    ): PaymentProcessor =
        PaymentProcessor(
            listOf(
                YasTogoPaymentProviderAdapter(
                    PaymentProviderAdapterConfiguration(
                        apiBaseUrl = yasTogoApiBaseUrl,
                        apiKey = yasTogoApiKey,
                        merchantAccountId = yasTogoMerchantAccountId,
                        webhookSecret = yasTogoWebhookSecret,
                    )
                ).asPaymentMethod(),
                MoovAfricaPaymentProviderAdapter(
                    PaymentProviderAdapterConfiguration(
                        apiBaseUrl = moovAfricaApiBaseUrl,
                        apiKey = moovAfricaApiKey,
                        merchantAccountId = moovAfricaMerchantAccountId,
                        webhookSecret = moovAfricaWebhookSecret,
                    )
                ).asPaymentMethod(),
            )
        )
}
