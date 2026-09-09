package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.JwtService
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import java.security.Principal
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class RealtimeWebSocketConfig(
    private val jwtService: JwtService,
    @Value("\${sequo.security.cors.allowed-origins:}") private val corsAllowedOrigins: List<String>,
) : WebSocketMessageBrokerConfigurer {
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val origins = corsAllowedOrigins.map { it.trim() }.filter { it.isNotBlank() }.toTypedArray()
        registry.addEndpoint("/ws").setAllowedOrigins(*origins)
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.setApplicationDestinationPrefixes("/app")
        registry.setUserDestinationPrefix("/user")
        registry.enableSimpleBroker("/topic", "/queue")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(
            StompJwtAuthenticationInterceptor(jwtService),
            StompSubscriptionAuthorizationInterceptor(RealtimeSubscriptionAuthorizationService()),
        )
    }
}

class StompJwtAuthenticationInterceptor(
    private val jwtService: JwtService,
) : ChannelInterceptor {
    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message
        if (accessor.command != StompCommand.CONNECT) return message

        val rawHeader = accessor.getFirstNativeHeader("Authorization")
            ?: accessor.getFirstNativeHeader("authorization")
            ?: throw RealtimeAuthenticationException("Missing Authorization header.")
        if (!rawHeader.startsWith("Bearer ")) {
            throw RealtimeAuthenticationException("Authorization header must use Bearer token.")
        }

        val session = jwtService.parseAccessToken(rawHeader.removePrefix("Bearer ").trim())
            ?: throw RealtimeAuthenticationException("Invalid access token.")
        val authentication = UsernamePasswordAuthenticationToken(
            session.userId,
            null,
            session.roles.map { it.toGrantedAuthority() },
        )
        accessor.user = authentication
        return message
    }
}

class StompSubscriptionAuthorizationInterceptor(
    private val authorization: RealtimeSubscriptionAuthorizationService,
) : ChannelInterceptor {
    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message
        if (accessor.command != StompCommand.SUBSCRIBE) return message

        val authentication = accessor.user as? Authentication
            ?: throw RealtimeAuthenticationException("WebSocket subscription requires authentication.")
        val destination = accessor.destination
            ?: throw RealtimeAuthorizationException("WebSocket subscription destination is required.")
        val decision = authorization.authorize(authentication, destination)
        if (!decision.allowed) throw RealtimeAuthorizationException(decision.reason)
        return message
    }
}

data class RealtimeAuthorizationDecision(
    val allowed: Boolean,
    val reason: String,
)

class RealtimeSubscriptionAuthorizationService {
    fun authorize(principal: Principal?, destination: String): RealtimeAuthorizationDecision {
        val authentication = principal as? Authentication
            ?: return deny("Realtime subscription requires authentication.")
        if (!authentication.isAuthenticated) return deny("Realtime subscription requires authentication.")

        return when {
            destination == "/user/queue/notifications" -> allow("Authenticated user notification queue.")
            destination == "/user/queue/rider-missions" -> requireAnyRole(
                authentication,
                setOf(RoleCode.COURIER),
                "Courier mission queue requires courier role.",
            )
            destination == "/user/queue/bargaining" -> requireAnyRole(
                authentication,
                setOf(RoleCode.CUSTOMER, RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF),
                "Bargaining queue requires customer or merchant role.",
            )
            destination == "/topic/admin/operations" -> requireAnyRole(
                authentication,
                setOf(RoleCode.SUPPORT_AGENT, RoleCode.ADMIN, RoleCode.SUPER_ADMIN),
                "Admin operations topic requires support or admin role.",
            )
            else -> deny("Realtime destination is not allowed.")
        }
    }

    private fun requireAnyRole(
        authentication: Authentication,
        roles: Set<RoleCode>,
        denial: String,
    ): RealtimeAuthorizationDecision =
        if (authentication.hasAnyRole(roles)) allow("Realtime role authorized.") else deny(denial)

    private fun allow(reason: String) = RealtimeAuthorizationDecision(true, reason)

    private fun deny(reason: String) = RealtimeAuthorizationDecision(false, reason)
}

class RealtimeAuthenticationException(message: String) : RuntimeException(message)
class RealtimeAuthorizationException(message: String) : RuntimeException(message)
