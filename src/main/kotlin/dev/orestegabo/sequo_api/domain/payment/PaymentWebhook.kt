package dev.orestegabo.sequo_api.domain.payment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class PaymentWebhookProviderStatus { Pending, Validated, Failed, Cancelled }

data class PaymentWebhookCommand(
    val provider: PaymentProviderId,
    val eventId: String,
    val checkoutId: String,
    val paymentReference: String,
    val amountCfa: Int,
    val status: PaymentWebhookProviderStatus,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val signatureTimestamp: Instant,
    val signature: String,
    val rawPayload: String,
) {
    init {
        require(eventId.isNotBlank()) { "eventId cannot be blank." }
        require(checkoutId.isNotBlank()) { "checkoutId cannot be blank." }
        require(paymentReference.isNotBlank()) { "paymentReference cannot be blank." }
        require(amountCfa >= 0) { "amountCfa must be non-negative." }
        require(rawPayload.isNotBlank()) { "rawPayload cannot be blank." }
    }
}

data class PaymentWebhookResult(
    val eventId: String,
    val provider: PaymentProviderId,
    val duplicate: Boolean,
    val accepted: Boolean,
)

@Entity
@Table(name = "payment_webhook_events")
class PaymentWebhookEventRecord(
    @Id val id: String,
    @Column(nullable = false, length = 64) val provider: String,
    @Column(name = "event_id", nullable = false, length = 255) val eventId: String,
    @Column(name = "checkout_id", nullable = false, length = 255) val checkoutId: String,
    @Column(name = "payment_reference", nullable = false, length = 255) val paymentReference: String,
    @Column(name = "amount_cfa", nullable = false) val amountCfa: Int,
    @Enumerated(EnumType.STRING) @Column(name = "payment_status", nullable = false, length = 32) val status: PaymentWebhookProviderStatus,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant,
    @Column(name = "payload_hash", nullable = false, length = 128) val payloadHash: String,
    @Column(name = "received_at", nullable = false) val receivedAt: Instant,
)

interface PaymentWebhookEventRepository : JpaRepository<PaymentWebhookEventRecord, String> {
    fun findByProviderAndEventId(provider: String, eventId: String): PaymentWebhookEventRecord?
}

@Service
class PaymentWebhookService(
    private val events: PaymentWebhookEventRepository,
    @Value("\${sequo.wallets.yas-togo.webhook-secret:}") private val yasTogoSecret: String,
    @Value("\${sequo.wallets.moov-africa.webhook-secret:}") private val moovAfricaSecret: String,
) {
    private val replayWindow = Duration.ofMinutes(5)

    @Transactional
    fun accept(command: PaymentWebhookCommand): PaymentWebhookResult {
        val secret = secretFor(command.provider)
            ?: throw IllegalArgumentException("Payment webhook provider is not configured.")
        val age = Duration.between(command.signatureTimestamp, command.receivedAt).abs()
        require(age <= replayWindow) { "Payment webhook signature timestamp is outside the replay window." }
        require(verifySignature(command, secret)) { "Invalid payment webhook signature." }

        val payloadHash = sha256(command.rawPayload)
        val existing = events.findByProviderAndEventId(command.provider.value, command.eventId)
        if (existing != null) {
            require(existing.payloadHash == payloadHash) { "Payment webhook event id was reused with a different payload." }
            return PaymentWebhookResult(command.eventId, command.provider, duplicate = true, accepted = true)
        }

        events.save(
            PaymentWebhookEventRecord(
                id = "${command.provider.value}:${command.eventId}",
                provider = command.provider.value,
                eventId = command.eventId,
                checkoutId = command.checkoutId,
                paymentReference = command.paymentReference,
                amountCfa = command.amountCfa,
                status = command.status,
                occurredAt = command.occurredAt,
                payloadHash = payloadHash,
                receivedAt = command.receivedAt,
            )
        )
        return PaymentWebhookResult(command.eventId, command.provider, duplicate = false, accepted = true)
    }

    private fun secretFor(provider: PaymentProviderId): String? = when (provider) {
        SequoPaymentProviders.YasTogo -> yasTogoSecret.takeUnless { it.isBlank() }
        SequoPaymentProviders.MoovAfrica -> moovAfricaSecret.takeUnless { it.isBlank() }
        else -> null
    }

    private fun verifySignature(command: PaymentWebhookCommand, secret: String): Boolean {
        val expected = hmacSha256(secret, "${command.signatureTimestamp.epochSecond}.${command.rawPayload}")
        val supplied = command.signature.removePrefix("sha256=").lowercase()
        return supplied.length == expected.length && MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.US_ASCII),
            expected.toByteArray(StandardCharsets.US_ASCII),
        )
    }
}

private fun hmacSha256(secret: String, value: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
