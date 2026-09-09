package dev.orestegabo.sequo_api.config

import dev.orestegabo.sequo_api.domain.auth.InMemoryRateLimiter
import jakarta.servlet.FilterChain
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ApiRequestRateLimitFilterTest {

    @Test
    fun limitsBusinessApiRequestsAndReturnsRetryAfter() {
        val filter = ApiRequestRateLimitFilter(InMemoryRateLimiter())
        val chain = Mockito.mock(FilterChain::class.java)
        val request = MockHttpServletRequest("GET", "/api/orders").apply { remoteAddr = "192.0.2.10" }

        repeat(300) {
            filter.doFilter(request, MockHttpServletResponse(), chain)
        }
        val blocked = MockHttpServletResponse()
        filter.doFilter(request, blocked, chain)

        assertEquals(429, blocked.status)
        assertTrue(blocked.getHeader("Retry-After")!!.toLong() > 0)
        assertTrue(blocked.contentAsString.contains("rate_limited"))
    }

    @Test
    fun doesNotApplyGenericLimitToAuthOrPreflightRequests() {
        val filter = ApiRequestRateLimitFilter(InMemoryRateLimiter())
        val chain = Mockito.mock(FilterChain::class.java)

        filter.doFilter(MockHttpServletRequest("POST", "/api/auth/login"), MockHttpServletResponse(), chain)
        filter.doFilter(MockHttpServletRequest("OPTIONS", "/api/orders"), MockHttpServletResponse(), chain)

        Mockito.verify(chain, Mockito.times(2)).doFilter(Mockito.any(), Mockito.any())
    }
}
