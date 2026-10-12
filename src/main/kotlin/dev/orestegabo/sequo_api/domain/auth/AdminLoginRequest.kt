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

@Entity
@Table(name = "admin_login_requests")
class AdminLoginRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String? = null,

    @Column(name = "email_normalized", nullable = false, length = 320)
    val emailNormalized: String,

    @Column(name = "code_hash", nullable = false, length = 96)
    var codeHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: AdminLoginRequestStatus = AdminLoginRequestStatus.PENDING,

    @Column(name = "device_label", length = 255)
    var deviceLabel: String? = null,

    @Column(name = "browser_info", length = 255)
    var browserInfo: String? = null,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "approved_at")
    var approvedAt: Instant? = null,

    @Column(name = "rejected_at")
    var rejectedAt: Instant? = null,

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null,

    @Column(name = "approved_by_user_id", length = 255)
    var approvedByUserId: String? = null,

    @Column(name = "rejection_reason", length = 255)
    var rejectionReason: String? = null,

    @Column(name = "created_ip_hash", length = 96)
    var createdIpHash: String? = null,
) {
    init {
        require(emailNormalized.isNotBlank()) { "emailNormalized is required." }
        require(codeHash.isNotBlank()) { "codeHash is required." }
        require(expiresAt.isAfter(requestedAt)) { "expiresAt must be after requestedAt." }
        require(deviceLabel == null || deviceLabel!!.length <= 255) { "deviceLabel is too long." }
        require(browserInfo == null || browserInfo!!.length <= 255) { "browserInfo is too long." }
        require(rejectionReason == null || rejectionReason!!.length <= 255) { "rejectionReason is too long." }
    }
}
