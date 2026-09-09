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
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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
    fun findTop100ByUserIdOrderByCreatedAtDesc(userId: String): List<RefreshSession>
    fun findByIdAndUserId(id: String, userId: String): RefreshSession?

    @Modifying
    @Query("update RefreshSession s set s.revokedAt = :revokedAt, s.lastUsedAt = :usedAt where s.id = :id and s.revokedAt is null")
    fun revokeIfActive(
        @Param("id") id: String,
        @Param("revokedAt") revokedAt: Instant,
        @Param("usedAt") usedAt: Instant,
    ): Int
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

    fun rotate(session: RefreshSession, now: Instant = Instant.now()): Boolean =
        repository.revokeIfActive(requireNotNull(session.id), now, now) == 1

    fun revokeAllForUser(userId: String, now: Instant = Instant.now()): Int {
        val sessions = repository.findAllByUserIdAndRevokedAtIsNull(userId)
        sessions.forEach { it.revokedAt = now }
        if (sessions.isNotEmpty()) repository.saveAll(sessions)
        return sessions.size
    }

    @Transactional
    fun deleteExpired(at: Instant = Instant.now()): Int =
        repository.deleteAllByExpiresAtBefore(at)

    fun listForUser(userId: String): List<RefreshSession> =
        repository.findTop100ByUserIdOrderByCreatedAtDesc(userId)

    fun revokeForUser(sessionId: String, userId: String): Boolean {
        val session = repository.findByIdAndUserId(sessionId, userId) ?: return false
        revoke(session)
        return true
    }
}
