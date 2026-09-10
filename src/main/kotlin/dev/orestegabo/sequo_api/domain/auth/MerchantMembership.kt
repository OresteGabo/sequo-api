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
    name = "merchant_memberships",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_merchant_memberships_user_merchant_role",
            columnNames = ["user_id", "merchant_id", "role_code"],
        )
    ],
)
class MerchantMembership(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Column(name = "merchant_id", nullable = false)
    val merchantId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false)
    val role: RoleCode,

    @Column(nullable = false)
    var active: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    init {
        require(userId.isNotBlank()) { "userId is required." }
        require(merchantId.isNotBlank()) { "merchantId is required." }
        require(role == RoleCode.MERCHANT_OWNER || role == RoleCode.MERCHANT_STAFF) {
            "Merchant membership role must be MERCHANT_OWNER or MERCHANT_STAFF."
        }
    }
}
