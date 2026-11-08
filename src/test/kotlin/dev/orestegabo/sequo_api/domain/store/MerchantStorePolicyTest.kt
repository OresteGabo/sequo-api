package dev.orestegabo.sequo_api.domain.store

import java.time.Instant
import kotlin.test.*

class MerchantStorePolicyTest {
    private val hours = (1..7).map { StoreOpeningHoursDay(it, "08:00", "18:00") }
    private fun policy() = MerchantStoreSettings(true, true, "Africa/Lome", hours, emptyList())
    private fun status(time: String, settings: MerchantStoreSettings = policy()) = MerchantStorePolicy.status(settings, Instant.parse(time))

    @Test fun openingIsInclusiveAndClosingExclusiveAtBothBoundaries() {
        assertEquals("OUTSIDE_HOURS", status("2026-11-09T07:59:59.999999Z"))
        assertEquals("OPEN", status("2026-11-09T08:00:00Z"))
        assertEquals("OPEN", status("2026-11-09T08:00:00.000001Z"))
        assertEquals("OPEN", status("2026-11-09T17:59:59.999999Z"))
        assertEquals("OUTSIDE_HOURS", status("2026-11-09T18:00:00Z"))
        assertEquals("OUTSIDE_HOURS", status("2026-11-09T18:00:00.000001Z"))
    }

    @Test fun datedExceptionOverridesWeekAndTemporaryClosureUsesRealInstantBoundaries() {
        val holiday = policy().copy(temporaryExceptions = listOf(StoreOpeningHoursException("holiday", "2026-11-09", true)))
        assertEquals("CLOSED_BY_EXCEPTION", status("2026-11-09T10:00:00Z", holiday))
        val timed = policy().copy(temporaryClosure = StoreTemporaryClosure(startsAt = "2026-11-09T10:00:00Z", endsAt = "2026-11-09T12:00:00Z"))
        assertEquals("OPEN", status("2026-11-09T09:59:59.999999Z", timed))
        assertEquals("TEMPORARILY_CLOSED", status("2026-11-09T10:00:00Z", timed))
        assertEquals("TEMPORARILY_CLOSED", status("2026-11-09T10:00:00.000001Z", timed))
        assertEquals("TEMPORARILY_CLOSED", status("2026-11-09T11:59:59.999999Z", timed))
        assertEquals("OPEN", status("2026-11-09T12:00:00Z", timed))
        assertEquals("OPEN", status("2026-11-09T12:00:00.000001Z", timed))
    }

    @Test fun explicitClosedOrNotAcceptingOrdersWinsAndNoScheduleMeansManualMode() {
        assertEquals("PAUSED", status("2026-11-09T10:00:00Z", policy().copy(open = false)))
        assertEquals("NOT_ACCEPTING_ORDERS", status("2026-11-09T10:00:00Z", policy().copy(acceptingOrders = false)))
        assertEquals("OPEN", status("2026-11-09T02:00:00Z", policy().copy(openingHours = emptyList())))
    }
}
