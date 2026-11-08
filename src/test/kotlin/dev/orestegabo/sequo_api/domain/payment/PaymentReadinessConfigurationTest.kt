package dev.orestegabo.sequo_api.domain.payment

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentReadinessConfigurationTest {
    private fun configuration(url: String) = PaymentProviderAdapterConfiguration(
        apiBaseUrl = url,
        apiKey = "synthetic-credential-123456",
        merchantAccountId = "synthetic-merchant-account",
    )

    @Test
    fun missingConfigurationReportsOnlyFieldCodes() {
        assertEquals(
            listOf(PaymentConfigurationIssue.API_BASE_URL_MISSING, PaymentConfigurationIssue.API_KEY_MISSING,
                PaymentConfigurationIssue.MERCHANT_ACCOUNT_ID_MISSING),
            PaymentProviderAdapterConfiguration().paymentApiConfigurationIssues(),
        )
    }

    @Test
    fun malformedOrInsecureUrlsCannotCountAsConfigured() {
        for (url in listOf("not-a-url", "http://wallet-fixture.sequo.tg", "https:///missing-host",
            "https://wallet-fixture.sequo.tg:70000", "https://user:secret@wallet-fixture.sequo.tg",
            "https://wallet-fixture.sequo.tg?key=secret", "https://wallet-fixture.sequo.tg#fragment")) {
            val config = configuration(url)
            assertFalse(config.hasPaymentApiConfiguration())
            assertEquals(listOf(PaymentConfigurationIssue.API_BASE_URL_INVALID), config.paymentApiConfigurationIssues())
        }
    }

    @Test
    fun reservedAndKnownPlaceholderConfigurationDoesNotCountAsConfigured() {
        for (url in listOf("https://fixture.invalid", "https://wallet.example", "https://wallet.test",
            "https://example.com", "https://payments.example.org", "https://api.example.net",
            "https://localhost", "https://sub.localhost", "https://127.0.0.1", "https://[::1]")) {
            assertEquals(listOf(PaymentConfigurationIssue.API_BASE_URL_PLACEHOLDER), configuration(url).paymentApiConfigurationIssues())
        }
        for (placeholder in listOf("placeholder", "replace_me", "change-me", "your_api_key", "\${API_KEY}", "yas_dev_api_key",
            "replace_with_yas_togo_api_key", "replace-with-moov-africa-api-key")) {
            val issues = configuration("https://wallet-fixture.sequo.tg").copy(apiKey = placeholder).paymentApiConfigurationIssues()
            assertEquals(listOf(PaymentConfigurationIssue.API_KEY_PLACEHOLDER), issues)
        }
        for (placeholder in listOf("replace_with_yas_togo_merchant_account_id", "replace-with-moov-africa-merchant-account-id")) {
            val issues = configuration("https://wallet-fixture.sequo.tg").copy(merchantAccountId = placeholder).paymentApiConfigurationIssues()
            assertEquals(listOf(PaymentConfigurationIssue.MERCHANT_ACCOUNT_ID_PLACEHOLDER), issues)
        }
        for (placeholder in listOf("replace_with_yas_togo_webhook_secret", "replace-with-moov-africa-webhook-secret")) {
            assertFalse(configuration("https://wallet-fixture.sequo.tg").copy(webhookSecret = placeholder).hasWebhookConfiguration())
        }
    }

    @Test
    fun syntacticConfigurationRemainsDistinctFromAnImplementedTransport() {
        val configured = configuration("https://wallet-fixture.sequo.tg/v1/")
        assertTrue(configured.hasPaymentApiConfiguration())
        val status = PaymentConfigurationStatus(configured.apiBaseUrl, configured.apiKey, configured.merchantAccountId,
            "", "", "", listOf(YasTogoPaymentProviderAdapter(configured)))
        assertTrue(status.apiConfigured("yas_togo"))
        assertEquals(PaymentIntegrationStatus.NOT_CONNECTED, status.integrationStatus("yas_togo"))
        assertEquals(PaymentIntegrationStatus.UNSUPPORTED, status.integrationStatus("unknown"))
        assertFalse(status.apiConfigured("unknown"))
        assertEquals(listOf(PaymentConfigurationIssue.UNSUPPORTED_PROVIDER), status.configurationIssues("unknown"))
    }

    @Test
    fun bothProviderStubsFailClosedForEveryOperationWithAbsentInvalidOrPresentConfiguration() {
        val factories: List<(PaymentProviderAdapterConfiguration) -> PaymentProviderAdapter> =
            listOf(::YasTogoPaymentProviderAdapter, ::MoovAfricaPaymentProviderAdapter)
        val configurations = listOf(
            PaymentProviderAdapterConfiguration(),
            PaymentProviderAdapterConfiguration("malformed-url", "placeholder", "your_account", "placeholder"),
            configuration("https://wallet-fixture.sequo.tg").copy(webhookSecret = "synthetic-callback-credential"),
        )
        for (factory in factories) for (config in configurations) {
            val adapter = factory(config)
            val attempt = adapter.createPaymentAttempt(PaymentAttemptRequest("checkout-fixture", adapter.provider, 4500,
                "TG", "customer-fixture", "intent-fixture"))
            val status = adapter.checkPaymentStatus(ProviderPaymentStatusRequest("checkout-fixture", "provider-fixture", 4500, "TG"))
            val callback = adapter.verifyCallback(ProviderCallbackVerificationRequest(adapter.provider,
                Instant.parse("2026-11-08T10:00:00Z"), "synthetic-signature", "{\"fixture\":true}"))
            val refund = adapter.issueRefundInstruction(PaymentRefundRequest("order-fixture", adapter.provider, 4500,
                "provider-fixture", "TG", "Approved fixture return"))
            val payout = adapter.issuePayoutInstruction(ProviderPayoutInstructionRequest("payout-fixture", "courier-fixture",
                400, "synthetic-wallet-ref", "TG", "payout-intent-fixture"))
            assertTrue(attempt is PaymentAttemptResult.Failed)
            assertEquals(PaymentValidationStatus.Pending, status.status)
            assertTrue(callback is ProviderCallbackVerificationResult.Rejected)
            if (config.hasPaymentApiConfiguration()) {
                assertTrue(refund is PaymentRefundResult.Pending)
                assertTrue(payout is ProviderPayoutInstructionResult.Pending)
            } else {
                assertTrue(refund is PaymentRefundResult.Failed)
                assertTrue(payout is ProviderPayoutInstructionResult.Failed)
            }
            val results = listOf(attempt, status, callback, refund, payout).joinToString()
            for (value in listOf(config.apiBaseUrl, config.apiKey, config.merchantAccountId, config.webhookSecret).filter { it.isNotBlank() }) {
                assertFalse(results.contains(value), "Configuration values must not appear in operation results")
            }
        }
    }
}
