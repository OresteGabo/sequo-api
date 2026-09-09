package dev.orestegabo.sequo_api.domain.commission

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
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
)

@Entity
@Table(name = "merchant_commission_overrides")
class MerchantCommissionOverrideRecord(
    @Id
    @Column(name = "merchant_id")
    val merchantId: String,

    @Column(name = "commission_rate_bps", nullable = false)
    var commissionRateBps: Int,

    @Column(name = "reason", length = 500)
    var reason: String? = null,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,
)

interface MerchantCommissionOverrideRepository : JpaRepository<MerchantCommissionOverrideRecord, String>

@Service
class MerchantCommissionConfigurationService(
    private val overrides: MerchantCommissionOverrideRepository,
    private val calculator: MerchantCommissionService = MerchantCommissionService(),
) {
    private val policy = MerchantCommissionPolicy()

    @Transactional(readOnly = true)
    fun getOverride(merchantId: String): MerchantCommissionOverrideSnapshot? {
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        return overrides.findById(merchantId).orElse(null)?.toSnapshot()
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

    @Transactional
    fun upsertOverride(
        merchantId: String,
        commissionRateBps: Int,
        updatedByUserId: String,
        reason: String?,
        updatedAt: Instant = Instant.now(),
    ): MerchantCommissionOverrideSnapshot {
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        require(updatedByUserId.isNotBlank()) { "updatedByUserId cannot be blank." }
        require(commissionRateBps in policy.minRateBps..policy.maxRateBps) {
            "commissionRateBps must be between ${policy.minRateBps} and ${policy.maxRateBps}."
        }
        val sanitizedReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        require((sanitizedReason?.length ?: 0) <= 500) { "reason cannot exceed 500 characters." }

        val existing = overrides.findById(merchantId).orElse(null)
        val record = if (existing == null) {
            MerchantCommissionOverrideRecord(
                merchantId = merchantId,
                commissionRateBps = commissionRateBps,
                reason = sanitizedReason,
                updatedByUserId = updatedByUserId,
                createdAt = updatedAt,
                updatedAt = updatedAt,
            )
        } else {
            existing.apply {
                this.commissionRateBps = commissionRateBps
                this.reason = sanitizedReason
                this.updatedByUserId = updatedByUserId
                this.updatedAt = updatedAt
            }
        }

        return overrides.save(record).toSnapshot()
    }

    @Transactional
    fun clearOverride(merchantId: String) {
        require(merchantId.isNotBlank()) { "merchantId cannot be blank." }
        if (overrides.existsById(merchantId)) {
            overrides.deleteById(merchantId)
        }
    }
}

private fun MerchantCommissionOverrideRecord.toSnapshot(): MerchantCommissionOverrideSnapshot =
    MerchantCommissionOverrideSnapshot(
        merchantId = merchantId,
        commissionRateBps = commissionRateBps,
        reason = reason,
        updatedByUserId = updatedByUserId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
