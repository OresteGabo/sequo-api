package dev.orestegabo.sequo_api.domain.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "refresh_sessions")
class RefreshSession(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    val tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
)

interface RefreshSessionRepository : org.springframework.data.jpa.repository.JpaRepository<RefreshSession, String> {
    fun findByTokenHash(tokenHash: String): RefreshSession?
    fun findAllByUserIdAndRevokedAtIsNull(userId: String): List<RefreshSession>
    fun deleteAllByExpiresAtBefore(expiresAt: Instant): Int
}

@org.springframework.stereotype.Service
class RefreshSessionService(
    private val repository: RefreshSessionRepository,
    private val tokenService: PasswordResetTokenService,
) {
    fun issueRawToken(): String = tokenService.generate().rawToken

    fun findByToken(rawToken: String): RefreshSession? =
        repository.findByTokenHash(tokenService.hash(rawToken))

    fun create(userId: String, rawToken: String, expiresAt: Instant, now: Instant = Instant.now()): RefreshSession =
        repository.save(
            RefreshSession(
                userId = userId,
                tokenHash = tokenService.hash(rawToken),
                expiresAt = expiresAt,
                createdAt = now,
            )
        )

    fun revoke(session: RefreshSession, now: Instant = Instant.now()) {
        if (session.revokedAt == null) {
            session.revokedAt = now
            repository.save(session)
        }
    }

    fun revokeAllForUser(userId: String, now: Instant = Instant.now()): Int {
        val sessions = repository.findAllByUserIdAndRevokedAtIsNull(userId)
        sessions.forEach { it.revokedAt = now }
        if (sessions.isNotEmpty()) repository.saveAll(sessions)
        return sessions.size
    }

    @Transactional
    fun deleteExpired(at: Instant = Instant.now()): Int =
        repository.deleteAllByExpiresAtBefore(at)
}
