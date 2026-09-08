package dev.orestegabo.sequo_api.domain.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${sequo.auth.jwt.secret}") private val secret: String,
    @Value("\${sequo.auth.jwt.access-expiration}") private val accessExpiration: Long,
    @Value("\${sequo.auth.jwt.refresh-expiration}") private val refreshExpiration: Long,
    @Value("\${sequo.auth.jwt.issuer:sequo-api}") private val issuer: String,
    @Value("\${sequo.auth.jwt.audience:sequo-mobile}") private val audience: String
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generateTokens(session: UserSession): AuthTokens {
        val now = Date()
        
        val accessToken = Jwts.builder()
            .issuer(issuer)
            .subject(session.userId)
            .id(UUID.randomUUID().toString())
            .claim("aud", audience)
            .claim("email", session.email)
            .claim("provider", session.provider.name)
            .claim("roles", session.roles.map { it.name })
            .claim("token_use", TokenUse.ACCESS.name)
            .issuedAt(now)
            .notBefore(now)
            .expiration(Date(now.time + accessExpiration))
            .signWith(key)
            .compact()

        val refreshToken = Jwts.builder()
            .issuer(issuer)
            .subject(session.userId)
            .id(UUID.randomUUID().toString())
            .claim("aud", audience)
            .claim("email", session.email)
            .claim("provider", session.provider.name)
            .claim("token_use", TokenUse.REFRESH.name)
            .issuedAt(now)
            .notBefore(now)
            .expiration(Date(now.time + refreshExpiration))
            .signWith(key)
            .compact()

        return AuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = accessExpiration / 1000
        )
    }

    fun validateToken(token: String): String? {
        return validateAccessToken(token)
    }

    fun validateAccessToken(token: String): String? {
        return parseAccessToken(token)?.userId
    }

    fun parseAccessToken(token: String): UserSession? {
        return try {
            val claims = parseClaims(token) ?: return null
            if (!claimsMatchTokenUse(claims, TokenUse.ACCESS)) return null

            UserSession(
                userId = claims.subject?.takeIf { it.isNotBlank() } ?: return null,
                email = claims["email"] as? String,
                provider = AuthProvider.valueOf(claims["provider"] as String),
                roles = parseRoles(claims["roles"]),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseToken(token: String): UserSession? = parseRefreshToken(token)

    fun parseRefreshToken(token: String): UserSession? {
        return try {
            val claims = parseClaims(token) ?: return null
            if (!claimsMatchTokenUse(claims, TokenUse.REFRESH)) return null
            
            UserSession(
                userId = claims.subject,
                email = claims["email"] as? String,
                provider = AuthProvider.valueOf(claims["provider"] as String)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRoles(rolesClaim: Any?): Set<RoleCode> {
        val roleNames = when (rolesClaim) {
            is Collection<*> -> rolesClaim.mapNotNull { it as? String }
            is String -> listOf(rolesClaim)
            null -> emptyList()
            else -> throw IllegalArgumentException("Invalid roles claim")
        }

        return roleNames
            .map { RoleCode.valueOf(it) }
            .toSet()
            .ifEmpty { setOf(RoleCode.CUSTOMER) }
    }

    private fun parseClaims(token: String) = try {
        val claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload

        if (claims.issuer != issuer || !audienceMatches(claims)) {
            null
        } else {
            claims
        }
    } catch (e: Exception) {
        null
    }

    private fun claimsMatchTokenUse(
        claims: io.jsonwebtoken.Claims,
        expectedTokenUse: TokenUse
    ): Boolean = claims["token_use"] == expectedTokenUse.name

    private fun audienceMatches(claims: io.jsonwebtoken.Claims): Boolean {
        val audienceClaim = claims["aud"]
        return when (audienceClaim) {
            is String -> audienceClaim == audience
            is Collection<*> -> audienceClaim.contains(audience)
            else -> false
        }
    }
}
