package dev.orestegabo.sequo_api.domain.commission

data class MerchantCommissionPolicy(
    val defaultRateBps: Int = DEFAULT_COMMISSION_RATE_BPS,
    val minRateBps: Int = MIN_COMMISSION_RATE_BPS,
    val maxRateBps: Int = MAX_COMMISSION_RATE_BPS,
) {
    init {
        require(minRateBps >= 0) { "minRateBps cannot be negative." }
        require(maxRateBps >= minRateBps) { "maxRateBps must be greater than or equal to minRateBps." }
        require(defaultRateBps in minRateBps..maxRateBps) { "defaultRateBps must be within the allowed range." }
    }

    companion object {
        const val DEFAULT_COMMISSION_RATE_BPS = 1500
        const val MIN_COMMISSION_RATE_BPS = 500
        const val MAX_COMMISSION_RATE_BPS = 1500
    }
}

data class MerchantCommissionInput(
    val merchantId: String,
    val baseAmountCfa: Int,
    val platformMarginCfa: Int,
    val merchantOverrideRateBps: Int? = null,
) {
    init {
        require(merchantId.isNotBlank()) { "merchantId is required." }
        require(baseAmountCfa >= 0) { "baseAmountCfa cannot be negative." }
        require(platformMarginCfa >= 0) { "platformMarginCfa cannot be negative." }
    }
}

data class MerchantCommissionSnapshot(
    val merchantId: String,
    val baseAmountCfa: Int,
    val platformMarginCfa: Int,
    val commissionRateBps: Int,
    val commissionCfa: Int,
    val merchantNetCfa: Int,
    val sequoItemRevenueCfa: Int,
)

class MerchantCommissionService(
    private val policy: MerchantCommissionPolicy = MerchantCommissionPolicy(),
) {
    fun calculate(input: MerchantCommissionInput): MerchantCommissionSnapshot {
        val rateBps = input.merchantOverrideRateBps ?: policy.defaultRateBps
        require(rateBps in policy.minRateBps..policy.maxRateBps) {
            "commissionRateBps must be between ${policy.minRateBps} and ${policy.maxRateBps}."
        }

        val commissionCfa = ((input.baseAmountCfa.toLong() * rateBps.toLong()) + HALF_BASIS_POINT_DENOMINATOR) /
            BASIS_POINT_DENOMINATOR
        val merchantNetCfa = input.baseAmountCfa - commissionCfa.toInt()

        return MerchantCommissionSnapshot(
            merchantId = input.merchantId,
            baseAmountCfa = input.baseAmountCfa,
            platformMarginCfa = input.platformMarginCfa,
            commissionRateBps = rateBps,
            commissionCfa = commissionCfa.toInt(),
            merchantNetCfa = merchantNetCfa,
            sequoItemRevenueCfa = input.platformMarginCfa + commissionCfa.toInt(),
        )
    }

    private companion object {
        const val BASIS_POINT_DENOMINATOR = 10_000L
        const val HALF_BASIS_POINT_DENOMINATOR = BASIS_POINT_DENOMINATOR / 2
    }
}
