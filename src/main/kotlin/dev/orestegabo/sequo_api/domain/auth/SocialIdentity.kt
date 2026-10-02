package dev.orestegabo.sequo_api.domain.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "user_identities",
    uniqueConstraints = [UniqueConstraint(name = "uk_user_identities_provider_user", columnNames = ["provider", "provider_user_id"])],
)
class UserIdentity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_social_identities_user"))
    val user: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    val provider: AuthProvider,

    @Column(name = "provider_user_id", nullable = false, length = 512)
    val providerUserId: String,

    @Column(name = "credential_public_key", columnDefinition = "text")
    var credentialPublicKey: String? = null,

    @Column(name = "sign_count", nullable = false)
    var signCount: Long = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
) {
    init {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        require(provider in setOf(AuthProvider.GOOGLE, AuthProvider.APPLE, AuthProvider.FACEBOOK, AuthProvider.PASSKEY)) {
            "User identities must use a supported passwordless provider."
        }
        require(providerUserId.isNotBlank()) { "providerUserId cannot be blank." }
        require(signCount >= 0) { "signCount cannot be negative." }
    }
}
