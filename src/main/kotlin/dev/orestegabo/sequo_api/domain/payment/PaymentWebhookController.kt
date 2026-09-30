package dev.orestegabo.sequo_api.domain.payment

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/payments/webhooks")
class PaymentWebhookController(
    private val service: PaymentWebhookService,
) {
    private val objectMapper = ObjectMapper()
    private val maxWebhookPayloadBytes = 64 * 1024

    @PostMapping("/{provider}")
    fun receive(
        @PathVariable provider: String,
        @RequestHeader("X-Sequo-Webhook-Timestamp") timestamp: Long,
        @RequestHeader("X-Sequo-Webhook-Signature") signature: String,
        @RequestBody rawPayload: String,
    ): ResponseEntity<Any> = try {
        require(rawPayload.toByteArray(Charsets.UTF_8).size <= maxWebhookPayloadBytes) {
            "Payment webhook payload cannot exceed $maxWebhookPayloadBytes bytes."
        }
        val root = objectMapper.readTree(rawPayload)
        val result = service.accept(
            PaymentWebhookCommand(
                provider = ApiInputPolicy.requiredIdentifier(provider, "provider").toPaymentProviderId(),
                eventId = ApiInputPolicy.requiredIdentifier(root.requiredText("eventId"), "eventId"),
                checkoutId = ApiInputPolicy.requiredIdentifier(root.requiredText("checkoutId"), "checkoutId"),
                paymentReference = ApiInputPolicy.requiredIdentifier(root.requiredText("paymentReference"), "paymentReference"),
                providerReference = ApiInputPolicy.optionalIdentifier(root.optionalText("providerReference"), "providerReference"),
                amountCfa = root.requiredInt("amountCfa"),
                status = root.requiredText("status").uppercase().toPaymentWebhookStatus(),
                occurredAt = Instant.parse(root.requiredText("occurredAt")),
                receivedAt = Instant.now(),
                signatureTimestamp = Instant.ofEpochSecond(timestamp),
                signature = ApiInputPolicy.requiredShortToken(signature, "signature"),
                rawPayload = rawPayload,
            )
        )
        ResponseEntity.accepted().body(result)
    } catch (e: IllegalArgumentException) {
        ResponseEntity.badRequest().body(mapOf("code" to "invalid_payment_webhook", "message" to (e.message ?: "Invalid payment webhook.")))
    }
}

private fun String.toPaymentProviderId(): PaymentProviderId = when (trim().lowercase()) {
    SequoPaymentProviders.YasTogo.value -> SequoPaymentProviders.YasTogo
    SequoPaymentProviders.MoovAfrica.value -> SequoPaymentProviders.MoovAfrica
    else -> throw IllegalArgumentException("Unsupported payment webhook provider.")
}

private fun String.toPaymentWebhookStatus(): PaymentWebhookProviderStatus = when (this) {
    "PENDING" -> PaymentWebhookProviderStatus.PENDING
    "VALIDATED", "SUCCESS", "SUCCEEDED" -> PaymentWebhookProviderStatus.VALIDATED
    "FAILED" -> PaymentWebhookProviderStatus.FAILED
    "CANCELLED", "CANCELED" -> PaymentWebhookProviderStatus.CANCELLED
    else -> throw IllegalArgumentException("Unsupported payment webhook status.")
}

private fun com.fasterxml.jackson.databind.JsonNode.requiredText(name: String): String =
    get(name)?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
        ?: throw IllegalArgumentException("Payment webhook field $name is required.")

private fun com.fasterxml.jackson.databind.JsonNode.optionalText(name: String): String? =
    get(name)?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()

private fun com.fasterxml.jackson.databind.JsonNode.requiredInt(name: String): Int =
    get(name)?.takeIf { it.isInt && it.intValue() >= 0 }?.intValue()
        ?: throw IllegalArgumentException("Payment webhook field $name must be a non-negative integer.")
