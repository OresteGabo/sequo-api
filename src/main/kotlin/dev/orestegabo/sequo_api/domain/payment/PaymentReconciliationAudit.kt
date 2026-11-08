package dev.orestegabo.sequo_api.domain.payment

import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRecord
import jakarta.persistence.*
import java.security.MessageDigest
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Normalized transaction identity in the current single SEQUO integration-account slot per provider.
 * This is not a claim that an operator's external field or payee mapping is already implemented. */
@Entity @Table(name = "payment_transaction_claims", uniqueConstraints = [UniqueConstraint(
    name = "uk_payment_transaction_claim_provider_reference", columnNames = ["provider", "provider_reference"])])
class PaymentTransactionClaimRecord(
    @Id @Column(nullable = false, length = 64) val id: String,
    @Column(nullable = false, length = 64) val provider: String,
    @Column(name = "provider_reference", nullable = false) val providerReference: String,
    @Column(name = "checkout_id", nullable = false) val checkoutId: String,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "checkout_id", insertable = false, updatable = false,
        foreignKey = ForeignKey(name = "fk_payment_transaction_claim_checkout")) val checkout: PendingPaymentCheckoutRecord? = null,
    @Column(name = "first_event_id", nullable = false) val firstEventId: String,
    @Column(name = "claimed_at", nullable = false) val claimedAt: Instant,
)

interface PaymentTransactionClaimRepository : JpaRepository<PaymentTransactionClaimRecord, String> {
    @Query("select (count(o) > 0) from CustomerOrderRecord o where o.paymentProvider = :provider and o.providerReference = :reference and o.checkoutId <> :checkoutId and o.paymentStatus = :status")
    fun paidReferenceExistsElsewhere(provider: String, reference: String, checkoutId: String,
        status: PaymentValidationStatus = PaymentValidationStatus.Validated): Boolean
}

internal fun paymentTransactionClaimId(provider: String, reference: String): String = MessageDigest.getInstance("SHA-256")
    .digest("${provider.length}:$provider$reference".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Service
class PaymentReconciliationFailureRecorder(private val events: PaymentWebhookEventRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(event: PaymentWebhookAcceptedEvent) {
        val receipt = events.lockForReconciliation("${event.provider.value}:${event.eventId}") ?: return
        // A slower failed attempt cannot erase a successful/conclusive concurrent result.
        if (receipt.reconciliationStatus !in setOf(PaymentWebhookReconciliationStatus.PENDING, PaymentWebhookReconciliationStatus.ERROR)) return
        receipt.reconciliationStatus = PaymentWebhookReconciliationStatus.ERROR
        receipt.reconciliationReason = "processing_failed"
        receipt.reconciledAt = Instant.now()
        events.save(receipt)
    }
}
