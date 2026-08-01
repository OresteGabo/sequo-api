package dev.orestegabo.sequo_api.domain.payment

data class PaymentProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "Payment provider id cannot be blank." }
    }
}

object SequoPaymentProviders {
    val YasTogo = PaymentProviderId("yas_togo")
    val MoovAfrica = PaymentProviderId("moov_africa")
}

enum class PaymentFeature {
    CustomerCheckout,
    PaymentCancellation,
    Refund,
    SubscriptionRenewal,
    ReferralDeliveryCredit,
    CashWithdrawal,
    CashReferralPayout,
}

enum class PaymentValidationStatus {
    Pending,
    Validated,
    Failed,
    Cancelled,
    BlockedByPolicy,
}

data class PaymentAttemptRequest(
    val checkoutId: String,
    val provider: PaymentProviderId,
    val amountCfa: Int,
    val countryCode: String,
    val customerId: String,
    val idempotencyKey: String,
    val feature: PaymentFeature = PaymentFeature.CustomerCheckout,
) {
    init {
        require(checkoutId.isNotBlank()) { "checkoutId cannot be blank." }
        require(amountCfa >= 0) { "amountCfa must be non-negative." }
        require(countryCode.isNotBlank()) { "countryCode cannot be blank." }
        require(customerId.isNotBlank()) { "customerId cannot be blank." }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey cannot be blank." }
    }
}

data class PaymentValidationRequest(
    val checkoutId: String,
    val provider: PaymentProviderId,
    val amountCfa: Int,
    val paymentReference: String,
    val countryCode: String,
    val feature: PaymentFeature = PaymentFeature.CustomerCheckout,
) {
    init {
        require(checkoutId.isNotBlank()) { "checkoutId cannot be blank." }
        require(amountCfa >= 0) { "amountCfa must be non-negative." }
        require(paymentReference.isNotBlank()) { "paymentReference cannot be blank." }
        require(countryCode.isNotBlank()) { "countryCode cannot be blank." }
    }
}

data class PaymentCancellationRequest(
    val checkoutId: String,
    val provider: PaymentProviderId,
    val providerReference: String,
    val countryCode: String,
) {
    init {
        require(checkoutId.isNotBlank()) { "checkoutId cannot be blank." }
        require(providerReference.isNotBlank()) { "providerReference cannot be blank." }
        require(countryCode.isNotBlank()) { "countryCode cannot be blank." }
    }
}

data class PaymentRefundRequest(
    val orderId: String,
    val provider: PaymentProviderId,
    val amountCfa: Int,
    val providerReference: String,
    val countryCode: String,
    val reason: String,
) {
    init {
        require(orderId.isNotBlank()) { "orderId cannot be blank." }
        require(amountCfa > 0) { "amountCfa must be positive for refunds." }
        require(providerReference.isNotBlank()) { "providerReference cannot be blank." }
        require(countryCode.isNotBlank()) { "countryCode cannot be blank." }
        require(reason.isNotBlank()) { "reason cannot be blank." }
    }
}

data class PaymentValidationResult(
    val status: PaymentValidationStatus,
    val providerReference: String? = null,
    val message: String? = null,
) {
    companion object {
        fun validated(providerReference: String): PaymentValidationResult =
            PaymentValidationResult(PaymentValidationStatus.Validated, providerReference)

        fun pending(message: String): PaymentValidationResult =
            PaymentValidationResult(PaymentValidationStatus.Pending, message = message)

        fun failed(message: String): PaymentValidationResult =
            PaymentValidationResult(PaymentValidationStatus.Failed, message = message)

        fun cancelled(message: String): PaymentValidationResult =
            PaymentValidationResult(PaymentValidationStatus.Cancelled, message = message)

        fun blocked(message: String): PaymentValidationResult =
            PaymentValidationResult(PaymentValidationStatus.BlockedByPolicy, message = message)
    }
}

sealed class PaymentAttemptResult {
    data class Created(
        val provider: PaymentProviderId,
        val providerReference: String,
        val amountCfa: Int,
        val customerMessage: String,
    ) : PaymentAttemptResult()

    data class Blocked(val message: String) : PaymentAttemptResult()

    data class Failed(val message: String) : PaymentAttemptResult()
}

sealed class PaymentActionResult {
    data class Completed(val providerReference: String, val message: String) : PaymentActionResult()

    data class Blocked(val message: String) : PaymentActionResult()

    data class Failed(val message: String) : PaymentActionResult()
}

sealed class PaymentRefundResult {
    data class Triggered(val providerReference: String, val message: String) : PaymentRefundResult()

    data class Pending(val providerReference: String, val message: String) : PaymentRefundResult()

    data class Blocked(val message: String) : PaymentRefundResult()

    data class Failed(val message: String) : PaymentRefundResult()
}

data class PaymentPolicyDecision(
    val allowed: Boolean,
    val message: String,
) {
    companion object {
        fun allowed(): PaymentPolicyDecision = PaymentPolicyDecision(true, "Allowed")

        fun blocked(message: String): PaymentPolicyDecision = PaymentPolicyDecision(false, message)
    }
}

data class PaymentProviderPolicy(
    val provider: PaymentProviderId,
    val enabled: Boolean = true,
    val disabledFeatures: Set<PaymentFeature> = emptySet(),
    val reason: String? = null,
)

data class CountryPaymentRules(
    val countryCode: String,
    val disabledFeatures: Set<PaymentFeature> = emptySet(),
    val providerPolicies: List<PaymentProviderPolicy> = emptyList(),
) {
    init {
        require(countryCode.isNotBlank()) { "countryCode cannot be blank." }
    }
}

abstract class PaymentPolicy {
    abstract fun decision(
        provider: PaymentProviderId,
        feature: PaymentFeature,
        countryCode: String,
    ): PaymentPolicyDecision
}

class ConfigurablePaymentPolicy(
    countryRules: List<CountryPaymentRules> = emptyList(),
    private val globallyDisabledFeatures: Set<PaymentFeature> = setOf(
        PaymentFeature.CashWithdrawal,
        PaymentFeature.CashReferralPayout,
    ),
) : PaymentPolicy() {
    private val rulesByCountry: Map<String, CountryPaymentRules> =
        countryRules.associateBy { it.countryCode.normalizedCountryCode() }

    override fun decision(
        provider: PaymentProviderId,
        feature: PaymentFeature,
        countryCode: String,
    ): PaymentPolicyDecision {
        if (feature in globallyDisabledFeatures) {
            return PaymentPolicyDecision.blocked("${feature.name} is disabled for Sequo payments.")
        }

        val rules = rulesByCountry[countryCode.normalizedCountryCode()] ?: return PaymentPolicyDecision.allowed()
        if (feature in rules.disabledFeatures) {
            return PaymentPolicyDecision.blocked("${feature.name} is disabled in ${rules.countryCode}.")
        }

        val providerPolicy = rules.providerPolicies.firstOrNull { it.provider == provider }
            ?: return PaymentPolicyDecision.allowed()
        if (!providerPolicy.enabled) {
            return PaymentPolicyDecision.blocked(
                providerPolicy.reason ?: "${provider.value} is disabled in ${rules.countryCode}.",
            )
        }
        if (feature in providerPolicy.disabledFeatures) {
            return PaymentPolicyDecision.blocked(
                providerPolicy.reason ?: "${feature.name} is disabled for ${provider.value} in ${rules.countryCode}.",
            )
        }

        return PaymentPolicyDecision.allowed()
    }
}

data class PaymentMethodDescriptor(
    val provider: PaymentProviderId,
    val displayName: String,
    val enabledFeatures: Set<PaymentFeature>,
)

abstract class SequoPaymentMethod {
    abstract val provider: PaymentProviderId
    abstract val displayName: String
    abstract val supportedFeatures: Set<PaymentFeature>

    fun supports(feature: PaymentFeature): Boolean = feature in supportedFeatures

    abstract fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult

    abstract fun validatePayment(request: PaymentValidationRequest): PaymentValidationResult

    abstract fun cancelPayment(request: PaymentCancellationRequest): PaymentActionResult

    abstract fun refundPayment(request: PaymentRefundRequest): PaymentRefundResult
}

class YasTogoPaymentMethod(
    private val validateHandler: (PaymentValidationRequest) -> PaymentValidationResult,
    private val createHandler: (PaymentAttemptRequest) -> PaymentAttemptResult = { request ->
        PaymentAttemptResult.Created(
            provider = request.provider,
            providerReference = request.idempotencyKey,
            amountCfa = request.amountCfa,
            customerMessage = "Open Yas Togo to approve the payment.",
        )
    },
    private val cancelHandler: (PaymentCancellationRequest) -> PaymentActionResult = { request ->
        PaymentActionResult.Completed(request.providerReference, "Yas Togo payment attempt cancelled.")
    },
    private val refundHandler: (PaymentRefundRequest) -> PaymentRefundResult = { request ->
        PaymentRefundResult.Pending(request.providerReference, "Yas Togo refund requested.")
    },
) : SequoPaymentMethod() {
    override val provider: PaymentProviderId = SequoPaymentProviders.YasTogo
    override val displayName: String = "Yas Togo"
    override val supportedFeatures: Set<PaymentFeature> = setOf(
        PaymentFeature.CustomerCheckout,
        PaymentFeature.PaymentCancellation,
        PaymentFeature.Refund,
    )

    override fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult =
        createHandler(request)

    override fun validatePayment(request: PaymentValidationRequest): PaymentValidationResult =
        validateHandler(request)

    override fun cancelPayment(request: PaymentCancellationRequest): PaymentActionResult =
        cancelHandler(request)

    override fun refundPayment(request: PaymentRefundRequest): PaymentRefundResult =
        refundHandler(request)
}

class MoovAfricaPaymentMethod(
    private val validateHandler: (PaymentValidationRequest) -> PaymentValidationResult,
    private val createHandler: (PaymentAttemptRequest) -> PaymentAttemptResult = { request ->
        PaymentAttemptResult.Created(
            provider = request.provider,
            providerReference = request.idempotencyKey,
            amountCfa = request.amountCfa,
            customerMessage = "Open Moov Africa to approve the payment.",
        )
    },
    private val cancelHandler: (PaymentCancellationRequest) -> PaymentActionResult = { request ->
        PaymentActionResult.Completed(request.providerReference, "Moov Africa payment attempt cancelled.")
    },
    private val refundHandler: (PaymentRefundRequest) -> PaymentRefundResult = { request ->
        PaymentRefundResult.Pending(request.providerReference, "Moov Africa refund requested.")
    },
) : SequoPaymentMethod() {
    override val provider: PaymentProviderId = SequoPaymentProviders.MoovAfrica
    override val displayName: String = "Moov Africa"
    override val supportedFeatures: Set<PaymentFeature> = setOf(
        PaymentFeature.CustomerCheckout,
        PaymentFeature.PaymentCancellation,
        PaymentFeature.Refund,
    )

    override fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult =
        createHandler(request)

    override fun validatePayment(request: PaymentValidationRequest): PaymentValidationResult =
        validateHandler(request)

    override fun cancelPayment(request: PaymentCancellationRequest): PaymentActionResult =
        cancelHandler(request)

    override fun refundPayment(request: PaymentRefundRequest): PaymentRefundResult =
        refundHandler(request)
}

class PaymentProcessor(
    paymentMethods: List<SequoPaymentMethod>,
    private val policy: PaymentPolicy = ConfigurablePaymentPolicy(),
) {
    private val methodsByProvider: Map<PaymentProviderId, SequoPaymentMethod> =
        paymentMethods.associateBy { it.provider }

    init {
        require(paymentMethods.isNotEmpty()) { "At least one payment method must be registered." }
        require(methodsByProvider.size == paymentMethods.size) { "Payment providers must be registered once." }
    }

    fun availableMethods(
        countryCode: String,
        feature: PaymentFeature = PaymentFeature.CustomerCheckout,
    ): List<PaymentMethodDescriptor> =
        methodsByProvider.values
            .filter { method -> method.supports(feature) && policy.decision(method.provider, feature, countryCode).allowed }
            .map { method ->
                PaymentMethodDescriptor(
                    provider = method.provider,
                    displayName = method.displayName,
                    enabledFeatures = method.supportedFeatures.filterTo(mutableSetOf()) { featureCandidate ->
                        policy.decision(method.provider, featureCandidate, countryCode).allowed
                    },
                )
            }

    fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult {
        val method = methodFor(request.provider)
            ?: return PaymentAttemptResult.Blocked("Payment provider ${request.provider.value} is not registered.")
        method.ensureFeature(request.feature, request.countryCode)?.let { return PaymentAttemptResult.Blocked(it) }
        return method.createPaymentAttempt(request)
    }

    fun validatePayment(request: PaymentValidationRequest): PaymentValidationResult {
        val method = methodFor(request.provider)
            ?: return PaymentValidationResult.blocked("Payment provider ${request.provider.value} is not registered.")
        method.ensureFeature(request.feature, request.countryCode)?.let { return PaymentValidationResult.blocked(it) }
        return method.validatePayment(request)
    }

    fun cancelPayment(request: PaymentCancellationRequest): PaymentActionResult {
        val method = methodFor(request.provider)
            ?: return PaymentActionResult.Blocked("Payment provider ${request.provider.value} is not registered.")
        method.ensureFeature(PaymentFeature.PaymentCancellation, request.countryCode)?.let {
            return PaymentActionResult.Blocked(it)
        }
        return method.cancelPayment(request)
    }

    fun refundPayment(request: PaymentRefundRequest): PaymentRefundResult {
        val method = methodFor(request.provider)
            ?: return PaymentRefundResult.Blocked("Payment provider ${request.provider.value} is not registered.")
        method.ensureFeature(PaymentFeature.Refund, request.countryCode)?.let {
            return PaymentRefundResult.Blocked(it)
        }
        return method.refundPayment(request)
    }

    private fun methodFor(provider: PaymentProviderId): SequoPaymentMethod? =
        methodsByProvider[provider]

    private fun SequoPaymentMethod.ensureFeature(feature: PaymentFeature, countryCode: String): String? {
        if (!supports(feature)) {
            return "$displayName does not support ${feature.name}."
        }
        val decision = policy.decision(provider, feature, countryCode)
        return if (decision.allowed) null else decision.message
    }
}

private fun String.normalizedCountryCode(): String =
    trim().uppercase()
