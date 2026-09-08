package dev.orestegabo.sequo_api.domain.relay

import java.time.Duration
import java.time.Instant

data class RelayStorageFeeRequest(
    val depositedAt: Instant,
    val evaluatedAt: Instant,
    val pickedUpAt: Instant? = null,
    val collectedAt: Instant? = null,
    val dailyFeeCfa: Int,
    val policy: RelayParcelPolicyConfig = RelayParcelPolicyConfig(),
) {
    init {
        require(!evaluatedAt.isBefore(depositedAt)) { "evaluatedAt cannot be before depositedAt." }
        require(dailyFeeCfa >= 0) { "dailyFeeCfa cannot be negative." }
    }
}

data class RelayStorageFeeSnapshot(
    val eligible: Boolean,
    val chargeableDays: Long,
    val dailyFeeCfa: Int,
    val totalFeeCfa: Int,
    val feeStartsAt: Instant,
    val measuredUntil: Instant,
)

class RelayStorageFeeService {
    fun calculate(request: RelayStorageFeeRequest): RelayStorageFeeSnapshot {
        val resolvedAt = listOfNotNull(request.pickedUpAt, request.collectedAt).minOrNull()
        val measuredUntil = minOf(request.evaluatedAt, resolvedAt ?: request.evaluatedAt)
        val feeStartsAt = request.depositedAt.plus(request.policy.storageFeeAfter)
        if (!measuredUntil.isAfter(feeStartsAt)) {
            return snapshot(false, 0, request.dailyFeeCfa, feeStartsAt, measuredUntil)
        }

        val chargeableDuration = Duration.between(feeStartsAt, measuredUntil)
        val chargeableDays = chargeableDuration.toDays() + if (chargeableDuration.toSecondsPart() > 0 || chargeableDuration.nano > 0) 1 else 0
        val total = Math.multiplyExact(chargeableDays, request.dailyFeeCfa.toLong())
        require(total <= Int.MAX_VALUE) { "Calculated storage fee exceeds supported amount." }
        return snapshot(true, chargeableDays, request.dailyFeeCfa, feeStartsAt, measuredUntil, total.toInt())
    }

    private fun snapshot(
        eligible: Boolean,
        chargeableDays: Long,
        dailyFeeCfa: Int,
        feeStartsAt: Instant,
        measuredUntil: Instant,
        totalFeeCfa: Int = 0,
    ) = RelayStorageFeeSnapshot(eligible, chargeableDays, dailyFeeCfa, totalFeeCfa, feeStartsAt, measuredUntil)
}
