package dev.orestegabo.sequo_api.domain.payment

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Internal normalized fixtures only. This configuration is absent from the production artifact. */
@TestConfiguration(proxyBeanMethods = false)
class PaymentWebhookFixtureConfiguration {
    @Bean
    @Primary
    fun normalizedFixtureWebhookVerifier(
        @Value("\${sequo.wallets.yas-togo.webhook-secret:}") yasSecret: String,
        @Value("\${sequo.wallets.moov-africa.webhook-secret:}") moovSecret: String,
    ): PaymentWebhookVerifier = NormalizedPaymentWebhookFixtureVerifier(mapOf(
        SequoPaymentProviders.YasTogo to yasSecret, SequoPaymentProviders.MoovAfrica to moovSecret,
    ))
}

internal class NormalizedPaymentWebhookFixtureVerifier(private val secrets: Map<PaymentProviderId, String>) : PaymentWebhookVerifier {
    override fun verify(request: ProviderCallbackVerificationRequest): ProviderCallbackVerificationResult {
        val secret = secrets[request.provider]?.takeIf { it.isProductionValue() }
            ?: return ProviderCallbackVerificationResult.Rejected("Internal payment fixture has no signing key.")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal("${request.timestamp.epochSecond}.${request.rawPayload}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val supplied = request.signature.removePrefix("sha256=").lowercase()
        return if (supplied.length == expected.length && MessageDigest.isEqual(
            supplied.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) ProviderCallbackVerificationResult.Accepted
        else ProviderCallbackVerificationResult.Rejected("Invalid internal payment fixture signature.")
    }
}
