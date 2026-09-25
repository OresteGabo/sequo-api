package dev.orestegabo.sequo_api.domain.party

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import dev.orestegabo.sequo_api.domain.auth.User
import java.time.Instant

@Entity
@Table(name = "merchants")
class MerchantRecord(
    @Id
    @Column(name = "id", nullable = false)
    val id: String,

    @Column(name = "owner_user_id")
    val ownerUserId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchants_owner_user"))
    val ownerUser: User? = null,

    @Column(name = "name", nullable = false)
    var name: String,

    @Column(name = "status", nullable = false, length = 64)
    var status: String = "ACTIVE",

    @Column(name = "commission_rate_bps", nullable = false)
    var commissionRateBps: Int = 1500,

    @Column(name = "wallet_provider", length = 64)
    var walletProvider: String? = null,

    @Column(name = "wallet_account_ref")
    var walletAccountRef: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "couriers")
class CourierRecord(
    @Id
    @Column(name = "id", nullable = false)
    val id: String,

    @Column(name = "user_id", unique = true)
    val userId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_couriers_user"))
    val user: User? = null,

    @Column(name = "status", nullable = false, length = 64)
    var status: String = "ACTIVE",

    @Column(name = "workforce_type", length = 64)
    var workforceType: String? = null,

    @Column(name = "vehicle_type", length = 64)
    var vehicleType: String? = null,

    @Column(name = "wallet_provider", length = 64)
    var walletProvider: String? = null,

    @Column(name = "wallet_account_ref")
    var walletAccountRef: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "relay_points")
class RelayPointRecord(
    @Id
    @Column(name = "id", nullable = false)
    val id: String,

    @Column(name = "operator_user_id")
    val operatorUserId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_points_operator_user"))
    val operatorUser: User? = null,

    @Column(name = "name", nullable = false)
    var name: String,

    @Column(name = "status", nullable = false, length = 64)
    var status: String = "ACTIVE",

    @Column(name = "city")
    var city: String? = null,

    @Column(name = "neighborhood")
    var neighborhood: String? = null,

    @Column(name = "landmark", length = 500)
    var landmark: String? = null,

    @Column(name = "wallet_provider", length = 64)
    var walletProvider: String? = null,

    @Column(name = "wallet_account_ref")
    var walletAccountRef: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
