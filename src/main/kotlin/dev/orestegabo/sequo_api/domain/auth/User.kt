package dev.orestegabo.sequo_api.domain.auth

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(unique = true, nullable = false)
    val email: String,

    @Column
    var passwordHash: String? = null,

    @Column
    var name: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val provider: AuthProvider,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: UserStatus = UserStatus.ACTIVE,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false)
    var roles: MutableSet<RoleCode> = mutableSetOf(RoleCode.CUSTOMER),

    @Column(unique = true)
    val providerId: String? = null,

    @Column(name = "reset_token_hash")
    var resetTokenHash: String? = null,

    @Column
    var resetTokenExpiry: Instant? = null
)
