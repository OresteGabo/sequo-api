package dev.orestegabo.sequo_api.domain.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "user_devices",
    uniqueConstraints = [UniqueConstraint(name = "uk_user_devices_device_id", columnNames = ["device_id"])],
)
class UserDevice(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_user_devices_user"))
    val user: User? = null,

    @Column(name = "device_id", nullable = false, length = 255)
    val deviceId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "app_source", nullable = false, length = 32)
    val appSource: AppSource,

    @Column(name = "fcm_token", length = 512)
    var fcmToken: String? = null,

    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 96)
    var refreshTokenHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "last_active_at", nullable = false)
    var lastActiveAt: Instant = Instant.now(),

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
) {
    init {
        require(userId.isNotBlank()) { "userId is required." }
        require(deviceId.isNotBlank()) { "deviceId is required." }
        require(refreshTokenHash.isNotBlank()) { "refreshTokenHash is required." }
    }
}

@Entity
@Table(name = "auth_challenges")
class AuthChallenge(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 64)
    val purpose: AuthChallengePurpose,

    @Column(name = "subject", nullable = false, length = 255)
    val subject: String,

    @Column(name = "challenge_hash", nullable = false, length = 96)
    var challengeHash: String,

    @Column(name = "requesting_device_id", length = 255)
    var requestingDeviceId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "requesting_app_source", length = 32)
    var requestingAppSource: AppSource? = null,

    @Column(name = "approved_user_id", length = 255)
    var approvedUserId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null,
) {
    init {
        require(subject.isNotBlank()) { "subject is required." }
        require(challengeHash.isNotBlank()) { "challengeHash is required." }
    }
}
