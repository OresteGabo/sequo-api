package dev.orestegabo.sequo_api.domain.notification

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebSocketPresenceServiceTest {
    private val service = WebSocketPresenceService()

    @Test
    fun tracksMultipleSessionsForSameUserAndApp() {
        service.connected("user-1", NotificationAppFamily.SEQUO_CUSTOMER, "session-1", Instant.parse("2026-09-09T08:00:00Z"))
        val snapshot = service.connected("user-1", NotificationAppFamily.SEQUO_CUSTOMER, "session-2", Instant.parse("2026-09-09T08:01:00Z"))

        assertEquals(2, snapshot.activeSessions)
        assertEquals(2, service.activeSessionCount("user-1", NotificationAppFamily.SEQUO_CUSTOMER))
        assertEquals(0, service.activeSessionCount("user-1", NotificationAppFamily.SEQUO_RIDER))
    }

    @Test
    fun disconnectRemovesOnlyTheRequestedSession() {
        service.connected("user-1", NotificationAppFamily.SEQUO_CUSTOMER, "session-1")
        service.connected("user-1", NotificationAppFamily.SEQUO_CUSTOMER, "session-2")

        val snapshot = service.disconnected("user-1", NotificationAppFamily.SEQUO_CUSTOMER, "session-1")

        assertEquals(1, snapshot.activeSessions)
        assertEquals(1, service.activeSessionCount("user-1", NotificationAppFamily.SEQUO_CUSTOMER))
    }

    @Test
    fun rejectsBlankPresenceReferences() {
        assertFailsWith<IllegalArgumentException> {
            service.connected("", NotificationAppFamily.SEQUO_CUSTOMER, "session-1")
        }
        assertFailsWith<IllegalArgumentException> {
            service.connected("user-1", NotificationAppFamily.SEQUO_CUSTOMER, "")
        }
    }
}
