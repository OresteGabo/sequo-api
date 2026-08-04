package dev.orestegabo.sequo_api.domain.auth

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRateLimiterTest {
    @Test
    fun inMemoryLimiterRejectsRequestsAfterWindowBudgetIsConsumed() {
        val limiter = InMemoryRateLimiter()
        val rule = RateLimitRule(maxAttempts = 2, window = Duration.ofMinutes(1))
        val now = Instant.parse("2026-08-04T10:00:00Z")

        val first = limiter.consume("auth.login.email", "customer", rule, now)
        val second = limiter.consume("auth.login.email", "customer", rule, now.plusSeconds(1))
        val third = limiter.consume("auth.login.email", "customer", rule, now.plusSeconds(2))

        assertTrue(first.allowed)
        assertTrue(second.allowed)
        assertFalse(third.allowed)
        assertEquals(58, third.retryAfterSeconds)
        assertEquals(0, third.remainingAttempts)
    }

    @Test
    fun inMemoryLimiterResetsAfterWindowExpires() {
        val limiter = InMemoryRateLimiter()
        val rule = RateLimitRule(maxAttempts = 1, window = Duration.ofSeconds(30))
        val now = Instant.parse("2026-08-04T10:00:00Z")

        limiter.consume("auth.forgot-password.email", "customer", rule, now)
        val blocked = limiter.consume("auth.forgot-password.email", "customer", rule, now.plusSeconds(10))
        val allowedAfterWindow = limiter.consume("auth.forgot-password.email", "customer", rule, now.plusSeconds(31))

        assertFalse(blocked.allowed)
        assertTrue(allowedAfterWindow.allowed)
        assertEquals(0, allowedAfterWindow.remainingAttempts)
    }
}
