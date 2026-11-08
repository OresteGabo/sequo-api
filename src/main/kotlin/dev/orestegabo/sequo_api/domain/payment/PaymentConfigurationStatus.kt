package dev.orestegabo.sequo_api.domain.payment

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

enum class PaymentIntegrationStatus { NOT_CONNECTED, UNSUPPORTED }

@Service
class PaymentConfigurationStatus(
    @Value("\${sequo.wallets.yas-togo.api-base-url:}") yasUrl: String,
    @Value("\${sequo.wallets.yas-togo.api-key:}") yasKey: String,
    @Value("\${sequo.wallets.yas-togo.merchant-account-id:}") yasAccount: String,
    @Value("\${sequo.wallets.moov-africa.api-base-url:}") moovUrl: String,
    @Value("\${sequo.wallets.moov-africa.api-key:}") moovKey: String,
    @Value("\${sequo.wallets.moov-africa.merchant-account-id:}") moovAccount: String,
    private val adapters: List<PaymentProviderAdapter>,
) {
    private val providerConfigurations = mapOf(
        SequoPaymentProviders.YasTogo.value to PaymentProviderAdapterConfiguration(yasUrl, yasKey, yasAccount),
        SequoPaymentProviders.MoovAfrica.value to PaymentProviderAdapterConfiguration(moovUrl, moovKey, moovAccount),
    )

    fun apiConfigured(providerId: String): Boolean = providerConfigurations[providerId]?.hasPaymentApiConfiguration() == true

    fun integrationStatus(providerId: String): PaymentIntegrationStatus =
        if (adapters.any { it.provider.value == providerId }) PaymentIntegrationStatus.NOT_CONNECTED else PaymentIntegrationStatus.UNSUPPORTED

    fun configurationIssues(providerId: String): List<PaymentConfigurationIssue> =
        providerConfigurations[providerId]?.paymentApiConfigurationIssues()
            ?: listOf(PaymentConfigurationIssue.UNSUPPORTED_PROVIDER)
}
