package dev.orestegabo.sequo_api.domain.catalog

import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

enum class CatalogProductStatus {
    ACTIVE,
    INACTIVE,
    ARCHIVED,
}

@Entity
@Table(name = "products")
class ProductRecord(
    @Id
    @Column(name = "id", nullable = false)
    val id: String,

    @Column(name = "merchant_id")
    val merchantId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_products_merchant"))
    val merchant: MerchantRecord? = null,

    @Column(name = "name", nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 64)
    var kind: CatalogProductKind = CatalogProductKind.SellerSpecific,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    var status: CatalogProductStatus = CatalogProductStatus.ACTIVE,

    @Column(name = "category", length = 128)
    var category: String? = null,

    @Column(name = "subcategory", length = 128)
    var subcategory: String? = null,

    @Column(name = "detail", length = 500)
    var detail: String? = null,

    @Column(name = "base_price_cfa")
    var basePriceCfa: Int? = null,

    @Column(name = "option_hint", length = 240)
    var optionHint: String? = null,

    @Column(name = "original_price_cfa")
    var originalPriceCfa: Int? = null,

    @Column(name = "bargaining_enabled", nullable = false)
    var bargainingEnabled: Boolean = false,

    @Column(name = "bargain_floor_cfa")
    var bargainFloorCfa: Int? = null,

    @Column(name = "camera_verified", nullable = false)
    var cameraVerified: Boolean = false,

    @Column(name = "captured_at_label", length = 120)
    var capturedAtLabel: String? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "catalog_images")
class CatalogImageRecord(
    @Id
    @Column(name = "id", nullable = false)
    val id: String,

    @Column(name = "product_id", nullable = false)
    val productId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_catalog_images_product"))
    val product: ProductRecord? = null,

    @Column(name = "storage_key", nullable = false)
    var storageKey: String,

    @Column(name = "public_url", nullable = false)
    var publicUrl: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 64)
    var source: ProductMediaSource,

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 64)
    var moderationStatus: ProductMediaModerationStatus = ProductMediaModerationStatus.NeedsReview,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
