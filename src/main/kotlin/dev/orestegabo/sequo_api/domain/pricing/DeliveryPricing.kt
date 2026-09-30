package dev.orestegabo.sequo_api.domain.pricing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import org.springframework.data.jpa.repository.JpaRepository

data class DeliveryPricingInput(
    val distanceKm: Double,
    val subscriptionDiscountPercent: Int = 0,
    val referralCreditCfa: Int = 0,
)

data class DeliveryPricingBreakdown(
    val billableKm: Int,
    val baseFeeCfa: Int,
    val subscriptionDiscountCfa: Int,
    val referralCreditAppliedCfa: Int,
    val finalDeliveryFeeCfa: Int,
)

@Entity
@Table(name = "delivery_pricing_settings")
class DeliveryPricingSettingsRecord(
    @Id
    @Column(name = "id", nullable = false)
    val id: String,

    @Column(name = "profile", nullable = false, length = 64)
    val profile: String,

    @Column(name = "minimum_delivery_fee_cfa", nullable = false)
    val minimumDeliveryFeeCfa: Int,

    @Column(name = "extra_km_fee_cfa", nullable = false)
    val extraKmFeeCfa: Int,

    @Column(name = "included_km", nullable = false)
    val includedKm: Int,

    @Column(name = "active", nullable = false)
    val active: Boolean,

    @Column(name = "effective_from", nullable = false)
    val effectiveFrom: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Version
    @Column(name = "version", nullable = false)
    val version: Long = 0,
)

interface DeliveryPricingSettingsRepository : JpaRepository<DeliveryPricingSettingsRecord, String> {
    fun findFirstByActiveTrueOrderByEffectiveFromDescCreatedAtDesc(): DeliveryPricingSettingsRecord?
}

data class DeliveryPricingSettings(
    val minimumDeliveryFeeCfa: Int,
    val extraKmFeeCfa: Int,
    val includedKm: Int,
) {
    init {
        require(minimumDeliveryFeeCfa >= 0) { "minimumDeliveryFeeCfa must be non-negative" }
        require(extraKmFeeCfa >= 0) { "extraKmFeeCfa must be non-negative" }
        require(includedKm >= 0) { "includedKm must be non-negative" }
    }
}

class DeliveryPricingService(
    private val settingsRepository: DeliveryPricingSettingsRepository? = null,
) {
    fun calculate(input: DeliveryPricingInput): DeliveryPricingBreakdown {
        require(input.distanceKm >= 0.0) { "distanceKm must be non-negative" }
        require(input.subscriptionDiscountPercent in 0..100) {
            "subscriptionDiscountPercent must be between 0 and 100"
        }
        require(input.referralCreditCfa >= 0) { "referralCreditCfa must be non-negative" }

        val settings = activeSettings()
        val billableKm = ceil(input.distanceKm).toInt()
        val baseFeeCfa = if (billableKm <= settings.includedKm) {
            settings.minimumDeliveryFeeCfa
        } else {
            settings.minimumDeliveryFeeCfa + ((billableKm - settings.includedKm) * settings.extraKmFeeCfa)
        }
        val subscriptionDiscountCfa = (baseFeeCfa * input.subscriptionDiscountPercent) / 100
        val feeAfterSubscription = baseFeeCfa - subscriptionDiscountCfa
        val referralCreditAppliedCfa = input.referralCreditCfa.coerceAtMost(feeAfterSubscription)

        return DeliveryPricingBreakdown(
            billableKm = billableKm,
            baseFeeCfa = baseFeeCfa,
            subscriptionDiscountCfa = subscriptionDiscountCfa,
            referralCreditAppliedCfa = referralCreditAppliedCfa,
            finalDeliveryFeeCfa = max(feeAfterSubscription - referralCreditAppliedCfa, 0),
        )
    }

    private fun activeSettings(): DeliveryPricingSettings =
        settingsRepository
            ?.findFirstByActiveTrueOrderByEffectiveFromDescCreatedAtDesc()
            ?.toSettings()
            ?: DefaultSettings

    companion object {
        val DefaultSettings = DeliveryPricingSettings(
            minimumDeliveryFeeCfa = 400,
            extraKmFeeCfa = 100,
            includedKm = 5,
        )
    }
}

private fun DeliveryPricingSettingsRecord.toSettings(): DeliveryPricingSettings =
    DeliveryPricingSettings(
        minimumDeliveryFeeCfa = minimumDeliveryFeeCfa,
        extraKmFeeCfa = extraKmFeeCfa,
        includedKm = includedKm,
    )
