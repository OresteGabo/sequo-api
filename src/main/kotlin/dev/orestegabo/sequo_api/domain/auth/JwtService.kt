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
    @Value("\${sequo.auth.jwt.refresh-expiration}") private val refreshExpiration: Long
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generateTokens(session: UserSession): AuthTokens {
        val now = Date()
        
        val accessToken = Jwts.builder()
            .subject(session.userId)
            .claim("email", session.email)
            .claim("provider", session.provider.name)
            .issuedAt(now)
            .expiration(Date(now.time + accessExpiration))
            .signWith(key)
            .compact()

        val refreshToken = Jwts.builder()
            .subject(session.userId)
            .claim("email", session.email)
            .claim("provider", session.provider.name)
            .issuedAt(now)
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
        return try {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
                .subject
        } catch (e: Exception) {
            null
        }
    }

    fun parseToken(token: String): UserSession? {
        return try {
            val claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
            
            UserSession(
                userId = claims.subject,
                email = claims["email"] as? String,
                provider = AuthProvider.valueOf(claims["provider"] as String)
            )
        } catch (e: Exception) {
            null
        }
    }
}
