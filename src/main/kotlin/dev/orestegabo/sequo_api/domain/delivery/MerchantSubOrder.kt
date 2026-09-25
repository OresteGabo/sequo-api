package dev.orestegabo.sequo_api.domain.delivery

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
import jakarta.persistence.Version
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import java.time.Instant

enum class MerchantSubOrderStatus {
    MERCHANT_PENDING,
    ACCEPTED,
    PREPARING,
    PACKED_READY,
    HANDED_TO_COURIER,
    REJECTED,
    CANCELLED,
}

@Entity
@Table(name = "merchant_sub_orders")
class MerchantSubOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "sub_order_code", nullable = false, unique = true, length = 64)
    val subOrderCode: String,

    @Column(name = "order_id", nullable = false)
    val orderId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchant_sub_orders_order"))
    val order: CustomerOrderRecord? = null,

    @Column(name = "merchant_id", nullable = false)
    val merchantId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchant_sub_orders_merchant"))
    val merchant: MerchantRecord? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    var status: MerchantSubOrderStatus = MerchantSubOrderStatus.MERCHANT_PENDING,

    @Column(name = "item_subtotal_cfa", nullable = false)
    val itemSubtotalCfa: Int = 0,

    @Column(name = "commission_rate_bps", nullable = false)
    val commissionRateBps: Int = 1500,

    @Column(name = "commission_cfa", nullable = false)
    val commissionCfa: Int = 0,

    @Column(name = "merchant_net_cfa", nullable = false)
    val merchantNetCfa: Int = 0,

    @Column(name = "package_count", nullable = false)
    var packageCount: Int = 0,

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,

    @Column(name = "preparing_at")
    var preparingAt: Instant? = null,

    @Column(name = "packed_ready_at")
    var packedReadyAt: Instant? = null,

    @Column(name = "handed_to_courier_at")
    var handedToCourierAt: Instant? = null,

    @Column(name = "rejection_reason", length = 500)
    var rejectionReason: String? = null,

    @Column(name = "seller_response_due_at")
    val sellerResponseDueAt: Instant? = null,

    @Column(name = "packing_due_at")
    val packingDueAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
