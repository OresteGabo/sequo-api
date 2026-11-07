package dev.orestegabo.sequo_api.domain.payment

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class PaymentConfigurationStatus(
    @Value("\${sequo.wallets.yas-togo.api-base-url:}") yasUrl: String,
    @Value("\${sequo.wallets.yas-togo.api-key:}") yasKey: String,
    @Value("\${sequo.wallets.yas-togo.merchant-account-id:}") yasAccount: String,
    @Value("\${sequo.wallets.moov-africa.api-base-url:}") moovUrl: String,
    @Value("\${sequo.wallets.moov-africa.api-key:}") moovKey: String,
    @Value("\${sequo.wallets.moov-africa.merchant-account-id:}") moovAccount: String,
) {
    private val configuredProviders = mapOf(
        SequoPaymentProviders.YasTogo.value to PaymentProviderAdapterConfiguration(yasUrl, yasKey, yasAccount).hasPaymentApiConfiguration(),
        SequoPaymentProviders.MoovAfrica.value to PaymentProviderAdapterConfiguration(moovUrl, moovKey, moovAccount).hasPaymentApiConfiguration(),
    )

    fun apiConfigured(providerId: String): Boolean = configuredProviders[providerId] == true
}
