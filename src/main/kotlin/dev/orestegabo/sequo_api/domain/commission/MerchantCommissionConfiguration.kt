package dev.orestegabo.sequo_api.domain.commission

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.security.MessageDigest
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class MerchantCommissionOverrideSnapshot(
    val merchantId: String,
    val commissionRateBps: Int,
    val reason: String?,
    val updatedByUserId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: String? = null,
    val operationId: String? = null,
)

@Entity
@Table(name = "merchant_commission_overrides")
class MerchantCommissionOverrideRecord(
    @Id
    @Column(name = "merchant_id")
    val merchantId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchant_commission_overrides_merchant"))
    val merchant: MerchantRecord? = null,

    @Column(name = "commission_rate_bps", nullable = false)
    var commissionRateBps: Int,

    @Column(name = "reason", length = 500)
    var reason: String? = null,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchant_commission_overrides_updated_by"))
    val updatedByUser: User? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,
)

interface MerchantCommissionOverrideRepository : JpaRepository<MerchantCommissionOverrideRecord, String>

@Service
class MerchantCommissionConfigurationService(
    private val overrides: MerchantCommissionOverrideRepository,
    private val audit: MerchantCommissionAuditRepository,
    private val merchants: MerchantCommissionMerchantRepository,
    private val calculator: MerchantCommissionService = MerchantCommissionService(),
) {
    private val policy = MerchantCommissionPolicy()

    @Transactional(readOnly = true)
    fun getOverride(merchantId: String): MerchantCommissionOverrideSnapshot? {
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        val record = overrides.findById(merchantId).orElse(null) ?: return null
        val latest = audit.findFirstByMerchantIdOrderByGenerationDesc(merchantId)
        return record.toSnapshot(revision(merchantId, record, latest?.id), latest?.id)
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    fun policyState(merchantId: String): MerchantCommissionPolicyState {
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        val record = overrides.findById(merchantId).orElse(null)
        val history = audit.findTop50ByMerchantIdOrderByGenerationDesc(merchantId)
        val latest = history.firstOrNull()
        val currentRevision = revision(merchantId, record, latest?.id)
        return MerchantCommissionPolicyState(merchantId, record?.commissionRateBps ?: policy.defaultRateBps,
            record?.toSnapshot(currentRevision, latest?.id), policy.defaultRateBps, policy.minRateBps, policy.maxRateBps,
            currentRevision, latest?.id, history.map { it.toSnapshot() })
    }

    @Transactional(readOnly = true)
    fun resolveRateBps(merchantId: String): Int {
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        return overrides.findById(merchantId).orElse(null)?.commissionRateBps ?: policy.defaultRateBps
    }

    @Transactional(readOnly = true)
    fun calculateForMerchant(
        merchantId: String,
        baseAmountCfa: Int,
        platformMarginCfa: Int,
    ): MerchantCommissionSnapshot =
        calculator.calculate(
            MerchantCommissionInput(
                merchantId = merchantId,
                baseAmountCfa = baseAmountCfa,
                platformMarginCfa = platformMarginCfa,
                merchantOverrideRateBps = resolveRateBps(merchantId),
            )
        )

    @Transactional(rollbackFor = [Exception::class])
    fun upsertOverride(
        merchantId: String,
        commissionRateBps: Int,
        updatedByUserId: String,
        reason: String?,
        updatedAt: Instant = Instant.now(),
        expectedRevision: String? = null,
        idempotencyKey: String? = null,
    ): MerchantCommissionOverrideSnapshot {
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        require(updatedByUserId.isNotBlank()) { "updatedByUserId cannot be blank." }
        require(commissionRateBps in policy.minRateBps..policy.maxRateBps) {
            "commissionRateBps must be between ${policy.minRateBps} and ${policy.maxRateBps}."
        }
        val sanitizedReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        require((sanitizedReason?.length ?: 0) <= 500) { "reason cannot exceed 500 characters." }
        validateGuard(expectedRevision, idempotencyKey)
        require(merchants.lockForCommission(merchantId) != null) { "Merchant was not found." }
        val intent = intent(MerchantCommissionAuditAction.SET, commissionRateBps, sanitizedReason, expectedRevision)
        replay(merchantId, updatedByUserId, idempotencyKey, intent)?.let { return it.overrideSnapshot() }
        val existing = overrides.findById(merchantId).orElse(null)
        val latest = audit.findFirstByMerchantIdOrderByGenerationDesc(merchantId)
        guard(expectedRevision, revision(merchantId, existing, latest?.id))
        val beforeRate = existing?.commissionRateBps ?: policy.defaultRateBps
        val beforePresent = existing != null
        val timestamp = updatedAt.truncatedTo(ChronoUnit.MICROS)
        val record = if (existing == null) {
            MerchantCommissionOverrideRecord(
                merchantId = merchantId,
                commissionRateBps = commissionRateBps,
                reason = sanitizedReason,
                updatedByUserId = updatedByUserId,
                createdAt = timestamp,
                updatedAt = timestamp,
            )
        } else {
            existing.apply {
                this.commissionRateBps = commissionRateBps
                this.reason = sanitizedReason
                this.updatedByUserId = updatedByUserId
                this.updatedAt = timestamp
            }
        }

        val saved = overrides.saveAndFlush(record)
        val entry = appendAudit(merchantId, MerchantCommissionAuditAction.SET, updatedByUserId, beforeRate, beforePresent,
            saved, sanitizedReason, idempotencyKey, intent, latest)
        return saved.toSnapshot(entry.revision, entry.id)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun clearOverride(merchantId: String, updatedByUserId: String? = null, reason: String? = null,
        expectedRevision: String? = null, idempotencyKey: String? = null): MerchantCommissionAuditSnapshot {
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        val safeReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        require((safeReason?.length ?: 0) <= 500) { "reason cannot exceed 500 characters." }
        validateGuard(expectedRevision, idempotencyKey)
        require(idempotencyKey == null || !updatedByUserId.isNullOrBlank()) { "An actor is required for a guarded reset." }
        require(merchants.lockForCommission(merchantId) != null) { "Merchant was not found." }
        val intent = intent(MerchantCommissionAuditAction.RESET, null, safeReason, expectedRevision)
        replay(merchantId, updatedByUserId, idempotencyKey, intent)?.let { return it.toSnapshot() }
        val existing = overrides.findById(merchantId).orElse(null)
        val latest = audit.findFirstByMerchantIdOrderByGenerationDesc(merchantId)
        guard(expectedRevision, revision(merchantId, existing, latest?.id))
        val beforeRate = existing?.commissionRateBps ?: policy.defaultRateBps
        if (existing != null) { overrides.delete(existing); overrides.flush() }
        return appendAudit(merchantId, MerchantCommissionAuditAction.RESET, updatedByUserId, beforeRate, existing != null,
            null, safeReason, idempotencyKey, intent, latest).toSnapshot()
    }

    private fun appendAudit(merchantId: String, action: MerchantCommissionAuditAction, actor: String?, beforeRate: Int,
        beforePresent: Boolean, after: MerchantCommissionOverrideRecord?, reason: String?, key: String?, hash: String,
        latest: MerchantCommissionAuditRecord?): MerchantCommissionAuditRecord {
        val id = UUID.randomUUID().toString()
        return audit.saveAndFlush(MerchantCommissionAuditRecord(id, merchantId, Math.addExact(latest?.generation ?: 0L, 1L),
            action, actor, beforeRate, after?.commissionRateBps ?: policy.defaultRateBps, beforePresent, after != null,
            reason, Instant.now().truncatedTo(ChronoUnit.MICROS), key, hash, revision(merchantId, after, id), after?.createdAt, after?.updatedAt))
    }

    private fun replay(merchantId: String, actor: String?, key: String?, hash: String): MerchantCommissionAuditRecord? {
        if (key == null || actor == null) return null
        val existing = audit.findByMerchantIdAndActorUserIdAndIdempotencyKey(merchantId, actor, key) ?: return null
        if (existing.intentHash != hash) throw MerchantCommissionConflict("commission_retry_conflict", "This retry key already records another commission intent. Refresh before editing.")
        return existing
    }

    private fun validateGuard(expectedRevision: String?, key: String?) {
        require((expectedRevision == null) == (key == null)) { "expectedRevision and idempotencyKey must be supplied together." }
        require(expectedRevision == null || expectedRevision.matches(Regex("[a-f0-9]{64}"))) { "expectedRevision must be a server revision." }
        if (key != null) dev.orestegabo.sequo_api.api.ApiInputPolicy.requiredIdempotencyKey(key)
    }

    private fun guard(expected: String?, actual: String) {
        if (expected != null && expected != actual) throw MerchantCommissionConflict("commission_stale_revision", "Commission settings changed. Refresh and review the current policy.")
    }

    private fun intent(action: MerchantCommissionAuditAction, rate: Int?, reason: String?, expected: String?) = digest(
        listOf(action.name, rate?.toString(), reason, expected))

    private fun revision(merchantId: String, record: MerchantCommissionOverrideRecord?, auditId: String?) = digest(listOf(
        merchantId, policy.defaultRateBps.toString(), policy.minRateBps.toString(), policy.maxRateBps.toString(),
        auditId, record?.commissionRateBps?.toString(), record?.reason, record?.updatedByUserId,
        record?.createdAt?.truncatedTo(ChronoUnit.MICROS)?.toString(), record?.updatedAt?.truncatedTo(ChronoUnit.MICROS)?.toString()))

    private fun digest(values: List<String?>): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("") { value -> value?.let { "${it.length}:$it" } ?: "-:" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun MerchantCommissionOverrideRecord.toSnapshot(revision: String? = null, operationId: String? = null): MerchantCommissionOverrideSnapshot =
    MerchantCommissionOverrideSnapshot(
        merchantId = merchantId,
        commissionRateBps = commissionRateBps,
        reason = reason,
        updatedByUserId = updatedByUserId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        revision = revision,
        operationId = operationId,
    )
