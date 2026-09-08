package dev.orestegabo.sequo_api.domain.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "social_identities",
    uniqueConstraints = [UniqueConstraint(name = "uk_social_identity_provider_subject", columnNames = ["provider", "provider_subject"])],
)
class SocialIdentity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    val provider: AuthProvider,

    @Column(name = "provider_subject", nullable = false, length = 255)
    val providerSubject: String,

    @Column(name = "verified_email", length = 255)
    val verifiedEmail: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
) {
    init {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        require(provider != AuthProvider.EMAIL) { "Social identities cannot use the EMAIL provider." }
        require(providerSubject.isNotBlank()) { "providerSubject cannot be blank." }
        require(verifiedEmail?.isNotBlank() ?: true) { "verifiedEmail cannot be blank." }
    }
}
