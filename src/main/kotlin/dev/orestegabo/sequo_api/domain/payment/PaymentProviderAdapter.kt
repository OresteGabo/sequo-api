package dev.orestegabo.sequo_api.domain.payment

import java.time.Instant
import java.net.URI

enum class PaymentConfigurationIssue {
    API_BASE_URL_MISSING,
    API_BASE_URL_INVALID,
    API_BASE_URL_PLACEHOLDER,
    API_KEY_MISSING,
    API_KEY_PLACEHOLDER,
    MERCHANT_ACCOUNT_ID_MISSING,
    MERCHANT_ACCOUNT_ID_PLACEHOLDER,
    UNSUPPORTED_PROVIDER,
}

data class PaymentProviderAdapterConfiguration(
    val apiBaseUrl: String = "",
    val apiKey: String = "",
    val merchantAccountId: String = "",
    val webhookSecret: String = "",
) {
    fun hasPaymentApiConfiguration(): Boolean = paymentApiConfigurationIssues().isEmpty()

    fun paymentApiConfigurationIssues(): List<PaymentConfigurationIssue> = buildList {
        when {
            apiBaseUrl.isBlank() -> add(PaymentConfigurationIssue.API_BASE_URL_MISSING)
            !apiBaseUrl.isProductionValue() -> add(PaymentConfigurationIssue.API_BASE_URL_PLACEHOLDER)
            !apiBaseUrl.isPaymentApiUrl() -> add(PaymentConfigurationIssue.API_BASE_URL_INVALID)
            apiBaseUrl.isPlaceholderEndpoint() -> add(PaymentConfigurationIssue.API_BASE_URL_PLACEHOLDER)
        }
        when {
            apiKey.isBlank() -> add(PaymentConfigurationIssue.API_KEY_MISSING)
            !apiKey.isProductionValue() -> add(PaymentConfigurationIssue.API_KEY_PLACEHOLDER)
        }
        when {
            merchantAccountId.isBlank() -> add(PaymentConfigurationIssue.MERCHANT_ACCOUNT_ID_MISSING)
            !merchantAccountId.isProductionValue() -> add(PaymentConfigurationIssue.MERCHANT_ACCOUNT_ID_PLACEHOLDER)
        }
    }

    fun hasWebhookConfiguration(): Boolean =
        webhookSecret.isProductionValue()
}

data class ProviderCallbackVerificationRequest(
    val provider: PaymentProviderId,
    val timestamp: Instant,
    val signature: String,
    val rawPayload: String,
) {
    init {
        require(signature.isNotBlank()) { "signature cannot be blank." }
        require(rawPayload.isNotBlank()) { "rawPayload cannot be blank." }
    }
}

sealed class ProviderCallbackVerificationResult {
    data object Accepted : ProviderCallbackVerificationResult()

    data class Rejected(val message: String) : ProviderCallbackVerificationResult()
}

data class ProviderPaymentStatusRequest(
    val checkoutId: String,
    val providerReference: String,
    val amountCfa: Int,
    val countryCode: String,
) {
    init {
        require(checkoutId.isNotBlank()) { "checkoutId cannot be blank." }
        require(providerReference.isNotBlank()) { "providerReference cannot be blank." }
        require(amountCfa >= 0) { "amountCfa must be non-negative." }
        require(countryCode.isNotBlank()) { "countryCode cannot be blank." }
    }
}

data class ProviderPayoutInstructionRequest(
    val payoutId: String,
    val courierId: String,
    val amountCfa: Int,
    val walletAccountRef: String,
    val countryCode: String,
    val idempotencyKey: String,
) {
    init {
        require(payoutId.isNotBlank()) { "payoutId cannot be blank." }
        require(courierId.isNotBlank()) { "courierId cannot be blank." }
        require(amountCfa > 0) { "amountCfa must be positive for payouts." }
        require(walletAccountRef.isNotBlank()) { "walletAccountRef cannot be blank." }
        require(countryCode.isNotBlank()) { "countryCode cannot be blank." }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey cannot be blank." }
    }
}

sealed class ProviderPayoutInstructionResult {
    data class Submitted(val providerReference: String, val message: String) : ProviderPayoutInstructionResult()

    data class Pending(val message: String) : ProviderPayoutInstructionResult()

    data class Failed(val message: String) : ProviderPayoutInstructionResult()
}

interface PaymentProviderAdapter {
    val provider: PaymentProviderId
    val displayName: String
    val enabledFeatures: Set<PaymentFeature> get() = setOf(PaymentFeature.CustomerCheckout, PaymentFeature.PaymentCancellation, PaymentFeature.Refund)

    fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult

    fun verifyCallback(request: ProviderCallbackVerificationRequest): ProviderCallbackVerificationResult

    fun checkPaymentStatus(request: ProviderPaymentStatusRequest): PaymentValidationResult

    fun issueRefundInstruction(request: PaymentRefundRequest): PaymentRefundResult

    fun issuePayoutInstruction(request: ProviderPayoutInstructionRequest): ProviderPayoutInstructionResult
}

abstract class ConfiguredWalletPaymentProviderAdapter(
    final override val provider: PaymentProviderId,
    final override val displayName: String,
    private val configuration: PaymentProviderAdapterConfiguration,
) : PaymentProviderAdapter {

    override fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult {
        if (!configuration.hasPaymentApiConfiguration()) {
            return PaymentAttemptResult.Failed("$displayName payment provider is not configured.")
        }
        return PaymentAttemptResult.Failed("$displayName payment provider API is not connected yet.")
    }

    override fun verifyCallback(request: ProviderCallbackVerificationRequest): ProviderCallbackVerificationResult {
        if (!configuration.hasWebhookConfiguration()) {
            return ProviderCallbackVerificationResult.Rejected("$displayName callback verification is not configured.")
        }
        return ProviderCallbackVerificationResult.Rejected("$displayName callback payload mapping is not connected yet.")
    }

    override fun checkPaymentStatus(request: ProviderPaymentStatusRequest): PaymentValidationResult {
        if (!configuration.hasPaymentApiConfiguration()) {
            return PaymentValidationResult.pending("$displayName payment validation adapter is not configured.")
        }
        return PaymentValidationResult.pending("$displayName payment status check is not connected yet.")
    }

    override fun issueRefundInstruction(request: PaymentRefundRequest): PaymentRefundResult {
        if (!configuration.hasPaymentApiConfiguration()) {
            return PaymentRefundResult.Failed("$displayName refund provider is not configured.")
        }
        return PaymentRefundResult.Pending(request.providerReference, "$displayName refund API is not connected yet.")
    }

    override fun issuePayoutInstruction(request: ProviderPayoutInstructionRequest): ProviderPayoutInstructionResult {
        if (!configuration.hasPaymentApiConfiguration()) {
            return ProviderPayoutInstructionResult.Failed("$displayName payout provider is not configured.")
        }
        return ProviderPayoutInstructionResult.Pending("$displayName payout API is not connected yet.")
    }
}

class YasTogoPaymentProviderAdapter(
    configuration: PaymentProviderAdapterConfiguration,
) : ConfiguredWalletPaymentProviderAdapter(
    provider = SequoPaymentProviders.YasTogo,
    displayName = "Yas Togo",
    configuration = configuration,
)

class MoovAfricaPaymentProviderAdapter(
    configuration: PaymentProviderAdapterConfiguration,
) : ConfiguredWalletPaymentProviderAdapter(
    provider = SequoPaymentProviders.MoovAfrica,
    displayName = "Moov Africa",
    configuration = configuration,
)

fun PaymentProviderAdapter.asPaymentMethod(): SequoPaymentMethod {
    val adapter = this
    return object : SequoPaymentMethod() {
        override val provider: PaymentProviderId = adapter.provider
        override val displayName: String = adapter.displayName
        override val supportedFeatures: Set<PaymentFeature> = adapter.enabledFeatures

        override fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult =
            adapter.createPaymentAttempt(request)

        override fun validatePayment(request: PaymentValidationRequest): PaymentValidationResult =
            adapter.checkPaymentStatus(
                ProviderPaymentStatusRequest(
                    checkoutId = request.checkoutId,
                    providerReference = request.paymentReference,
                    amountCfa = request.amountCfa,
                    countryCode = request.countryCode,
                )
            )

        override fun cancelPayment(request: PaymentCancellationRequest): PaymentActionResult =
            PaymentActionResult.Failed("$displayName payment cancellation is not connected yet.")

        override fun refundPayment(request: PaymentRefundRequest): PaymentRefundResult =
            adapter.issueRefundInstruction(request)
    }
}

internal fun String.isProductionValue(): Boolean {
    val normalized = trim().lowercase()
    return normalized.isNotEmpty() &&
        !normalized.contains("change_before_prod") &&
        !normalized.contains("dev_") &&
        !normalized.contains("placeholder") &&
        !normalized.contains("replace_me") &&
        !normalized.contains("replace-me") &&
        !normalized.contains("replace_with") &&
        !normalized.contains("replace-with") &&
        !normalized.contains("change_me") &&
        !normalized.contains("change-me") &&
        !normalized.contains("changeme") &&
        !normalized.startsWith("your_") &&
        !normalized.startsWith("your-") &&
        !normalized.startsWith("\${") &&
        normalized !in setOf("example", "dummy", "test") &&
        !normalized.any { it.isISOControl() }
}

private fun String.isPaymentApiUrl(): Boolean = runCatching {
    val url = URI(trim())
    url.scheme.equals("https", ignoreCase = true) && !url.host.isNullOrBlank() &&
        url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null &&
        (url.port == -1 || url.port in 1..65535)
}.getOrDefault(false)

private fun String.isPlaceholderEndpoint(): Boolean {
    val host = URI(trim()).host.lowercase().trimEnd('.')
    return host == "localhost" || host.endsWith(".localhost") || host == "localhost.localdomain" ||
        host == "0.0.0.0" || host.startsWith("127.") || host in setOf("[::1]", "::1") ||
        listOf("invalid", "example", "test").any { host == it || host.endsWith(".$it") } ||
        listOf("example.com", "example.org", "example.net").any { host == it || host.endsWith(".$it") }
}
