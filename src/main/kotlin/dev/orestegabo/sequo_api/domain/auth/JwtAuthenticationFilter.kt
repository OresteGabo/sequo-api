package dev.orestegabo.sequo_api.domain.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

data class JwtAuthenticationDetails(
    val webAuthenticationDetails: Any?,
    val sessionId: String?,
    val merchantScopeIds: Set<String> = emptySet(),
)

@Component
class JwtAuthenticationFilter(private val jwtService: JwtService) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader("Authorization")
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.substring(7)
            val session = jwtService.parseAccessToken(token)

            if (session != null && SecurityContextHolder.getContext().authentication == null) {
                val authorities = session.roles.map { it.toGrantedAuthority() }
                val authToken = UsernamePasswordAuthenticationToken(session.userId, null, authorities)
                authToken.details = JwtAuthenticationDetails(
                    webAuthenticationDetails = WebAuthenticationDetailsSource().buildDetails(request),
                    sessionId = session.sessionId,
                    merchantScopeIds = session.merchantScopeIds,
                )
                SecurityContextHolder.getContext().authentication = authToken
            }
        }
        
        filterChain.doFilter(request, response)
    }
}
