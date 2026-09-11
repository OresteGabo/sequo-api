package dev.orestegabo.sequo_api.domain.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JwtServiceTest {

    private val secret = "12345678901234567890123456789012"
    private val jwtService = JwtService(
        secret = secret,
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
            sampleSession(
                roles = setOf(RoleCode.MERCHANT_OWNER, RoleCode.COURIER),
                merchantScopeIds = setOf("merchant-a", "merchant-b"),
            )
        )

        val session = jwtService.parseAccessToken(tokens.accessToken)

        assertNotNull(session)
        assertEquals("user-1", session.userId)
        assertEquals("customer@sequo.test", session.email)
        assertEquals(AuthProvider.EMAIL, session.provider)
        assertEquals(setOf(RoleCode.MERCHANT_OWNER, RoleCode.COURIER), session.roles)
        assertEquals(setOf("merchant-a", "merchant-b"), session.merchantScopeIds)
        assertEquals("refresh-session-1", session.sessionId)
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
            secret = secret,
            accessExpiration = 60_000,
            refreshExpiration = 120_000,
            issuer = "other-issuer",
            audience = "sequo-mobile-test"
        )
        val wrongAudienceService = JwtService(
            secret = secret,
            accessExpiration = 60_000,
            refreshExpiration = 120_000,
            issuer = "sequo-api-test",
            audience = "other-audience"
        )

        assertNull(wrongIssuerService.validateAccessToken(tokens.accessToken))
        assertNull(wrongAudienceService.validateAccessToken(tokens.accessToken))
    }

    @Test
    fun unknownProviderOrRoleClaimsAreRejectedWithoutAuthenticating() {
        val unknownProvider = signedToken(provider = "PASSWORD", roles = listOf(RoleCode.CUSTOMER.name))
        val unknownRole = signedToken(provider = AuthProvider.EMAIL.name, roles = listOf("OPS_ADMIN"))
        val invalidRolesShape = signedToken(provider = AuthProvider.EMAIL.name, roles = mapOf("role" to "CUSTOMER"))

        assertNull(jwtService.parseAccessToken(unknownProvider))
        assertNull(jwtService.parseAccessToken(unknownRole))
        assertNull(jwtService.parseAccessToken(invalidRolesShape))
    }

    private fun sampleSession(
        roles: Set<RoleCode> = setOf(RoleCode.CUSTOMER),
        merchantScopeIds: Set<String> = emptySet(),
    ): UserSession =
        UserSession(
            userId = "user-1",
            email = "customer@sequo.test",
            provider = AuthProvider.EMAIL,
            roles = roles,
            merchantScopeIds = merchantScopeIds,
            sessionId = "refresh-session-1",
        )

    private fun signedToken(
        provider: String,
        roles: Any,
    ): String {
        val now = Date()
        return Jwts.builder()
            .issuer("sequo-api-test")
            .subject("user-1")
            .id(UUID.randomUUID().toString())
            .claim("aud", "sequo-mobile-test")
            .claim("email", "customer@sequo.test")
            .claim("provider", provider)
            .claim("roles", roles)
            .claim("token_use", TokenUse.ACCESS.name)
            .issuedAt(now)
            .notBefore(now)
            .expiration(Date(now.time + 60_000))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray()))
            .compact()
    }
}
