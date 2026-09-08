package dev.orestegabo.sequo_api.domain.auth

import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecurityConfigCorsTest {

    @Test
    fun corsConfigurationUsesExplicitAllowedOriginsAndSafeHeaders() {
        val configurationSource = SecurityConfig(
            jwtAuthenticationFilter = JwtAuthenticationFilter(jwtService()),
            corsAllowedOrigins = listOf(
                "https://app.sequo.example",
                " https://admin.sequo.example ",
                "",
            ),
        ).corsConfigurationSource()

        val configuration = configurationSource.getCorsConfiguration(MockHttpServletRequest())

        assertNotNull(configuration)
        assertEquals(
            listOf("https://app.sequo.example", "https://admin.sequo.example"),
            configuration.allowedOrigins,
        )
        assertEquals(true, configuration.allowCredentials)
        assertTrue(requireNotNull(configuration.allowedMethods).contains("OPTIONS"))
        assertTrue(requireNotNull(configuration.allowedHeaders).contains("Authorization"))
        assertTrue(requireNotNull(configuration.exposedHeaders).contains("Retry-After"))
    }

    private fun jwtService(): JwtService =
        JwtService(
            secret = "12345678901234567890123456789012",
            accessExpiration = 60_000,
            refreshExpiration = 120_000,
            issuer = "sequo-api-test",
            audience = "sequo-mobile-test",
        )
}
