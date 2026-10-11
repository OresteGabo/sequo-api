package dev.orestegabo.sequo_api.domain.payment

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentProviderAdapterTest {

    @Test
    fun unconfiguredAdaptersFailClosedWithoutFakeSuccess() {
        val adapter = YasTogoPaymentProviderAdapter(PaymentProviderAdapterConfiguration())

        val attempt = adapter.createPaymentAttempt(
            PaymentAttemptRequest(
                checkoutId = "checkout-1",
                provider = SequoPaymentProviders.YasTogo,
                amountCfa = 4_500,
                countryCode = "TG",
                customerId = "customer-1",
                idempotencyKey = "attempt-1",
            )
        )
        val status = adapter.checkPaymentStatus(
            ProviderPaymentStatusRequest(
                checkoutId = "checkout-1",
                providerReference = "provider-ref-1",
                amountCfa = 4_500,
                countryCode = "TG",
            )
        )
        val refund = adapter.issueRefundInstruction(
            PaymentRefundRequest(
                orderId = "order-1",
                provider = SequoPaymentProviders.YasTogo,
                amountCfa = 4_500,
                providerReference = "provider-ref-1",
                countryCode = "TG",
                reason = "Return accepted after inspection.",
            )
        )
        val payout = adapter.issuePayoutInstruction(
            ProviderPayoutInstructionRequest(
                payoutId = "payout-1",
                courierId = "courier-1",
                amountCfa = 1_000,
                walletAccountRef = "wallet-ref-1",
                countryCode = "TG",
                idempotencyKey = "payout-key-1",
            )
        )

        assertTrue(attempt is PaymentAttemptResult.Failed)
        assertEquals(PaymentValidationStatus.Pending, status.status)
        assertTrue(refund is PaymentRefundResult.Failed)
        assertTrue(payout is ProviderPayoutInstructionResult.Failed)
        assertFalse(attempt.toString().contains("api-key", ignoreCase = true))
    }

    @Test
    fun devPlaceholderValuesAreTreatedAsUnconfigured() {
        val adapter = MoovAfricaPaymentProviderAdapter(
            PaymentProviderAdapterConfiguration(
                apiBaseUrl = "https://payments.example.invalid",
                apiKey = "moov_africa_dev_api_key_2026_change_before_prod",
                merchantAccountId = "merchant-1",
                webhookSecret = "moov_africa_dev_webhook_secret_2026_change_before_prod",
            )
        )

        val status = adapter.checkPaymentStatus(
            ProviderPaymentStatusRequest(
                checkoutId = "checkout-1",
                providerReference = "provider-ref-1",
                amountCfa = 4_500,
                countryCode = "TG",
            )
        )
        val callback = adapter.verifyCallback(
            ProviderCallbackVerificationRequest(
                provider = SequoPaymentProviders.MoovAfrica,
                timestamp = Instant.parse("2026-10-10T10:00:00Z"),
                signature = "sha256=placeholder",
                rawPayload = "{}",
            )
        )

        assertEquals(PaymentValidationStatus.Pending, status.status)
        assertTrue(callback is ProviderCallbackVerificationResult.Rejected)
    }

    @Test
    fun configuredAdaptersStillDoNotProduceFakeValidatedPayments() {
        val adapter = YasTogoPaymentProviderAdapter(
            PaymentProviderAdapterConfiguration(
                apiBaseUrl = "https://payments.example.invalid",
                apiKey = "live-test-key",
                merchantAccountId = "sequo-merchant",
                webhookSecret = "live-test-webhook-secret",
            )
        )

        val attempt = adapter.createPaymentAttempt(
            PaymentAttemptRequest(
                checkoutId = "checkout-1",
                provider = SequoPaymentProviders.YasTogo,
                amountCfa = 4_500,
                countryCode = "TG",
                customerId = "customer-1",
                idempotencyKey = "attempt-1",
            )
        )
        val status = adapter.checkPaymentStatus(
            ProviderPaymentStatusRequest(
                checkoutId = "checkout-1",
                providerReference = "provider-ref-1",
                amountCfa = 4_500,
                countryCode = "TG",
            )
        )

        assertTrue(attempt is PaymentAttemptResult.Failed)
        assertEquals(PaymentValidationStatus.Pending, status.status)
    }
}
