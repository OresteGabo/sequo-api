package dev.orestegabo.sequo_api.domain

import dev.orestegabo.sequo_api.domain.delivery.DeliveryAssignmentPolicy
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMissionWorkflow
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentWorkflow
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingSettingsRepository
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import java.time.Duration
import dev.orestegabo.sequo_api.domain.relay.RelayParcelPolicyConfig
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
    fun relayParcelPolicy(
        @Value("\${sequo.relay.storage-fee-after:}") storageFeeAfter: String,
        @Value("\${sequo.relay.review-after:}") reviewAfter: String,
    ): RelayParcelPolicy {
        val defaults = RelayParcelPolicyConfig()
        return RelayParcelPolicy(
            RelayParcelPolicyConfig(
                storageFeeAfter = storageFeeAfter.takeIf { it.isNotBlank() }?.let(Duration::parse) ?: defaults.storageFeeAfter,
                returnToSellerReviewAfter = reviewAfter.takeIf { it.isNotBlank() }?.let(Duration::parse) ?: defaults.returnToSellerReviewAfter,
            )
        )
    }

    @Bean
    fun yasTogoPaymentProviderAdapter(
        @Value("\${sequo.wallets.yas-togo.api-base-url:}") yasTogoApiBaseUrl: String,
        @Value("\${sequo.wallets.yas-togo.api-key:}") yasTogoApiKey: String,
        @Value("\${sequo.wallets.yas-togo.merchant-account-id:}") yasTogoMerchantAccountId: String,
        @Value("\${sequo.wallets.yas-togo.webhook-secret:}") yasTogoWebhookSecret: String,
    ) = YasTogoPaymentProviderAdapter(PaymentProviderAdapterConfiguration(
        apiBaseUrl = yasTogoApiBaseUrl, apiKey = yasTogoApiKey,
        merchantAccountId = yasTogoMerchantAccountId, webhookSecret = yasTogoWebhookSecret,
    ))

    @Bean
    fun moovAfricaPaymentProviderAdapter(
        @Value("\${sequo.wallets.moov-africa.api-base-url:}") moovAfricaApiBaseUrl: String,
        @Value("\${sequo.wallets.moov-africa.api-key:}") moovAfricaApiKey: String,
        @Value("\${sequo.wallets.moov-africa.merchant-account-id:}") moovAfricaMerchantAccountId: String,
        @Value("\${sequo.wallets.moov-africa.webhook-secret:}") moovAfricaWebhookSecret: String,
    ) = MoovAfricaPaymentProviderAdapter(PaymentProviderAdapterConfiguration(
        apiBaseUrl = moovAfricaApiBaseUrl, apiKey = moovAfricaApiKey,
        merchantAccountId = moovAfricaMerchantAccountId, webhookSecret = moovAfricaWebhookSecret,
    ))

    @Bean
    fun paymentProcessor(adapters: List<PaymentProviderAdapter>) =
        PaymentProcessor(adapters.map { it.asPaymentMethod() })

    @Bean
    fun paymentWebhookVerifier(adapters: List<PaymentProviderAdapter>) = PaymentWebhookVerifier { request ->
        adapters.singleOrNull { it.provider == request.provider }?.verifyCallback(request)
            ?: ProviderCallbackVerificationResult.Rejected("Payment callback verification is unavailable for this provider.")
    }
}
