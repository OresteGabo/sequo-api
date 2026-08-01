package dev.orestegabo.sequo_api.domain.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentProcessingTest {

    @Test
    fun createsAttemptThroughSelectedPaymentMethod() {
        val processor = paymentProcessor()

        val result = processor.createPaymentAttempt(
            PaymentAttemptRequest(
                checkoutId = "checkout-2419",
                provider = SequoPaymentProviders.YasTogo,
                amountCfa = 5500,
                countryCode = "TG",
                customerId = "customer-1",
                idempotencyKey = "attempt-2419",
            ),
        )

        assertTrue(result is PaymentAttemptResult.Created)
        assertEquals(SequoPaymentProviders.YasTogo, result.provider)
        assertEquals(5500, result.amountCfa)
    }

    @Test
    fun blocksProviderDisabledByCountryLawBeforeMethodRuns() {
        val processor = paymentProcessor(
            policy = ConfigurablePaymentPolicy(
                countryRules = listOf(
                    CountryPaymentRules(
                        countryCode = "TG",
                        providerPolicies = listOf(
                            PaymentProviderPolicy(
                                provider = SequoPaymentProviders.MoovAfrica,
                                enabled = false,
                                reason = "Moov Africa temporarily disabled by regulator.",
                            ),
                        ),
                    ),
                ),
            ),
            moovValidation = { error("Provider validation must not run when policy blocks it.") },
        )

        val result = processor.validatePayment(
            PaymentValidationRequest(
                checkoutId = "checkout-2419",
                provider = SequoPaymentProviders.MoovAfrica,
                amountCfa = 5500,
                paymentReference = "moov-ref",
                countryCode = "TG",
            ),
        )

        assertEquals(PaymentValidationStatus.BlockedByPolicy, result.status)
        assertEquals("Moov Africa temporarily disabled by regulator.", result.message)
    }

    @Test
    fun canDisableOnlyRefundsWhileKeepingCheckoutAvailable() {
        val processor = paymentProcessor(
            policy = ConfigurablePaymentPolicy(
                countryRules = listOf(
                    CountryPaymentRules(
                        countryCode = "TG",
                        providerPolicies = listOf(
                            PaymentProviderPolicy(
                                provider = SequoPaymentProviders.YasTogo,
                                disabledFeatures = setOf(PaymentFeature.Refund),
                                reason = "Refunds are temporarily paused by policy.",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val checkout = processor.validatePayment(
            PaymentValidationRequest(
                checkoutId = "checkout-2419",
                provider = SequoPaymentProviders.YasTogo,
                amountCfa = 5500,
                paymentReference = "yas-ref",
                countryCode = "TG",
            ),
        )
        val refund = processor.refundPayment(
            PaymentRefundRequest(
                orderId = "SQ-2419",
                provider = SequoPaymentProviders.YasTogo,
                amountCfa = 5500,
                providerReference = "yas-ref",
                countryCode = "TG",
                reason = "Return accepted after inspection.",
            ),
        )

        assertEquals(PaymentValidationStatus.Validated, checkout.status)
        assertTrue(refund is PaymentRefundResult.Blocked)
        assertEquals("Refunds are temporarily paused by policy.", refund.message)
    }

    @Test
    fun cashWithdrawalAndCashReferralPayoutAreGloballyDisabled() {
        val processor = paymentProcessor()

        assertEquals(emptyList(), processor.availableMethods("TG", PaymentFeature.CashWithdrawal))
        assertEquals(emptyList(), processor.availableMethods("TG", PaymentFeature.CashReferralPayout))
    }

    @Test
    fun availableMethodsReflectProviderPolicy() {
        val processor = paymentProcessor(
            policy = ConfigurablePaymentPolicy(
                countryRules = listOf(
                    CountryPaymentRules(
                        countryCode = "TG",
                        providerPolicies = listOf(
                            PaymentProviderPolicy(provider = SequoPaymentProviders.MoovAfrica, enabled = false),
                        ),
                    ),
                ),
            ),
        )

        val methods = processor.availableMethods("TG", PaymentFeature.CustomerCheckout)

        assertEquals(listOf(SequoPaymentProviders.YasTogo), methods.map { it.provider })
        assertEquals("Yas Togo", methods.single().displayName)
    }

    private fun paymentProcessor(
        policy: PaymentPolicy = ConfigurablePaymentPolicy(),
        yasValidation: (PaymentValidationRequest) -> PaymentValidationResult = { request ->
            PaymentValidationResult.validated(request.paymentReference)
        },
        moovValidation: (PaymentValidationRequest) -> PaymentValidationResult = { request ->
            PaymentValidationResult.validated(request.paymentReference)
        },
    ): PaymentProcessor =
        PaymentProcessor(
            paymentMethods = listOf(
                YasTogoPaymentMethod(validateHandler = yasValidation),
                MoovAfricaPaymentMethod(validateHandler = moovValidation),
            ),
            policy = policy,
        )
}
