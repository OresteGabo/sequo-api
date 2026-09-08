package dev.orestegabo.sequo_api.domain.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class PaymentProcessorConfigurationTest @Autowired constructor(
    private val paymentProcessor: PaymentProcessor,
) {
    @Test
    fun defaultWalletAdaptersDoNotAutoValidatePayments() {
        val yas = paymentProcessor.validatePayment(
            PaymentValidationRequest(
                checkoutId = "checkout-config-yas",
                provider = SequoPaymentProviders.YasTogo,
                amountCfa = 5_000,
                paymentReference = "yas-reference",
                countryCode = "TG",
            )
        )
        val moov = paymentProcessor.validatePayment(
            PaymentValidationRequest(
                checkoutId = "checkout-config-moov",
                provider = SequoPaymentProviders.MoovAfrica,
                amountCfa = 5_000,
                paymentReference = "moov-reference",
                countryCode = "TG",
            )
        )

        assertEquals(PaymentValidationStatus.Pending, yas.status)
        assertEquals(PaymentValidationStatus.Pending, moov.status)
    }
}
