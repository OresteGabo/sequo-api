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

enum class AdminEmailCodeStatus {
    PENDING,
    CONSUMED,
}

@Entity
@Table(name = "admin_email_codes")
class AdminEmailCode(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "email_normalized", nullable = false, length = 320)
    val emailNormalized: String,

    @Column(name = "code_hash", nullable = false, length = 96)
    var codeHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: AdminEmailCodeStatus = AdminEmailCodeStatus.PENDING,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null,

    @Column(name = "user_id", length = 255)
    val userId: String? = null,
) {
    init {
        require(emailNormalized.isNotBlank()) { "emailNormalized is required." }
        require(codeHash.isNotBlank()) { "codeHash is required." }
        require(expiresAt.isAfter(requestedAt)) { "expiresAt must be after requestedAt." }
    }
}
