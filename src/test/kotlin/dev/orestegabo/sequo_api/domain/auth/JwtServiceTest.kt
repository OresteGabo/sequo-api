package dev.orestegabo.sequo_api.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JwtServiceTest {

    private val jwtService = JwtService(
        secret = "12345678901234567890123456789012",
        accessExpiration = 60_000,
        refreshExpiration = 120_000,
        issuer = "sequo-api-test",
        audience = "sequo-mobile-test"
    )

    @Test
    fun accessTokenAuthenticatesProtectedRequests() {
        val tokens = jwtService.generateTokens(sampleSession())

        assertEquals("user-1", jwtService.validateAccessToken(tokens.accessToken))
    }

    @Test
    fun accessTokenParserRestoresSessionRoles() {
        val tokens = jwtService.generateTokens(
            sampleSession(roles = setOf(RoleCode.MERCHANT_OWNER, RoleCode.COURIER))
        )

        val session = jwtService.parseAccessToken(tokens.accessToken)

        assertNotNull(session)
        assertEquals("user-1", session.userId)
        assertEquals("customer@sequo.test", session.email)
        assertEquals(AuthProvider.EMAIL, session.provider)
        assertEquals(setOf(RoleCode.MERCHANT_OWNER, RoleCode.COURIER), session.roles)
    }

    @Test
    fun refreshTokenCannotAuthenticateProtectedRequests() {
        val tokens = jwtService.generateTokens(sampleSession())

        assertNull(jwtService.validateAccessToken(tokens.refreshToken))
        assertNull(jwtService.parseAccessToken(tokens.refreshToken))
    }

    @Test
    fun refreshParserAcceptsOnlyRefreshTokens() {
        val tokens = jwtService.generateTokens(sampleSession())

        val refreshSession = jwtService.parseRefreshToken(tokens.refreshToken)

        assertNotNull(refreshSession)
        assertEquals("user-1", refreshSession.userId)
        assertNull(jwtService.parseRefreshToken(tokens.accessToken))
    }

    @Test
    fun generatedTokensHaveDifferentIdentifiers() {
        val tokens = jwtService.generateTokens(sampleSession())

        assertNotEquals(tokens.accessToken, tokens.refreshToken)
    }

    @Test
    fun wrongIssuerOrAudienceIsRejected() {
        val tokens = jwtService.generateTokens(sampleSession())
        val wrongIssuerService = JwtService(
            secret = "12345678901234567890123456789012",
            accessExpiration = 60_000,
            refreshExpiration = 120_000,
            issuer = "other-issuer",
            audience = "sequo-mobile-test"
        )
        val wrongAudienceService = JwtService(
            secret = "12345678901234567890123456789012",
            accessExpiration = 60_000,
            refreshExpiration = 120_000,
            issuer = "sequo-api-test",
            audience = "other-audience"
        )

        assertNull(wrongIssuerService.validateAccessToken(tokens.accessToken))
        assertNull(wrongAudienceService.validateAccessToken(tokens.accessToken))
    }

    private fun sampleSession(
        roles: Set<RoleCode> = setOf(RoleCode.CUSTOMER)
    ): UserSession =
        UserSession(
            userId = "user-1",
            email = "customer@sequo.test",
            provider = AuthProvider.EMAIL,
            roles = roles
        )
}
