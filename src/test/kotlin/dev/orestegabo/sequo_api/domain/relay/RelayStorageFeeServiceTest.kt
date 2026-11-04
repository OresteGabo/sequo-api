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

    @Test
    fun chargesConfiguredDailyFeeOnlyAfterEightDays() {
        val result = service.calculate(
            RelayStorageFeeRequest(
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(10)),
                dailyFeeCfa = 250,
            )
        )

        assertTrue(result.eligible)
        assertEquals(2, result.chargeableDays)
        assertEquals(500, result.totalFeeCfa)
    }

    @Test
    fun chargesOnlyAfterTheExactFeeBoundary() {
        val threshold = depositedAt.plus(Duration.ofDays(8))
        fun calculateAt(time: Instant) = service.calculate(RelayStorageFeeRequest(depositedAt, time, dailyFeeCfa = 250))

        assertEquals(0, calculateAt(threshold.minusNanos(1)).totalFeeCfa)
        assertEquals(0, calculateAt(threshold).totalFeeCfa)
        assertEquals(250, calculateAt(threshold.plusNanos(1)).totalFeeCfa)
        assertEquals(threshold, calculateAt(threshold.plusNanos(1)).feeStartsAt)
    }

    @Test
    fun doesNotChargeBeforeThresholdOrAfterParcelWasResolved() {
        val beforeThreshold = service.calculate(
            RelayStorageFeeRequest(depositedAt, depositedAt.plus(Duration.ofDays(8)), dailyFeeCfa = 250)
        )
        val pickedUp = service.calculate(
            RelayStorageFeeRequest(
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(20)),
                pickedUpAt = depositedAt.plus(Duration.ofDays(9)),
                dailyFeeCfa = 250,
            )
        )

        assertFalse(beforeThreshold.eligible)
        assertEquals(0, beforeThreshold.totalFeeCfa)
        assertEquals(1, pickedUp.chargeableDays)
        assertEquals(250, pickedUp.totalFeeCfa)
        assertEquals(depositedAt.plus(Duration.ofDays(9)), pickedUp.measuredUntil)
    }

    @Test
    fun rejectsInvalidFeeAndImpossibleDates() {
        assertFailsWith<IllegalArgumentException> {
            RelayStorageFeeRequest(depositedAt, depositedAt, dailyFeeCfa = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RelayStorageFeeRequest(depositedAt, depositedAt.minusSeconds(1), dailyFeeCfa = 250)
        }
    }
}
