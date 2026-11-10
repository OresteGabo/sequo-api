package dev.orestegabo.sequo_api.domain.payment

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

enum class PaymentIntegrationStatus { NOT_CONNECTED, UNSUPPORTED, SANDBOX_CONNECTED, LIVE_CONNECTED }

@Service
class PaymentConfigurationStatus(
    @Value("\${sequo.wallets.yas-togo.api-base-url:}") yasUrl: String,
    @Value("\${sequo.wallets.yas-togo.api-key:}") yasKey: String,
    @Value("\${sequo.wallets.yas-togo.merchant-account-id:}") yasAccount: String,
    @Value("\${sequo.wallets.moov-africa.api-base-url:}") moovUrl: String,
    @Value("\${sequo.wallets.moov-africa.api-key:}") moovKey: String,
    @Value("\${sequo.wallets.moov-africa.merchant-account-id:}") moovAccount: String,
    private val adapters: List<PaymentProviderAdapter>,
    private val kkiapay: KkiapayConfiguration? = null,
) {
    private val providerConfigurations = mapOf(
        SequoPaymentProviders.YasTogo.value to PaymentProviderAdapterConfiguration(yasUrl, yasKey, yasAccount),
        SequoPaymentProviders.MoovAfrica.value to PaymentProviderAdapterConfiguration(moovUrl, moovKey, moovAccount),
    )

    fun apiConfigured(providerId: String): Boolean = if (providerId == "kkiapay") kkiapay?.configured() == true else providerConfigurations[providerId]?.hasPaymentApiConfiguration() == true

    fun integrationStatus(providerId: String): PaymentIntegrationStatus =
        if (providerId == "kkiapay" && kkiapay?.ready() == true) {
            if (kkiapay.sandbox) PaymentIntegrationStatus.SANDBOX_CONNECTED else PaymentIntegrationStatus.LIVE_CONNECTED
        } else if (adapters.any { it.provider.value == providerId }) PaymentIntegrationStatus.NOT_CONNECTED else PaymentIntegrationStatus.UNSUPPORTED

    fun sandbox(providerId: String) = providerId == "kkiapay" && kkiapay?.sandbox == true
    fun connected(providerId: String) = providerId == "kkiapay" && kkiapay?.ready() == true

    fun configurationIssues(providerId: String): List<PaymentConfigurationIssue> =
        if (providerId == "kkiapay") { if (kkiapay?.configured() == true) emptyList() else listOf(PaymentConfigurationIssue.API_KEY_MISSING) }
        else providerConfigurations[providerId]?.paymentApiConfigurationIssues()
            ?: listOf(PaymentConfigurationIssue.UNSUPPORTED_PROVIDER)
}
