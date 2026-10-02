package dev.orestegabo.sequo_api.domain.auth

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "phone_number", unique = true)
    var phoneNumber: String? = null,

    @Column(unique = true)
    var email: String? = null,

    @Column(name = "display_name")
    var name: String? = null,

    @Column(name = "avatar_url")
    var avatarUrl: String? = null,

    @Column(name = "is_active", nullable = false)
    var active: Boolean = true,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var provider: AuthProvider = AuthProvider.EMAIL,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: UserStatus = UserStatus.ACTIVE,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false)
    var roles: MutableSet<RoleCode> = mutableSetOf(RoleCode.CUSTOMER),

    @Column
    var createdAt: Instant = Instant.now(),

    @Column
    var updatedAt: Instant = Instant.now(),

    @get:Transient
    var passwordHash: String? = null,

    @get:Transient
    var resetTokenHash: String? = null,

    @get:Transient
    var resetTokenExpiry: Instant? = null,
) {
    @get:Transient
    var displayName: String?
        get() = name
        set(value) {
            name = value
        }

    @PrePersist
    fun beforePersist() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun beforeUpdate() {
        updatedAt = Instant.now()
    }
}
