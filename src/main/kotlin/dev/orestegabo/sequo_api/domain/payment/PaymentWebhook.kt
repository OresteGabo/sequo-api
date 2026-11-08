package dev.orestegabo.sequo_api.domain.payment

import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRecord
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class PaymentWebhookProviderStatus { PENDING, VALIDATED, FAILED, CANCELLED }
enum class PaymentWebhookReconciliationStatus { PENDING, FULFILLED, IGNORED, REVIEW_REQUIRED, ERROR, LEGACY_REVIEW_REQUIRED }

/** An internal normalized event; production acceptance requires the provider adapter's verification. */
data class PaymentWebhookCommand(
    val provider: PaymentProviderId,
    val eventId: String,
    val checkoutId: String,
    val paymentReference: String,
    val providerReference: String? = null,
    val amountCfa: Int,
    val status: PaymentWebhookProviderStatus,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val signatureTimestamp: Instant,
    val signature: String,
    val rawPayload: String,
    val currency: String = "XOF",
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

data class PaymentWebhookAcceptedEvent(
    val provider: PaymentProviderId,
    val eventId: String,
    val checkoutId: String,
    val paymentReference: String,
    val providerReference: String?,
    val amountCfa: Int,
    val status: PaymentWebhookProviderStatus,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val currency: String = "XOF",
)

@Entity
@Table(name = "payment_webhook_events")
class PaymentWebhookEventRecord(
    @Id val id: String,
    @Column(nullable = false, length = 64) val provider: String,
    @Column(name = "event_id", nullable = false, length = 255) val eventId: String,
    @Column(name = "checkout_id", nullable = false, length = 255) val checkoutId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checkout_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_payment_webhook_events_checkout"))
    val checkout: PendingPaymentCheckoutRecord? = null,
    @Column(name = "payment_reference", nullable = false, length = 255) val paymentReference: String,
    @Column(name = "provider_reference", length = 255) val providerReference: String?,
    @Column(name = "amount_cfa", nullable = false) val amountCfa: Int,
    @Enumerated(EnumType.STRING) @Column(name = "payment_status", nullable = false, length = 32) val status: PaymentWebhookProviderStatus,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant,
    @Column(name = "payload_hash", nullable = false, length = 128) val payloadHash: String,
    @Column(name = "received_at", nullable = false) val receivedAt: Instant,
    @Enumerated(EnumType.STRING) @Column(name = "reconciliation_status", nullable = false, length = 32)
    var reconciliationStatus: PaymentWebhookReconciliationStatus = PaymentWebhookReconciliationStatus.PENDING,
    @Column(name = "reconciliation_reason", length = 96) var reconciliationReason: String? = null,
    @Column(name = "reconciled_at") var reconciledAt: Instant? = null,
)

interface PaymentWebhookEventRepository : JpaRepository<PaymentWebhookEventRecord, String> {
    fun findByProviderAndEventId(provider: String, eventId: String): PaymentWebhookEventRecord?
    fun findTop100ByCheckoutIdOrderByReceivedAtDesc(checkoutId: String): List<PaymentWebhookEventRecord>
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from PaymentWebhookEventRecord e where e.id = :id")
    fun lockForReconciliation(id: String): PaymentWebhookEventRecord?
}

fun interface PaymentWebhookVerifier {
    fun verify(request: ProviderCallbackVerificationRequest): ProviderCallbackVerificationResult
}

@Service
class PaymentWebhookService(
    private val events: PaymentWebhookEventRepository,
    private val publisher: ApplicationEventPublisher,
    private val verifier: PaymentWebhookVerifier,
    private val entityManager: jakarta.persistence.EntityManager,
) {
    private val replayWindow = Duration.ofMinutes(5)

    @Transactional
    fun accept(command: PaymentWebhookCommand): PaymentWebhookResult {
        require(command.currency == "XOF") { "Payment currency must be XOF." }
        val age = Duration.between(command.signatureTimestamp, command.receivedAt).abs()
        require(age <= replayWindow) { "Payment webhook signature timestamp is outside the replay window." }
        when (val verification = verifier.verify(ProviderCallbackVerificationRequest(command.provider,
            command.signatureTimestamp, command.signature, command.rawPayload))) {
            ProviderCallbackVerificationResult.Accepted -> Unit
            is ProviderCallbackVerificationResult.Rejected -> throw IllegalArgumentException(verification.message)
        }

        val payloadHash = sha256(command.rawPayload)
        val existing = events.findByProviderAndEventId(command.provider.value, command.eventId)
        if (existing != null) {
            require(existing.payloadHash == payloadHash) { "Payment webhook event id was reused with a different payload." }
            publisher.publishEvent(existing.toAcceptedEvent(command.provider))
            return PaymentWebhookResult(command.eventId, command.provider, duplicate = true, accepted = true)
        }

        val saved = PaymentWebhookEventRecord(
                id = "${command.provider.value}:${command.eventId}",
                provider = command.provider.value,
                eventId = command.eventId,
                checkoutId = command.checkoutId,
                paymentReference = command.paymentReference,
                providerReference = command.providerReference,
                amountCfa = command.amountCfa,
                status = command.status,
                occurredAt = command.occurredAt,
                payloadHash = payloadHash,
                receivedAt = command.receivedAt,
            )
        try {
            // The provider/event identity is immutable evidence. JPA merge must never replace it
            // if another receipt was committed after the initial duplicate lookup.
            entityManager.persist(saved)
            entityManager.flush()
        } catch (failure: RuntimeException) {
            if (generateSequence<Throwable>(failure) { it.cause }.any {
                    it is jakarta.persistence.EntityExistsException || (it is java.sql.SQLException && it.sqlState == "23505")
                }) throw PaymentWebhookReceiptConflict()
            throw failure
        }
        publisher.publishEvent(saved.toAcceptedEvent(command.provider))
        return PaymentWebhookResult(command.eventId, command.provider, duplicate = false, accepted = true)
    }
}

internal fun PaymentWebhookEventRecord.toAcceptedEvent(providerId: PaymentProviderId = PaymentProviderId(provider)): PaymentWebhookAcceptedEvent =
    PaymentWebhookAcceptedEvent(
        provider = providerId,
        eventId = eventId,
        checkoutId = checkoutId,
        paymentReference = paymentReference,
        providerReference = providerReference,
        amountCfa = amountCfa,
        status = status,
        occurredAt = occurredAt,
        receivedAt = receivedAt,
    )

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
class PaymentWebhookReceiptConflict : RuntimeException("Payment event identity conflicted with another receipt. Retry the unchanged event.")
