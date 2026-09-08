package dev.orestegabo.sequo_api.domain.relay

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelayStorageFeeServiceTest {
    private val service = RelayStorageFeeService()
    private val depositedAt = Instant.parse("2026-09-01T10:00:00Z")
    private val policy = RelayParcelPolicyConfig(storageFeeAfter = Duration.ofDays(14), returnToSellerReviewAfter = Duration.ofDays(28))

    @Test
    fun chargesConfiguredDailyFeeOnlyAfterFourteenDays() {
        val result = service.calculate(
            RelayStorageFeeRequest(
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(16)),
                dailyFeeCfa = 250,
                policy = policy,
            )
        )

        assertTrue(result.eligible)
        assertEquals(2, result.chargeableDays)
        assertEquals(500, result.totalFeeCfa)
    }

    @Test
    fun doesNotChargeBeforeThresholdOrAfterParcelWasResolved() {
        val beforeThreshold = service.calculate(
            RelayStorageFeeRequest(depositedAt, depositedAt.plus(Duration.ofDays(14)), dailyFeeCfa = 250, policy = policy)
        )
        val pickedUp = service.calculate(
            RelayStorageFeeRequest(
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(20)),
                pickedUpAt = depositedAt.plus(Duration.ofDays(15)),
                dailyFeeCfa = 250,
                policy = policy,
            )
        )

        assertFalse(beforeThreshold.eligible)
        assertEquals(0, beforeThreshold.totalFeeCfa)
        assertEquals(1, pickedUp.chargeableDays)
        assertEquals(250, pickedUp.totalFeeCfa)
        assertEquals(depositedAt.plus(Duration.ofDays(15)), pickedUp.measuredUntil)
    }

    @Test
    fun rejectsInvalidFeeAndImpossibleDates() {
        assertFailsWith<IllegalArgumentException> {
            RelayStorageFeeRequest(depositedAt, depositedAt, dailyFeeCfa = -1, policy = policy)
        }
        assertFailsWith<IllegalArgumentException> {
            RelayStorageFeeRequest(depositedAt, depositedAt.minusSeconds(1), dailyFeeCfa = 250, policy = policy)
        }
    }
}
