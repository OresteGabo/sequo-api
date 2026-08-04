package dev.orestegabo.sequo_api.domain.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class RateLimitRule(
    val maxAttempts: Int,
    val window: Duration,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive." }
        require(!window.isNegative && !window.isZero) { "window must be positive." }
    }
}

data class RateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long,
    val remainingAttempts: Int,
)

class RateLimitExceededException(
    val retryAfterSeconds: Long,
) : RuntimeException("Too many attempts. Please wait before trying again.")

@Component
class InMemoryRateLimiter {
    private val counters = ConcurrentHashMap<String, WindowCounter>()
    private val consumeCalls = AtomicInteger(0)

    fun consume(
        scope: String,
        subject: String,
        rule: RateLimitRule,
        occurredAt: Instant = Instant.now(),
    ): RateLimitDecision {
        require(scope.isNotBlank()) { "scope cannot be blank." }
        require(subject.isNotBlank()) { "subject cannot be blank." }
        cleanupIfNeeded(occurredAt)

        val key = "$scope:$subject"
        val counter = counters.computeIfAbsent(key) { WindowCounter(occurredAt, 0) }

        synchronized(counter) {
            val elapsed = Duration.between(counter.windowStartedAt, occurredAt)
            if (elapsed.isNegative || elapsed >= rule.window) {
                counter.windowStartedAt = occurredAt
                counter.count = 0
            }

            if (counter.count >= rule.maxAttempts) {
                val retryAfter = rule.window
                    .minus(Duration.between(counter.windowStartedAt, occurredAt))
                    .coerceAtLeast(Duration.ofSeconds(1))
                return RateLimitDecision(
                    allowed = false,
                    retryAfterSeconds = retryAfter.seconds,
                    remainingAttempts = 0,
                )
            }

            counter.count += 1
            return RateLimitDecision(
                allowed = true,
                retryAfterSeconds = 0,
                remainingAttempts = (rule.maxAttempts - counter.count).coerceAtLeast(0),
            )
        }
    }

    fun resetForTests() {
        counters.clear()
        consumeCalls.set(0)
    }

    private fun cleanupIfNeeded(occurredAt: Instant) {
        if (counters.size < MAX_COUNTERS && consumeCalls.incrementAndGet() % CLEANUP_INTERVAL != 0) {
            return
        }

        counters.entries.removeIf { (_, counter) ->
            Duration.between(counter.windowStartedAt, occurredAt) > MAX_COUNTER_AGE
        }
    }

    private data class WindowCounter(
        var windowStartedAt: Instant,
        var count: Int,
    )

    private companion object {
        private const val MAX_COUNTERS = 50_000
        private const val CLEANUP_INTERVAL = 250
        private val MAX_COUNTER_AGE: Duration = Duration.ofHours(2)
    }
}

@Component
class AuthRateLimiter(
    private val rateLimiter: InMemoryRateLimiter,
) {
    fun checkSignUp(email: String) {
        enforce(
            rateLimiter.consume("auth.signup.ip", clientSubject(), SIGNUP_BY_IP),
            rateLimiter.consume("auth.signup.email", normalizedSubject(email), SIGNUP_BY_EMAIL),
        )
    }

    fun checkEmailLogin(email: String) {
        enforce(
            rateLimiter.consume("auth.login.ip", clientSubject(), LOGIN_BY_IP),
            rateLimiter.consume("auth.login.email", normalizedSubject(email), LOGIN_BY_EMAIL),
        )
    }

    fun checkSocialLogin(provider: AuthProvider) {
        enforce(
            rateLimiter.consume("auth.social.ip", clientSubject(), SOCIAL_LOGIN_BY_IP),
            rateLimiter.consume("auth.social.provider", "${clientSubject()}:${provider.name}", SOCIAL_LOGIN_BY_PROVIDER),
        )
    }

    fun checkRefresh(refreshToken: String) {
        enforce(
            rateLimiter.consume("auth.refresh.ip", clientSubject(), REFRESH_BY_IP),
            rateLimiter.consume("auth.refresh.token", stableHash(refreshToken), REFRESH_BY_TOKEN),
        )
    }

    fun checkForgotPassword(email: String) {
        enforce(
            rateLimiter.consume("auth.forgot-password.ip", clientSubject(), FORGOT_PASSWORD_BY_IP),
            rateLimiter.consume("auth.forgot-password.email", normalizedSubject(email), FORGOT_PASSWORD_BY_EMAIL),
        )
    }

    fun checkResetPassword(token: String) {
        enforce(
            rateLimiter.consume("auth.reset-password.ip", clientSubject(), RESET_PASSWORD_BY_IP),
            rateLimiter.consume("auth.reset-password.token", stableHash(token), RESET_PASSWORD_BY_TOKEN),
        )
    }

    fun resetForTests() {
        rateLimiter.resetForTests()
    }

    private fun enforce(vararg decisions: RateLimitDecision) {
        val blocked = decisions.filter { !it.allowed }
        if (blocked.isNotEmpty()) {
            throw RateLimitExceededException(
                retryAfterSeconds = blocked.maxOf { it.retryAfterSeconds }.coerceAtLeast(1),
            )
        }
    }

    private fun clientSubject(): String {
        val request = currentRequest() ?: return "direct-test"
        return stableHash(request.remoteAddr ?: "unknown")
    }

    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request

    private fun normalizedSubject(value: String): String =
        stableHash(value.trim().lowercase())

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        private val SIGNUP_BY_IP = RateLimitRule(maxAttempts = 10, window = Duration.ofHours(1))
        private val SIGNUP_BY_EMAIL = RateLimitRule(maxAttempts = 3, window = Duration.ofHours(1))
        private val LOGIN_BY_IP = RateLimitRule(maxAttempts = 60, window = Duration.ofMinutes(15))
        private val LOGIN_BY_EMAIL = RateLimitRule(maxAttempts = 10, window = Duration.ofMinutes(15))
        private val SOCIAL_LOGIN_BY_IP = RateLimitRule(maxAttempts = 30, window = Duration.ofMinutes(15))
        private val SOCIAL_LOGIN_BY_PROVIDER = RateLimitRule(maxAttempts = 20, window = Duration.ofMinutes(15))
        private val REFRESH_BY_IP = RateLimitRule(maxAttempts = 120, window = Duration.ofMinutes(15))
        private val REFRESH_BY_TOKEN = RateLimitRule(maxAttempts = 30, window = Duration.ofMinutes(15))
        private val FORGOT_PASSWORD_BY_IP = RateLimitRule(maxAttempts = 10, window = Duration.ofHours(1))
        private val FORGOT_PASSWORD_BY_EMAIL = RateLimitRule(maxAttempts = 3, window = Duration.ofHours(1))
        private val RESET_PASSWORD_BY_IP = RateLimitRule(maxAttempts = 20, window = Duration.ofMinutes(15))
        private val RESET_PASSWORD_BY_TOKEN = RateLimitRule(maxAttempts = 5, window = Duration.ofMinutes(15))
    }
}
