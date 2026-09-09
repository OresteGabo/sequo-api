package dev.orestegabo.sequo_api.domain.notification

import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
class WebSocketPresenceEventListener(
    private val presenceService: WebSocketPresenceService,
) {
    @EventListener
    fun onConnected(event: SessionConnectedEvent) {
        val accessor = StompHeaderAccessor.wrap(event.message)
        val userId = accessor.user?.name ?: return
        val sessionId = accessor.sessionId ?: return
        val appFamily = accessor.sessionAttributes?.get(NotificationAppFamilyAttribute) as? NotificationAppFamily
            ?: NotificationAppFamily.SEQUO_CUSTOMER

        presenceService.connected(userId, appFamily, sessionId)
    }

    @EventListener
    fun onDisconnected(event: SessionDisconnectEvent) {
        val accessor = StompHeaderAccessor.wrap(event.message)
        val userId = accessor.user?.name ?: return
        val sessionId = accessor.sessionId ?: return
        val appFamily = accessor.sessionAttributes?.get(NotificationAppFamilyAttribute) as? NotificationAppFamily
            ?: NotificationAppFamily.SEQUO_CUSTOMER

        presenceService.disconnected(userId, appFamily, sessionId)
    }

    companion object {
        const val NotificationAppFamilyHeader = "x-sequo-app-family"
        const val NotificationAppFamilyAttribute = "sequoAppFamily"
    }
}
