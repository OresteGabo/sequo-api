package dev.orestegabo.sequo_api.domain.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtAuthenticationFilterTest {

    private val jwtService = JwtService(
        secret = "12345678901234567890123456789012",
        accessExpiration = 60_000,
        refreshExpiration = 120_000,
        issuer = "sequo-api-test",
        audience = "sequo-mobile-test"
    )
    private val filter = JwtAuthenticationFilter(jwtService)

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun bearerAccessTokenPopulatesPrincipalAndRoleAuthorities() {
        val tokens = jwtService.generateTokens(
            UserSession(
                userId = "courier-1",
                email = "courier@sequo.test",
                provider = AuthProvider.EMAIL,
                roles = setOf(RoleCode.COURIER, RoleCode.RELAY_PARTNER)
            )
        )
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ${tokens.accessToken}")
        }

        filter.doFilter(request, MockHttpServletResponse(), PassThroughChain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertEquals("courier-1", authentication.name)
        assertTrue(authentication.authorities.any { it.authority == "ROLE_COURIER" })
        assertTrue(authentication.authorities.any { it.authority == "ROLE_RELAY_PARTNER" })
    }

    @Test
    fun refreshTokenDoesNotAuthenticateRequest() {
        val tokens = jwtService.generateTokens(
            UserSession(
                userId = "user-1",
                email = "customer@sequo.test",
                provider = AuthProvider.EMAIL
            )
        )
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ${tokens.refreshToken}")
        }

        filter.doFilter(request, MockHttpServletResponse(), PassThroughChain)

        assertEquals(null, SecurityContextHolder.getContext().authentication)
    }

    private object PassThroughChain : FilterChain {
        override fun doFilter(request: ServletRequest, response: ServletResponse) = Unit
    }
}
