package dev.orestegabo.sequo_api.config

import dev.orestegabo.sequo_api.domain.auth.InMemoryRateLimiter
import dev.orestegabo.sequo_api.domain.auth.RateLimitRule
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter

class ApiRequestRateLimitFilter(
    private val rateLimiter: InMemoryRateLimiter,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!isRateLimitedRoute(request)) {
            filterChain.doFilter(request, response)
            return
        }

        val decision = rateLimiter.consume(
            scope = "api.request.ip",
            subject = stableHash(request.remoteAddr ?: "unknown"),
            rule = API_RULE,
        )
        if (!decision.allowed) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader("Retry-After", decision.retryAfterSeconds.toString())
            response.contentType = "application/json"
            response.writer.write(
                "{\"code\":\"rate_limited\",\"message\":\"Too many requests. Please try again later.\",\"retryAfterSeconds\":${decision.retryAfterSeconds}}"
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun isRateLimitedRoute(request: HttpServletRequest): Boolean {
        if (request.method == HttpMethod.OPTIONS.name()) return false
        val path = request.requestURI ?: return false
        return path.startsWith("/api/") && !path.startsWith("/api/auth/")
    }

    private fun stableHash(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        )

    private companion object {
        val API_RULE = RateLimitRule(maxAttempts = 300, window = Duration.ofMinutes(1))
    }
}
