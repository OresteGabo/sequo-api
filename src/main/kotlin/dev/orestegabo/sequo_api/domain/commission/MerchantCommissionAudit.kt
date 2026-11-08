package dev.orestegabo.sequo_api.domain.commission

import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import jakarta.persistence.*
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

enum class MerchantCommissionAuditAction { SET, RESET }

data class MerchantCommissionAuditSnapshot(
    val id: String, val action: MerchantCommissionAuditAction, val actorUserId: String?,
    val beforeRateBps: Int, val afterRateBps: Int, val beforeOverridePresent: Boolean, val afterOverridePresent: Boolean,
    val reason: String?, val createdAt: Instant, val idempotencyKey: String?, val revision: String,
)

data class MerchantCommissionPolicyState(
    val merchantId: String, val commissionRateBps: Int, val override: MerchantCommissionOverrideSnapshot?,
    val defaultRateBps: Int, val minRateBps: Int, val maxRateBps: Int, val revision: String,
    val operationId: String?, val history: List<MerchantCommissionAuditSnapshot>,
)

@Entity @Table(name = "merchant_commission_audit", uniqueConstraints = [
    UniqueConstraint(name = "uk_commission_audit_generation", columnNames = ["merchant_id", "generation"]),
    UniqueConstraint(name = "uk_commission_audit_retry", columnNames = ["merchant_id", "actor_user_id", "idempotency_key"]),
])
class MerchantCommissionAuditRecord(
    @Id val id: String,
    @Column(name = "merchant_id", nullable = false) val merchantId: String,
    @Column(nullable = false) val generation: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val action: MerchantCommissionAuditAction,
    @Column(name = "actor_user_id") val actorUserId: String?,
    @Column(name = "before_rate_bps", nullable = false) val beforeRateBps: Int,
    @Column(name = "after_rate_bps", nullable = false) val afterRateBps: Int,
    @Column(name = "before_override_present", nullable = false) val beforeOverridePresent: Boolean,
    @Column(name = "after_override_present", nullable = false) val afterOverridePresent: Boolean,
    @Column(length = 500) val reason: String?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "idempotency_key", length = 128) val idempotencyKey: String?,
    @Column(name = "intent_hash", nullable = false, length = 64) val intentHash: String,
    @Column(nullable = false, length = 64) val revision: String,
    @Column(name = "override_created_at") val overrideCreatedAt: Instant?,
    @Column(name = "override_updated_at") val overrideUpdatedAt: Instant?,
) {
    fun toSnapshot() = MerchantCommissionAuditSnapshot(id, action, actorUserId, beforeRateBps, afterRateBps,
        beforeOverridePresent, afterOverridePresent, reason, createdAt, idempotencyKey, revision)

    fun overrideSnapshot(): MerchantCommissionOverrideSnapshot = MerchantCommissionOverrideSnapshot(
        merchantId, afterRateBps, reason, requireNotNull(actorUserId), requireNotNull(overrideCreatedAt),
        requireNotNull(overrideUpdatedAt), revision, id,
    )
}

interface MerchantCommissionAuditRepository : JpaRepository<MerchantCommissionAuditRecord, String> {
    fun findTop50ByMerchantIdOrderByGenerationDesc(merchantId: String): List<MerchantCommissionAuditRecord>
    fun findFirstByMerchantIdOrderByGenerationDesc(merchantId: String): MerchantCommissionAuditRecord?
    fun findByMerchantIdAndActorUserIdAndIdempotencyKey(merchantId: String, actorUserId: String, idempotencyKey: String): MerchantCommissionAuditRecord?
}

interface MerchantCommissionMerchantRepository : JpaRepository<MerchantRecord, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MerchantRecord m where m.id = :id")
    fun lockForCommission(id: String): MerchantRecord?
}

class MerchantCommissionConflict(val code: String, message: String) : RuntimeException(message)
