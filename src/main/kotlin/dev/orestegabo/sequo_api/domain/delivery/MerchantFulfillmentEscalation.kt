package dev.orestegabo.sequo_api.domain.delivery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository

enum class MerchantFulfillmentEscalationReason {
    SELLER_RESPONSE_SLA_EXCEEDED,
    PACKING_SLA_EXCEEDED,
    MANUAL_SUPPORT_REVIEW,
}

data class MerchantFulfillmentEscalationSnapshot(
    val id: String,
    val subOrderId: String,
    val actorUserId: String,
    val reason: MerchantFulfillmentEscalationReason,
    val note: String,
    val createdAt: Instant,
)

@Entity
@Table(name = "merchant_fulfillment_escalations")
class MerchantFulfillmentEscalationRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "sub_order_id", nullable = false)
    val subOrderId: String,

    @Column(name = "actor_user_id", nullable = false)
    val actorUserId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 64)
    val reason: MerchantFulfillmentEscalationReason,

    @Column(name = "note", nullable = false, length = 1000)
    val note: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)

interface MerchantFulfillmentEscalationRepository : JpaRepository<MerchantFulfillmentEscalationRecord, String> {
    fun findBySubOrderIdAndReason(
        subOrderId: String,
        reason: MerchantFulfillmentEscalationReason,
    ): MerchantFulfillmentEscalationRecord?

    fun findBySubOrderIdOrderByCreatedAtAsc(subOrderId: String): List<MerchantFulfillmentEscalationRecord>
}

fun MerchantFulfillmentEscalationRecord.toSnapshot(): MerchantFulfillmentEscalationSnapshot =
    MerchantFulfillmentEscalationSnapshot(
        id = requireNotNull(id) { "Persisted merchant fulfillment escalation id is required." },
        subOrderId = subOrderId,
        actorUserId = actorUserId,
        reason = reason,
        note = note,
        createdAt = createdAt,
    )
