package dev.orestegabo.sequo_api.domain.notification

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Service

data class WebSocketPresenceSnapshot(
    val userId: String,
    val appFamily: NotificationAppFamily,
    val activeSessions: Int,
    val lastConnectedAt: Instant?,
    val lastDisconnectedAt: Instant?,
)

@Service
class WebSocketPresenceService {
    private val states = ConcurrentHashMap<PresenceKey, MutableMap<String, SessionPresence>>()

    fun connected(
        userId: String,
        appFamily: NotificationAppFamily,
        sessionId: String,
        connectedAt: Instant = Instant.now(),
    ): WebSocketPresenceSnapshot {
        validate(userId, sessionId)
        val key = PresenceKey(userId, appFamily)
        val sessions = states.computeIfAbsent(key) { mutableMapOf() }
        sessions[sessionId] = SessionPresence(connectedAt = connectedAt)
        return key.toSnapshot(sessions)
    }

    fun disconnected(
        userId: String,
        appFamily: NotificationAppFamily,
        sessionId: String,
        disconnectedAt: Instant = Instant.now(),
    ): WebSocketPresenceSnapshot {
        validate(userId, sessionId)
        val key = PresenceKey(userId, appFamily)
        val sessions = states[key] ?: return key.toSnapshot(emptyMap(), lastDisconnectedAt = disconnectedAt)
        sessions.remove(sessionId)
        if (sessions.isEmpty()) states.remove(key)
        return key.toSnapshot(sessions, lastDisconnectedAt = disconnectedAt)
    }

    fun activeSessionCount(userId: String, appFamily: NotificationAppFamily): Int {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        return states[PresenceKey(userId, appFamily)]?.size ?: 0
    }

    private fun validate(userId: String, sessionId: String) {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        require(sessionId.isNotBlank()) { "sessionId cannot be blank." }
    }
}

private data class PresenceKey(
    val userId: String,
    val appFamily: NotificationAppFamily,
)

private data class SessionPresence(
    val connectedAt: Instant,
)

private fun PresenceKey.toSnapshot(
    sessions: Map<String, SessionPresence>,
    lastDisconnectedAt: Instant? = null,
): WebSocketPresenceSnapshot =
    WebSocketPresenceSnapshot(
        userId = userId,
        appFamily = appFamily,
        activeSessions = sessions.size,
        lastConnectedAt = sessions.values.maxByOrNull { it.connectedAt }?.connectedAt,
        lastDisconnectedAt = lastDisconnectedAt,
    )
