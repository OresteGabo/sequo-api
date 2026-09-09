package dev.orestegabo.sequo_api.domain.delivery

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class CourierAvailabilityServiceTest @Autowired constructor(
    private val service: CourierAvailabilityService,
) {
    @Test
    fun unknownCourierDefaultsToActive() {
        val availability = service.get("courier-availability-default", Instant.parse("2026-09-09T10:00:00Z"))

        assertEquals(CourierAvailabilityStatus.ACTIVE, availability.status)
        assertFalse(availability.paused)
    }

    @Test
    fun activePauseBlocksUntilItExpiresOrIsCleared() {
        val pausedAt = Instant.parse("2026-09-09T10:00:00Z")
        val pausedUntil = Instant.parse("2026-09-09T11:00:00Z")

        val paused = service.pause(
            courierId = "courier-availability-paused",
            actorUserId = "admin-availability",
            reason = "Repeated no-show during pickup.",
            pausedUntil = pausedUntil,
            at = pausedAt,
        )
        val duringPause = service.get("courier-availability-paused", Instant.parse("2026-09-09T10:30:00Z"))
        val afterExpiry = service.get("courier-availability-paused", Instant.parse("2026-09-09T11:01:00Z"))
        val unpaused = service.unpause(
            courierId = "courier-availability-paused",
            actorUserId = "admin-availability",
            at = Instant.parse("2026-09-09T10:45:00Z"),
        )

        assertTrue(paused.paused)
        assertTrue(duringPause.paused)
        assertEquals("Repeated no-show during pickup.", duringPause.pausedReason)
        assertFalse(afterExpiry.paused)
        assertEquals(CourierAvailabilityStatus.ACTIVE, afterExpiry.status)
        assertFalse(unpaused.paused)
    }

    @Test
    fun invalidPauseDataIsRejected() {
        val at = Instant.parse("2026-09-09T10:00:00Z")
        val blankReason = kotlin.runCatching {
            service.pause("courier-invalid-pause", "admin-availability", "", at = at)
        }
        val pastPauseEnd = kotlin.runCatching {
            service.pause(
                courierId = "courier-invalid-pause",
                actorUserId = "admin-availability",
                reason = "Invalid window.",
                pausedUntil = at.minusSeconds(1),
                at = at,
            )
        }

        assertTrue(blankReason.exceptionOrNull() is IllegalArgumentException)
        assertTrue(pastPauseEnd.exceptionOrNull() is IllegalArgumentException)
    }
}
