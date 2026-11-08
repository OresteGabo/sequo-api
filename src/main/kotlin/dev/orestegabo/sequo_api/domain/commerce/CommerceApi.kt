package dev.orestegabo.sequo_api.domain.commerce

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.api.ApiErrorResponse
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.merchantScopeIds
import dev.orestegabo.sequo_api.domain.catalog.CatalogCameraMedia
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus
import dev.orestegabo.sequo_api.domain.catalog.ProductMediaModerationStatus
import dev.orestegabo.sequo_api.domain.catalog.ProductMediaSource
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationSelection
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.platform.PlatformCommerceService
import dev.orestegabo.sequo_api.domain.platform.ProductCustomizationGroupDto
import dev.orestegabo.sequo_api.domain.platform.QuoteProductCustomizationsRequest
import dev.orestegabo.sequo_api.domain.platform.ReplaceProductCustomizationsRequest
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.LockModeType
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "catalog_categories")
class CatalogCategoryRecord(
    @Id @Column(name = "category_key") val key: String,
    @Column(nullable = false) val title: String,
    @Column(name = "support_label", nullable = false) val supportLabel: String,
    @Column(name = "accent_hex", nullable = false) val accentHex: String,
    @Column(name = "sort_order", nullable = false) val sortOrder: Int,
    @Column(nullable = false) val active: Boolean = true,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "merchant_storefronts")
class MerchantStorefrontRecord(
    @Id @Column(name = "merchant_id") val merchantId: String,
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_merchant_storefronts_merchant"))
    val merchant: MerchantRecord? = null,
    @Column val area: String? = null,
    @Column val kind: String? = null,
    @Column(name = "distance_km") val distanceKm: Double? = null,
    @Column val eta: String? = null,
    @Column(name = "photo_status") val photoStatus: String? = null,
    @Column(name = "open_status") val openStatus: String? = null,
    @Column val rating: String? = null,
    @Column val consolidation: String? = null,
    @Column(nullable = false) val active: Boolean = true,
    @Column(name = "sort_order", nullable = false) val sortOrder: Int = 0,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = createdAt,
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "catalog_promotions")
class CatalogPromotionRecord(
    @Id val id: String,
    @Column(nullable = false) val headline: String,
    @Column(nullable = false) val title: String,
    @Column(nullable = false) val subtitle: String,
    @Column(name = "product_id", nullable = false) val productId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_catalog_promotions_product"))
    val product: ProductRecord? = null,
    @Column(name = "starts_at") val startsAt: Instant? = null,
    @Column(name = "ends_at") val endsAt: Instant? = null,
    @Column(name = "sort_order", nullable = false) val sortOrder: Int,
    @Column(nullable = false) val active: Boolean = true,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "customer_cart_items")
class CustomerCartItemRecord(
    @Id val id: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "product_id", nullable = false) val productId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_customer_cart_items_product"))
    val product: ProductRecord? = null,
    @Column(nullable = false) var quantity: Int,
    @Column(name = "configuration_key", nullable = false, length = 64) val configurationKey: String = "",
    @Column(name = "selections_json", nullable = false, columnDefinition = "text") val selectionsJson: String = "[]",
    @Column(name = "bargaining_thread_id") var bargainingThreadId: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(nullable = false) var version: Long = 0,
)

enum class BargainingThreadStatus { OPEN, ACCEPTED, REJECTED, EXPIRED, CANCELLED, LOCKED_ATTEMPTS_EXHAUSTED }
enum class BargainingActorType { CUSTOMER, MERCHANT, SUPPORT }
enum class BargainingOfferType { OFFER, COUNTER_OFFER, ACCEPT, REJECT, MESSAGE }

@Entity
@Table(name = "bargaining_threads")
class BargainingThreadRecord(
    @Id val id: String,
    @Column(name = "customer_id", nullable = false) val customerId: String,
    @Column(name = "merchant_id", nullable = false) val merchantId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_bargaining_threads_merchant"))
    val merchant: MerchantRecord? = null,
    @Column(name = "product_id", nullable = false) val productId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_bargaining_threads_product"))
    val product: ProductRecord? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: BargainingThreadStatus = BargainingThreadStatus.OPEN,
    @Column(name = "opened_at", nullable = false) val openedAt: Instant = Instant.now(),
    @Column(name = "closed_at") var closedAt: Instant? = null,
    @Column(name = "expires_at") var expiresAt: Instant? = null,
    @Column(name = "last_offer_cfa") var lastOfferCfa: Int? = null,
    @Column(name = "accepted_price_cfa") var acceptedPriceCfa: Int? = null,
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "bargaining_offers", uniqueConstraints = [jakarta.persistence.UniqueConstraint(
    name = "ux_bargaining_offer_actor_retry", columnNames = ["actor_user_id", "idempotency_key"],
)])
class BargainingOfferRecord(
    @Id val id: String,
    @Column(name = "thread_id", nullable = false) val threadId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_bargaining_offers_thread"))
    val thread: BargainingThreadRecord? = null,
    @Column(name = "actor_user_id", nullable = false) val actorUserId: String,
    @Enumerated(EnumType.STRING) @Column(name = "actor_type", nullable = false) val actorType: BargainingActorType,
    @Enumerated(EnumType.STRING) @Column(name = "offer_type", nullable = false) val offerType: BargainingOfferType,
    @Column(name = "amount_cfa") val amountCfa: Int? = null,
    @Column(length = 500) val message: String? = null,
    @Column(name = "idempotency_key", length = 128) val idempotencyKey: String? = null,
    @Column(name = "request_fingerprint", length = 64) val requestFingerprint: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

interface CatalogCategoryRepository : JpaRepository<CatalogCategoryRecord, String> {
    fun findByActiveTrueOrderBySortOrderAsc(): List<CatalogCategoryRecord>
}

interface CommerceProductRepository : JpaRepository<ProductRecord, String> {
    fun findByStatusOrderBySortOrderAscCreatedAtDesc(status: CatalogProductStatus): List<ProductRecord>
    fun findByIdAndStatus(id: String, status: CatalogProductStatus): ProductRecord?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductRecord p where p.id = :id")
    fun lockForCustomization(id: String): ProductRecord?

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from ProductRecord p where p.id = :id and p.status = dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus.ACTIVE")
    fun findActiveForBargaining(id: String): ProductRecord?

    @Query(
        """
        select related
        from ProductRecord related, ProductRecord target
        where target.id = :targetId
          and related.id <> target.id
          and related.status = dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus.ACTIVE
          and related.category is not null
          and related.category = target.category
        order by
          case
              when target.subcategory is not null and related.subcategory = target.subcategory then 0
              else 1
          end,
          case
              when target.basePriceCfa is not null and related.basePriceCfa is not null
                  then abs(related.basePriceCfa - target.basePriceCfa)
              else 2147483647
          end,
          related.createdAt desc
        """
    )
    fun findRelatedActiveProductsByCategory(targetId: String, pageable: Pageable): List<ProductRecord>
}

interface CommerceMerchantRepository : JpaRepository<MerchantRecord, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MerchantRecord m where m.id = :merchantId")
    fun lockForCatalogCreate(merchantId: String): MerchantRecord?
}

interface MerchantStorefrontRepository : JpaRepository<MerchantStorefrontRecord, String> {
    fun findByActiveTrueOrderBySortOrderAsc(): List<MerchantStorefrontRecord>
    fun findByMerchantIdIn(merchantIds: Collection<String>): List<MerchantStorefrontRecord>
}

interface CatalogPromotionRepository : JpaRepository<CatalogPromotionRecord, String> {
    fun findByActiveTrueOrderBySortOrderAsc(): List<CatalogPromotionRecord>
}

interface CustomerCartItemRepository : JpaRepository<CustomerCartItemRecord, String> {
    fun findByUserIdOrderByUpdatedAtDesc(userId: String): List<CustomerCartItemRecord>
    fun findByIdAndUserId(id: String, userId: String): CustomerCartItemRecord?
    fun findByUserIdAndProductIdAndConfigurationKey(
        userId: String, productId: String, configurationKey: String,
    ): CustomerCartItemRecord?
    fun deleteByUserId(userId: String): Long
}

interface BargainingThreadRepository : JpaRepository<BargainingThreadRecord, String> {
    fun findByMerchantId(merchantId: String, pageable: Pageable): org.springframework.data.domain.Page<BargainingThreadRecord>
    fun findByCustomerIdOrderByOpenedAtDesc(customerId: String): List<BargainingThreadRecord>
    fun findByIdAndCustomerId(id: String, customerId: String): BargainingThreadRecord?
    fun findByMerchantIdOrderByOpenedAtDesc(merchantId: String): List<BargainingThreadRecord>
    fun findByCustomerIdAndMerchantIdAndProductIdOrderByOpenedAtDesc(customerId: String, merchantId: String, productId: String): List<BargainingThreadRecord>
    @Query("select t.customerId from BargainingThreadRecord t where t.id = :id")
    fun customerReference(id: String): String?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BargainingThreadRecord t where t.id = :id")
    fun lockForAction(id: String): BargainingThreadRecord?
}

interface BargainingOfferRepository : JpaRepository<BargainingOfferRecord, String> {
    fun findByThreadIdOrderByCreatedAtAsc(threadId: String): List<BargainingOfferRecord>
    fun findByActorUserIdAndIdempotencyKey(actorUserId: String, idempotencyKey: String): BargainingOfferRecord?
    fun findByThreadIdInOrderByCreatedAtAsc(threadIds: Collection<String>): List<BargainingOfferRecord>
}

data class CommerceHomeDto(
    val categories: List<CatalogCategoryDto>,
    val merchants: List<MerchantStorefrontDto>,
    val products: List<CatalogProductDto>,
    val promotions: List<CatalogPromotionDto>,
)

data class CatalogCategoryDto(val key: String, val title: String, val supportLabel: String, val accentHex: String)

data class MerchantStorefrontDto(
    val id: String,
    val name: String,
    val status: String,
    val area: String?,
    val kind: String?,
    val distanceKm: Double?,
    val eta: String?,
    val photoStatus: String?,
    val openStatus: String?,
    val rating: String?,
    val consolidation: String?,
    val products: List<CatalogProductDto> = emptyList(),
)

data class CatalogProductDto(
    val id: String,
    val merchantId: String?,
    val name: String,
    val kind: CatalogProductKind,
    val status: CatalogProductStatus,
    val category: String?,
    val subcategory: String?,
    val detail: String?,
    val priceCfa: Int?,
    val optionHint: String?,
    val originalPriceCfa: Int?,
    val hasDiscount: Boolean,
    val bargainingEnabled: Boolean,
    val bargainFloorCfa: Int?,
    val cameraVerified: Boolean,
    val capturedAtLabel: String?,
    val photoRequired: Boolean = false,
    val imageUrl: String? = null,
    val customizations: List<ProductCustomizationGroupDto> = emptyList(),
)

data class CatalogPromotionDto(
    val id: String,
    val headline: String,
    val title: String,
    val subtitle: String,
    val productId: String,
)

data class CommerceCartDto(val items: List<CommerceCartItemDto>, val totalItems: Int, val subtotalCfa: Int)
data class CommerceCartItemDto(
    val id: String,
    val product: CatalogProductDto,
    val quantity: Int,
    val lineTotalCfa: Int?,
    val selections: List<FoodCustomizationSelection>,
    val productTotalCfa: Int?,
    val optionTotalCfa: Int?,
    val available: Boolean,
    val bargainingThreadId: String? = null,
    val negotiatedUnitPriceCfa: Int? = null,
    val negotiatedPriceExpiresAt: Instant? = null,
    val unavailableReason: String? = null,
)
data class UpsertCartItemRequest(
    val productId: String,
    val quantity: Int,
    val selections: List<FoodCustomizationSelection>? = null,
    val bargainingThreadId: String? = null,
)

data class ManageCatalogProductRequest(
    val merchantId: String? = null,
    val name: String,
    val kind: CatalogProductKind? = null,
    val status: CatalogProductStatus? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val detail: String? = null,
    val basePriceCfa: Int? = null,
    val optionHint: String? = null,
    val originalPriceCfa: Int? = null,
    val bargainingEnabled: Boolean? = null,
    val bargainFloorCfa: Int? = null,
    val cameraVerified: Boolean? = null,
    val capturedAtLabel: String? = null,
    val sortOrder: Int? = null,
    val idempotencyKey: String? = null,
)

data class PatchCatalogProductRequest(
    val name: String? = null,
    val status: CatalogProductStatus? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val detail: String? = null,
    val basePriceCfa: Int? = null,
    val optionHint: String? = null,
    val originalPriceCfa: Int? = null,
    val bargainingEnabled: Boolean? = null,
    val bargainFloorCfa: Int? = null,
    val cameraVerified: Boolean? = null,
    val capturedAtLabel: String? = null,
    val sortOrder: Int? = null,
)

data class MerchantProductPhotoRequest(
    val merchantId: String? = null,
    val source: ProductMediaSource,
    val capturedAt: Instant? = null,
    val deviceId: String? = null,
    val contentHash: String? = null,
    val catalogImageId: String? = null,
    val sourceUrl: String? = null,
)

data class MerchantProductPhotoStatusDto(
    val productId: String,
    val productKind: CatalogProductKind,
    val source: ProductMediaSource,
    val cameraVerified: Boolean,
    val moderationStatus: ProductMediaModerationStatus,
    val capturedAt: Instant?,
    val catalogImageId: String?,
    val publicUrl: String?,
    val storageReady: Boolean,
    val uploadRequired: Boolean,
    val status: String,
)

data class CreateBargainingThreadRequest(val productId: String, val amountCfa: Int, val message: String? = null, val idempotencyKey: String? = null)
data class CreateBargainingOfferRequest(
    val amountCfa: Int? = null, val message: String? = null, val offerType: BargainingOfferType? = null,
    val expectedOfferId: String? = null, val idempotencyKey: String? = null,
)
data class BargainingThreadDto(
    val id: String,
    val customerId: String,
    val merchantId: String,
    val productId: String,
    val status: BargainingThreadStatus,
    val lastOfferCfa: Int?,
    val acceptedPriceCfa: Int?,
    val openedAt: Instant,
    val offers: List<BargainingOfferDto>,
    val productName: String? = null,
    val catalogPriceCfa: Int? = null,
    val bargainingEnabled: Boolean = false,
    val customerAttemptsUsed: Int = 0,
    val merchantAttemptsUsed: Int = 0,
    val maxCustomerAttempts: Int = 3,
    val maxMerchantAttempts: Int = 3,
    val acceptedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val nextActor: BargainingActorType? = null,
)
data class BargainingOfferDto(
    val id: String,
    val actorUserId: String,
    val actorType: BargainingActorType,
    val offerType: BargainingOfferType,
    val amountCfa: Int?,
    val message: String?,
    val createdAt: Instant,
)

@Service
class CommerceService(
    private val categories: CatalogCategoryRepository,
    private val products: CommerceProductRepository,
    private val merchants: CommerceMerchantRepository,
    private val storefronts: MerchantStorefrontRepository,
    private val promotions: CatalogPromotionRepository,
    private val cartItems: CustomerCartItemRepository,
    private val users: UserRepository,
    private val bargainingThreads: BargainingThreadRepository,
    private val bargainingOffers: BargainingOfferRepository,
    private val bargaining: PersistedBargainingService,
    private val cameraMedia: CatalogCameraMedia,
    private val platformCommerce: PlatformCommerceService,
    private val merchantStores: dev.orestegabo.sequo_api.domain.store.MerchantStoreService,
    @Value("\${sequo.catalog.approved-generic-image-url:}") private val approvedGenericImageUrl: String,
) {
    @Transactional(readOnly = true)
    fun home(category: String? = null, subcategory: String? = null): CommerceHomeDto {
        val activeProducts = products.findByStatusOrderBySortOrderAscCreatedAtDesc(CatalogProductStatus.ACTIVE)
            .filter { category == null || it.category == category }
            .filter { subcategory == null || it.subcategory == subcategory }
        val catalogProducts = catalogDtos(activeProducts, showConfiguredBargaining = false)
        val productsByMerchant = catalogProducts.groupBy { it.merchantId }
        val merchantIds = activeProducts.mapNotNull { it.merchantId }.distinct()
        val storefrontDtos = if (merchantIds.isEmpty()) {
            emptyList()
        } else {
            val merchantsById = merchants.findAllById(merchantIds).associateBy { it.id }
            val storeOpenStatuses = merchantStores.publicOpenStatuses(merchantIds)
            storefronts.findByMerchantIdIn(merchantIds)
                .filter { it.active }
                .sortedBy { it.sortOrder }
                .map { storefront -> storefront.toDto(merchantsById[storefront.merchantId], productsByMerchant[storefront.merchantId].orEmpty()).let { dto ->
                    dto.copy(openStatus = storeOpenStatuses[storefront.merchantId] ?: dto.openStatus)
                } }
        }

        return CommerceHomeDto(
            categories = categories.findByActiveTrueOrderBySortOrderAsc().map { it.toDto() },
            merchants = storefrontDtos,
            products = catalogProducts,
            promotions = promotions.findByActiveTrueOrderBySortOrderAsc()
                .filter { promo -> activeProducts.any { it.id == promo.productId } }
                .map { it.toDto() },
        )
    }

    @Transactional(readOnly = true)
    fun listMerchants(category: String?, subcategory: String?, area: String?, sort: String?): List<MerchantStorefrontDto> {
        val sortMode = sort?.lowercase()
        val filtered = home(category, subcategory).merchants.filter { merchant ->
            area == null || merchant.area?.contains(area, ignoreCase = true) == true || merchant.name.contains(area, ignoreCase = true)
        }
        return when (sortMode) {
            "fastest" -> filtered.sortedBy { it.eta?.filter(Char::isDigit)?.toIntOrNull() ?: Int.MAX_VALUE }
            "rating" -> filtered.sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }
            "delivery" -> filtered.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            else -> filtered.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
        }
    }

    @Transactional(readOnly = true)
    fun listProducts(category: String?, subcategory: String?, merchantId: String?, includeArchived: Boolean): List<CatalogProductDto> {
        val allowedStatuses = if (includeArchived) {
            setOf(CatalogProductStatus.ACTIVE, CatalogProductStatus.INACTIVE, CatalogProductStatus.ARCHIVED)
        } else {
            setOf(CatalogProductStatus.ACTIVE)
        }
        val visible = products.findAll()
            .filter { it.status in allowedStatuses }
            .filter { category == null || it.category == category }
            .filter { subcategory == null || it.subcategory == subcategory }
            .filter { merchantId == null || it.merchantId == merchantId }
            .sortedWith(compareBy<ProductRecord> { it.sortOrder }.thenByDescending { it.createdAt })
        return catalogDtos(visible, showConfiguredBargaining = includeArchived)
    }

    @Transactional(readOnly = true)
    fun relatedProducts(productId: String, limit: Int): List<CatalogProductDto> {
        val safeProductId = ApiInputPolicy.requiredIdentifier(productId, "productId")
        require(limit in 1..MAX_RELATED_PRODUCTS_LIMIT) {
            "limit must be between 1 and $MAX_RELATED_PRODUCTS_LIMIT."
        }
        return catalogDtos(products.findRelatedActiveProductsByCategory(safeProductId, PageRequest.of(0, limit)), false)
    }

    private fun catalogDtos(records: List<ProductRecord>, showConfiguredBargaining: Boolean): List<CatalogProductDto> {
        val menus = platformCommerce.customizationsByProduct(records.map { it.id })
        return records.map { record ->
            val groups = menus[record.id].orEmpty()
            record.toDto(showConfiguredBargaining).copy(
                customizations = if (showConfiguredBargaining) groups else groups.filter { it.active }
            )
        }
    }

    @Transactional
    fun createProduct(request: ManageCatalogProductRequest): CatalogProductDto =
        saveNewProduct(
            request,
            UUID.randomUUID().toString(),
            photoRequired = !request.merchantId.isNullOrBlank() &&
                (request.kind ?: CatalogProductKind.SellerSpecific) == CatalogProductKind.SellerSpecific,
        )

    private fun saveNewProduct(
        request: ManageCatalogProductRequest,
        productId: String,
        photoRequired: Boolean = false,
    ): CatalogProductDto {
        val merchantId = ApiInputPolicy.optionalIdentifier(request.merchantId, "merchantId")
        merchantId?.let {
            require(merchants.existsById(it)) { "merchantId does not reference an existing merchant." }
        }
        val price = request.basePriceCfa
        require(price == null || price >= 0) { "basePriceCfa must be non-negative." }
        request.originalPriceCfa?.let { require(it >= 0) { "originalPriceCfa must be non-negative." } }
        request.bargainFloorCfa?.let { require(it >= 0) { "bargainFloorCfa must be non-negative." } }

        val now = Instant.now()
        val candidate = ProductRecord(
            id = productId,
            merchantId = merchantId,
            name = ApiInputPolicy.requiredShortText(request.name, "name", 180),
            kind = request.kind ?: CatalogProductKind.SellerSpecific,
            status = when {
                photoRequired && (request.status == null || request.status == CatalogProductStatus.ACTIVE) -> CatalogProductStatus.INACTIVE
                else -> request.status ?: CatalogProductStatus.ACTIVE
            },
            photoRequired = photoRequired,
            category = ApiInputPolicy.optionalIdentifier(request.category, "category"),
            subcategory = ApiInputPolicy.optionalShortText(request.subcategory, "subcategory", 128),
            detail = ApiInputPolicy.optionalLongText(request.detail, "detail", 500),
            basePriceCfa = price,
            optionHint = ApiInputPolicy.optionalShortText(request.optionHint, "optionHint", 240),
            originalPriceCfa = request.originalPriceCfa,
            bargainingEnabled = request.bargainingEnabled ?: false,
            bargainFloorCfa = request.bargainFloorCfa,
            cameraVerified = request.cameraVerified ?: false,
            capturedAtLabel = ApiInputPolicy.optionalShortText(request.capturedAtLabel, "capturedAtLabel", 120),
            sortOrder = request.sortOrder ?: 0,
            createdAt = now,
            updatedAt = now,
        )
        val existing = products.findById(productId).orElse(null)
        if (existing != null) {
            if (existing.toDto() != candidate.toDto() || existing.sortOrder != candidate.sortOrder) {
                throw MerchantCatalogCreateConflict()
            }
            return existing.toDto()
        }
        return products.save(candidate).toDto()
    }

    @Transactional
    fun updateProduct(productId: String, request: PatchCatalogProductRequest): CatalogProductDto {
        val safeProductId = ApiInputPolicy.requiredIdentifier(productId, "productId")
        val product = products.findById(safeProductId).orElseThrow {
            IllegalArgumentException("productId does not reference an existing product.")
        }
        request.name?.let { product.name = ApiInputPolicy.requiredShortText(it, "name", 180) }
        request.status?.let {
            require(it != CatalogProductStatus.ACTIVE || !product.photoRequired || product.imageUrl != null) {
                "Capture and upload a camera photo before publishing this product."
            }
            product.status = it
        }
        request.category?.let { product.category = ApiInputPolicy.optionalIdentifier(it, "category") }
        request.subcategory?.let { product.subcategory = ApiInputPolicy.optionalShortText(it, "subcategory", 128) }
        request.detail?.let { product.detail = ApiInputPolicy.optionalLongText(it, "detail", 500) }
        request.basePriceCfa?.let {
            require(it >= 0) { "basePriceCfa must be non-negative." }
            product.basePriceCfa = it
        }
        request.optionHint?.let { product.optionHint = ApiInputPolicy.optionalShortText(it, "optionHint", 240) }
        request.originalPriceCfa?.let {
            require(it >= 0) { "originalPriceCfa must be non-negative." }
            product.originalPriceCfa = it
        }
        request.bargainingEnabled?.let { product.bargainingEnabled = it }
        request.bargainFloorCfa?.let {
            require(it >= 0) { "bargainFloorCfa must be non-negative." }
            product.bargainFloorCfa = it
        }
        request.cameraVerified?.let { product.cameraVerified = it }
        request.capturedAtLabel?.let { product.capturedAtLabel = ApiInputPolicy.optionalShortText(it, "capturedAtLabel", 120) }
        request.sortOrder?.let { product.sortOrder = it }
        product.updatedAt = Instant.now()
        return catalogDtos(listOf(product), true).single()
    }

    @Transactional
    fun archiveProduct(productId: String): CatalogProductDto =
        updateProduct(productId, PatchCatalogProductRequest(status = CatalogProductStatus.ARCHIVED))

    @Transactional
    fun createMerchantProduct(merchantId: String, request: ManageCatalogProductRequest): CatalogProductDto {
        val safeMerchantId = ApiInputPolicy.requiredIdentifier(merchantId, "merchantId")
        require(request.merchantId == null || request.merchantId == safeMerchantId) {
            "merchantId must match the authenticated merchant scope."
        }
        val scopedRequest = request.copy(merchantId = safeMerchantId, cameraVerified = null, capturedAtLabel = null)
        val photoRequired = (request.kind ?: CatalogProductKind.SellerSpecific) == CatalogProductKind.SellerSpecific
        val key = request.idempotencyKey?.let { ApiInputPolicy.requiredIdempotencyKey(it) }
            ?: return saveNewProduct(scopedRequest, UUID.randomUUID().toString(), photoRequired)
        requireNotNull(merchants.lockForCatalogCreate(safeMerchantId)) { "merchantId does not reference an existing merchant." }
        val productId = UUID.nameUUIDFromBytes("merchant-catalog:$safeMerchantId\u0000$key".toByteArray()).toString()
        return saveNewProduct(scopedRequest, productId, photoRequired)
    }

    @Transactional
    fun updateMerchantProduct(merchantId: String, productId: String, request: PatchCatalogProductRequest): CatalogProductDto {
        requireMerchantProductOwnership(merchantId, productId)
        return updateProduct(productId, request.copy(cameraVerified = null, capturedAtLabel = null))
    }

    @Transactional
    fun deactivateMerchantProduct(merchantId: String, productId: String): CatalogProductDto =
        updateMerchantProduct(merchantId, productId, PatchCatalogProductRequest(status = CatalogProductStatus.INACTIVE))

    @Transactional
    fun activateMerchantProduct(merchantId: String, productId: String): CatalogProductDto =
        updateMerchantProduct(merchantId, productId, PatchCatalogProductRequest(status = CatalogProductStatus.ACTIVE))

    @Transactional
    fun prepareMerchantProductPhoto(
        merchantId: String,
        productId: String,
        request: MerchantProductPhotoRequest,
    ): MerchantProductPhotoStatusDto {
        val product = merchantProduct(merchantId, productId)
        request.merchantId?.let {
            require(ApiInputPolicy.requiredIdentifier(it, "merchantId") == product.merchantId) {
                "merchantId must match the authenticated merchant scope."
            }
        }

        val now = Instant.now()
        return when (request.source) {
            ProductMediaSource.LiveCamera -> {
                require(product.kind == CatalogProductKind.SellerSpecific) {
                    "Live camera photos are required only for seller-specific products."
                }
                val capturedAt = request.capturedAt ?: throw IllegalArgumentException("capturedAt is required.")
                require(!capturedAt.isAfter(now.plusSeconds(60))) {
                    "capturedAt cannot be in the future."
                }
                ApiInputPolicy.requiredShortText(request.deviceId ?: "", "deviceId", 128)
                val contentHash = ApiInputPolicy.requiredShortText(request.contentHash ?: "", "contentHash", 128)
                require(contentHash.startsWith("sha256:")) {
                    "contentHash must be a sha256 hash."
                }
                MerchantProductPhotoStatusDto(
                    productId = product.id,
                    productKind = product.kind,
                    source = ProductMediaSource.LiveCamera,
                    cameraVerified = false,
                    moderationStatus = ProductMediaModerationStatus.NeedsReview,
                    capturedAt = capturedAt,
                    catalogImageId = null,
                    publicUrl = null,
                    storageReady = false,
                    uploadRequired = true,
                    status = "PENDING_UPLOAD_STORAGE",
                )
            }

            ProductMediaSource.GenericCatalogReference -> {
                require(product.kind == CatalogProductKind.GenericSealedItem) {
                    "Generic catalog references are allowed only for generic sealed products."
                }
                val catalogImageId = ApiInputPolicy.requiredIdentifier(request.catalogImageId ?: "", "catalogImageId")
                val sourceUrl = approvedGenericImageUrl.trim()
                val uri = runCatching { URI.create(sourceUrl) }.getOrNull()
                require(catalogImageId == "standard" && sourceUrl.length <= 500 &&
                    uri?.scheme == "https" && uri.host != null && uri.userInfo == null) {
                    "No approved generic image matches this catalog reference."
                }
                require(request.sourceUrl == null || request.sourceUrl.trim() == sourceUrl) {
                    "The generic image URL is managed by SEQUO."
                }
                product.imageUrl = sourceUrl
                product.cameraVerified = false
                product.capturedAtLabel = "Approved generic catalog image"
                product.updatedAt = now
                MerchantProductPhotoStatusDto(
                    productId = product.id,
                    productKind = product.kind,
                    source = ProductMediaSource.GenericCatalogReference,
                    cameraVerified = product.cameraVerified,
                    moderationStatus = ProductMediaModerationStatus.Approved,
                    capturedAt = null,
                    catalogImageId = catalogImageId,
                    publicUrl = sourceUrl,
                    storageReady = true,
                    uploadRequired = false,
                    status = "GENERIC_REFERENCE_APPROVED",
                )
            }

            ProductMediaSource.GalleryUpload,
            ProductMediaSource.WebImage -> throw IllegalArgumentException("${request.source.name} is not accepted for merchant product photos.")
        }
    }

    @Transactional
    fun uploadMerchantCameraPhoto(
        merchantId: String,
        productId: String,
        file: MultipartFile,
        capturedAt: Instant,
        deviceId: String,
    ): CatalogProductDto {
        val product = merchantProduct(merchantId, productId)
        require(product.kind == CatalogProductKind.SellerSpecific) { "This product uses generic catalog images." }
        require(file.size in 1..(8L * 1024 * 1024)) { "Camera photos must be at most 8 MiB." }
        require(!capturedAt.isBefore(Instant.now().minusSeconds(900))) { "Capture a new photo before uploading." }
        val media = cameraMedia.upload(
            product, file.originalFilename.orEmpty(), file.contentType.orEmpty(), file.bytes, capturedAt,
            ApiInputPolicy.requiredIdentifier(deviceId, "deviceId"),
        )
        product.imageUrl = requireNotNull(media.publicUrl)
        product.cameraVerified = true
        product.capturedAtLabel = capturedAt.toString()
        product.updatedAt = Instant.now()
        return catalogDtos(listOf(product), true).single()
    }

    @Transactional(readOnly = true)
    fun cameraPhoto(productId: String): ByteArray? {
        val id = ApiInputPolicy.requiredIdentifier(productId, "productId")
        val product = products.findByIdAndStatus(id, CatalogProductStatus.ACTIVE) ?: return null
        if (product.imageUrl != "/api/catalog/products/$id/image") return null
        return cameraMedia.read(id)
    }

    @Transactional(readOnly = true)
    fun requireMerchantProductOwnership(merchantId: String, productId: String) {
        merchantProduct(merchantId, productId)
    }

    private fun merchantProduct(merchantId: String, productId: String): ProductRecord {
        val safeMerchantId = ApiInputPolicy.requiredIdentifier(merchantId, "merchantId")
        val safeProductId = ApiInputPolicy.requiredIdentifier(productId, "productId")
        val product = products.findById(safeProductId).orElseThrow {
            IllegalArgumentException("productId does not reference an existing product.")
        }
        require(product.merchantId == safeMerchantId) {
            "productId does not belong to the authenticated merchant."
        }
        return product
    }

    private val cartSelectionsMapper = ObjectMapper()

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun cart(userId: String): CommerceCartDto {
        val records = cartItems.findByUserIdOrderByUpdatedAtDesc(userId)
        val menus = platformCommerce.customizationsByProduct(records.map { it.productId }.distinct())
        val items = records.mapNotNull { item ->
            val product = item.product ?: products.findById(item.productId).orElse(null) ?: return@mapNotNull null
            val selections = cartSelectionsMapper.readTree(item.selectionsJson).map { selection ->
                FoodCustomizationSelection(selection["groupId"].asText(), selection["optionIds"].map { it.asText() }.toSet())
            }
            var unavailableReason: String? = null
            val price = try {
                platformCommerce.quoteCustomizations(product.id, QuoteProductCustomizationsRequest(selections))
                    .takeIf { it.unitPriceCfa.toLong() * item.quantity <= Int.MAX_VALUE }
            } catch (error: IllegalArgumentException) {
                unavailableReason = error.message
                null
            }
            val negotiated = item.bargainingThreadId?.let { id ->
                try { bargaining.acceptedPrice(userId, product, id) }
                catch (error: IllegalArgumentException) { unavailableReason = error.message; null }
            }
            val available = price != null && (item.bargainingThreadId == null || negotiated != null)
            val basePrice = negotiated?.unitPriceCfa ?: price?.basePriceCfa
            val unitPrice = price?.let { requireNotNull(basePrice) + it.optionPriceCfa }
            CommerceCartItemDto(
                id = item.id,
                product = product.toDto(showConfiguredBargaining = false)
                    .copy(customizations = menus[product.id].orEmpty().filter { it.active }),
                quantity = item.quantity,
                lineTotalCfa = unitPrice?.times(item.quantity).takeIf { available },
                selections = selections,
                productTotalCfa = basePrice?.times(item.quantity).takeIf { available },
                optionTotalCfa = price?.optionPriceCfa?.times(item.quantity).takeIf { available },
                available = available,
                bargainingThreadId = item.bargainingThreadId,
                negotiatedUnitPriceCfa = unitPrice.takeIf { negotiated != null && available },
                negotiatedPriceExpiresAt = negotiated?.expiresAt,
                unavailableReason = unavailableReason ?: "Product or selected options are unavailable.".takeUnless { available },
            )
        }
        val subtotal = items.sumOf { (it.lineTotalCfa ?: 0).toLong() }
        require(subtotal <= Int.MAX_VALUE) { "Cart subtotal is too large." }
        return CommerceCartDto(items, items.sumOf { it.quantity }, subtotal.toInt())
    }

    @Transactional
    fun putCartItem(userId: String, request: UpsertCartItemRequest): CommerceCartDto {
        require(users.lockCheckoutOwner(userId)?.active == true) { "Customer account is unavailable." }
        val productId = ApiInputPolicy.requiredIdentifier(request.productId, "productId")
        require(request.quantity in 1..99) { "quantity must be between 1 and 99." }
        val product = requireNotNull(products.lockForCustomization(productId)) { "Product is unavailable." }
        request.bargainingThreadId?.let { bargaining.acceptedPrice(userId, product, it) }
        val selections = request.selections.orEmpty().sortedBy { it.groupId }
            .map { it.copy(optionIds = it.optionIds.toSortedSet()) }
        val price = platformCommerce.quoteCustomizations(productId, QuoteProductCustomizationsRequest(selections))
        require(price.unitPriceCfa.toLong() * request.quantity <= Int.MAX_VALUE) { "Cart line total is too large." }
        val json = cartSelectionsMapper.writeValueAsString(selections)
        val configurationIdentity = request.bargainingThreadId?.let { cartSelectionsMapper.writeValueAsString(listOf(selections, it)) } ?: json
        val configurationKey = if (selections.isEmpty() && request.bargainingThreadId == null) "" else
            MessageDigest.getInstance("SHA-256").digest(configurationIdentity.toByteArray()).toHexString()

        cartItems.findByUserIdAndProductIdAndConfigurationKey(userId, productId, configurationKey)?.also {
            it.quantity = request.quantity
            it.bargainingThreadId = request.bargainingThreadId
            it.updatedAt = Instant.now()
        } ?: cartItems.save(
            CustomerCartItemRecord(
                id = UUID.randomUUID().toString(),
                userId = userId,
                productId = productId,
                quantity = request.quantity,
                configurationKey = configurationKey,
                selectionsJson = json,
                bargainingThreadId = request.bargainingThreadId,
            )
        )
        return cart(userId)
    }

    @Transactional
    fun removeCartItem(userId: String, itemId: String): CommerceCartDto {
        require(users.lockCheckoutOwner(userId)?.active == true) { "Customer account is unavailable." }
        val safeItemId = ApiInputPolicy.requiredIdentifier(itemId, "itemId")
        cartItems.findByIdAndUserId(safeItemId, userId)?.let(cartItems::delete)
        return cart(userId)
    }

    @Transactional
    fun clearCart(userId: String): CommerceCartDto {
        require(users.lockCheckoutOwner(userId)?.active == true) { "Customer account is unavailable." }
        cartItems.deleteByUserId(userId)
        return cart(userId)
    }

    @Transactional
    fun createBargainingThread(userId: String, request: CreateBargainingThreadRequest): BargainingThreadDto =
        bargaining.create(userId, request)

    @Transactional(readOnly = true)
    fun bargainingThreads(userId: String): List<BargainingThreadDto> = bargaining.customerThreads(userId)

    @Transactional
    fun addCustomerBargainingOffer(userId: String, threadId: String, request: CreateBargainingOfferRequest): BargainingThreadDto =
        bargaining.customerOffer(userId, threadId, request)

    private fun requireBargainingProduct(productId: String): ProductRecord {
        val product = products.findActiveForBargaining(productId)
            ?: throw IllegalArgumentException("productId does not reference an active product.")
        require(product.acceptsBargaining()) { "This product does not accept bargaining." }
        return product
    }
}

private const val MAX_RELATED_PRODUCTS_LIMIT = 6

@RestController
@RequestMapping("/api/catalog")
class CatalogController(private val commerce: CommerceService) {
    @GetMapping("/home")
    fun home(@RequestParam(required = false) category: String?, @RequestParam(required = false) subcategory: String?): ResponseEntity<Any> = ok {
        commerce.home(
            ApiInputPolicy.optionalIdentifier(category, "category"),
            ApiInputPolicy.optionalShortText(subcategory, "subcategory"),
        )
    }

    @GetMapping("/categories")
    fun categories(): ResponseEntity<Any> = ok { commerce.home().categories }

    @GetMapping("/merchants")
    fun merchants(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) subcategory: String?,
        @RequestParam(required = false) area: String?,
        @RequestParam(required = false) sort: String?,
    ): ResponseEntity<Any> = ok {
        commerce.listMerchants(
            ApiInputPolicy.optionalIdentifier(category, "category"),
            ApiInputPolicy.optionalShortText(subcategory, "subcategory"),
            ApiInputPolicy.optionalShortText(area, "area"),
            ApiInputPolicy.optionalShortText(sort, "sort"),
        )
    }

    @GetMapping("/products")
    fun products(
        authentication: Authentication?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) subcategory: String?,
        @RequestParam(required = false) merchantId: String?,
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
    ): ResponseEntity<Any> = ok {
        val scope = ApiInputPolicy.optionalIdentifier(merchantId, "merchantId")
        val canReadInactive = authentication != null && authentication.isLoggedIn() && (
            authentication.hasAnyRole(RoleGroups.AdminOnly) ||
                (scope != null && authentication.hasAnyRole(merchantCatalogRoles) && scope in authentication.merchantScopeIds())
        )
        commerce.listProducts(
            ApiInputPolicy.optionalIdentifier(category, "category"),
            ApiInputPolicy.optionalShortText(subcategory, "subcategory"),
            scope,
            includeArchived && canReadInactive,
        )
    }

    @GetMapping("/products/{productId}/image", produces = [MediaType.IMAGE_JPEG_VALUE])
    fun productImage(@PathVariable productId: String): ResponseEntity<ByteArray> {
        val bytes = commerce.cameraPhoto(productId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff").contentType(MediaType.IMAGE_JPEG).body(bytes)
    }

    @GetMapping("/products/{productId}/related")
    fun relatedProducts(
        @PathVariable productId: String,
        @RequestParam(defaultValue = "6") limit: Int,
    ): ResponseEntity<Any> = ok { commerce.relatedProducts(productId, limit) }

    @PostMapping("/products")
    fun createProduct(authentication: Authentication?, @RequestBody request: ManageCatalogProductRequest): ResponseEntity<Any> =
        adminCatalogAccess(authentication) { commerce.createProduct(request) }

    @PatchMapping("/products/{productId}")
    fun updateProduct(authentication: Authentication?, @PathVariable productId: String, @RequestBody request: PatchCatalogProductRequest): ResponseEntity<Any> =
        adminCatalogAccess(authentication) { commerce.updateProduct(productId, request) }

    @PostMapping("/products/{productId}/archive")
    fun archiveProduct(authentication: Authentication?, @PathVariable productId: String): ResponseEntity<Any> =
        adminCatalogAccess(authentication) { commerce.archiveProduct(productId) }
}

private class MerchantCatalogCreateConflict : RuntimeException()

@RestController
@RequestMapping("/api/merchant/catalog")
class MerchantCatalogController(private val commerce: CommerceService, private val platformCommerce: PlatformCommerceService) {
    @ExceptionHandler(MerchantCatalogCreateConflict::class)
    fun createConflict(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse("catalog_create_conflict", "This catalog create was already used for a different product state.")
        )

    @PostMapping("/products")
    fun createProduct(authentication: Authentication?, @RequestBody request: ManageCatalogProductRequest): ResponseEntity<Any> =
        merchantCatalogAccess(authentication, request.merchantId) { merchantId ->
            commerce.createMerchantProduct(merchantId, request)
        }

    @PatchMapping("/products/{productId}")
    fun updateProduct(
        authentication: Authentication?,
        @PathVariable productId: String,
        @RequestBody request: PatchCatalogProductRequest,
        @RequestParam(required = false) merchantId: String?,
    ): ResponseEntity<Any> =
        merchantCatalogAccess(authentication, merchantId) { merchantId ->
            commerce.updateMerchantProduct(merchantId, productId, request)
        }

    @PostMapping("/products/{productId}/deactivate")
    fun deactivateProduct(
        authentication: Authentication?,
        @PathVariable productId: String,
        @RequestParam(required = false) merchantId: String?,
    ): ResponseEntity<Any> =
        merchantCatalogAccess(authentication, merchantId) { merchantId ->
            commerce.deactivateMerchantProduct(merchantId, productId)
        }

    @PostMapping("/products/{productId}/activate")
    fun activateProduct(
        authentication: Authentication?,
        @PathVariable productId: String,
        @RequestParam(required = false) merchantId: String?,
    ): ResponseEntity<Any> =
        merchantCatalogAccess(authentication, merchantId) { merchantId ->
            commerce.activateMerchantProduct(merchantId, productId)
        }

    @PutMapping("/products/{productId}/customizations")
    fun replaceCustomizations(
        authentication: Authentication?,
        @PathVariable productId: String,
        @RequestParam merchantId: String,
        @RequestBody request: ReplaceProductCustomizationsRequest,
    ): ResponseEntity<Any> = merchantCatalogAccess(authentication, merchantId) {
        platformCommerce.replaceCustomizations(productId, request, it)
    }

    @PostMapping("/products/{productId}/photos/camera", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadCameraPhoto(
        authentication: Authentication?,
        @PathVariable productId: String,
        @RequestParam merchantId: String,
        @RequestParam file: MultipartFile,
        @RequestParam capturedAt: Instant,
        @RequestParam deviceId: String,
    ): ResponseEntity<Any> = merchantCatalogAccess(authentication, merchantId) {
        commerce.uploadMerchantCameraPhoto(it, productId, file, capturedAt, deviceId)
    }

    @PostMapping("/products/{productId}/photos")
    fun prepareProductPhoto(
        authentication: Authentication?,
        @PathVariable productId: String,
        @RequestBody request: MerchantProductPhotoRequest,
    ): ResponseEntity<Any> =
        merchantCatalogAccess(authentication, request.merchantId) { merchantId ->
            commerce.prepareMerchantProductPhoto(merchantId, productId, request)
        }
}

@RestController
@RequestMapping("/api/cart")
class CommerceCartController(private val commerce: CommerceService) {
    @GetMapping
    fun cart(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> =
        authenticated(userId) { commerce.cart(it) }

    @PutMapping("/items")
    fun putItem(@AuthenticationPrincipal userId: String?, @RequestBody request: UpsertCartItemRequest): ResponseEntity<Any> =
        authenticated(userId) { commerce.putCartItem(it, request) }

    @DeleteMapping("/items/{itemId}")
    fun removeItem(@AuthenticationPrincipal userId: String?, @PathVariable itemId: String): ResponseEntity<Any> =
        authenticated(userId) { commerce.removeCartItem(it, itemId) }

    @DeleteMapping
    fun clear(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> =
        authenticated(userId) { commerce.clearCart(it) }
}

@RestController
@RequestMapping("/api/bargaining")
class BargainingController(private val bargaining: PersistedBargainingService) {
    @GetMapping("/threads")
    fun threads(authentication: Authentication?, @RequestParam(required = false) merchantId: String?): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build() else ok { bargaining.list(authentication, merchantId) }

    @GetMapping("/threads/{threadId}")
    fun thread(authentication: Authentication?, @PathVariable threadId: String): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build() else ok { bargaining.get(authentication, threadId) }

    @PostMapping("/threads")
    fun createThread(authentication: Authentication?, @RequestBody request: CreateBargainingThreadRequest): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (!authentication.hasAnyRole(setOf(RoleCode.CUSTOMER))) ResponseEntity.status(403).build()
        else ok { bargaining.create(authentication.name, request) }

    @PostMapping("/threads/{threadId}/offers")
    fun addOffer(authentication: Authentication?, @PathVariable threadId: String,
        @RequestBody request: CreateBargainingOfferRequest): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build() else ok { bargaining.act(authentication, threadId, request) }
}

private val merchantCatalogRoles = setOf(RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF)

private fun adminCatalogAccess(authentication: Authentication?, block: () -> Any): ResponseEntity<Any> =
    if (!authentication.isLoggedIn()) ResponseEntity.status(401).build()
    else if (authentication?.hasAnyRole(RoleGroups.AdminOnly) != true) ResponseEntity.status(403).build()
    else ok(block)

private fun merchantCatalogAccess(
    authentication: Authentication?,
    requestedMerchantId: String?,
    block: (String) -> Any,
): ResponseEntity<Any> {
    if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) {
        return ResponseEntity.status(401).build()
    }
    if (!authentication.hasAnyRole(merchantCatalogRoles)) {
        return ResponseEntity.status(403).build()
    }
    val scopes = authentication.merchantScopeIds()
    val merchantId = requestedMerchantId?.let { ApiInputPolicy.requiredIdentifier(it, "merchantId") }
        ?: scopes.singleOrNull()
    if (merchantId == null || merchantId !in scopes) {
        return ResponseEntity.status(403).build()
    }
    return ok { block(merchantId) }
}

private fun ok(block: () -> Any): ResponseEntity<Any> = ResponseEntity.ok(block())

internal fun authenticated(userId: String?, block: (String) -> Any): ResponseEntity<Any> {
    if (userId == null) return ResponseEntity.status(401).build()
    return ok { block(userId) }
}

private fun Authentication?.isLoggedIn(): Boolean =
    this != null && isAuthenticated && this !is AnonymousAuthenticationToken

private fun CatalogCategoryRecord.toDto(): CatalogCategoryDto =
    CatalogCategoryDto(key, title, supportLabel, accentHex)

private fun MerchantStorefrontRecord.toDto(merchantRecord: MerchantRecord?, storeProducts: List<CatalogProductDto>): MerchantStorefrontDto =
    MerchantStorefrontDto(
        id = merchantId,
        name = merchantRecord?.name ?: merchantId,
        status = merchantRecord?.status ?: "UNKNOWN",
        area = area,
        kind = kind,
        distanceKm = distanceKm,
        eta = eta,
        photoStatus = photoStatus,
        openStatus = openStatus,
        rating = rating,
        consolidation = consolidation,
        products = storeProducts,
    )

private fun ProductRecord.acceptsBargaining(): Boolean =
    status == CatalogProductStatus.ACTIVE && bargainingEnabled && merchantId != null

private fun ProductRecord.toDto(showConfiguredBargaining: Boolean = true): CatalogProductDto =
    CatalogProductDto(
        id = id,
        merchantId = merchantId,
        name = name,
        kind = kind,
        status = status,
        category = category,
        subcategory = subcategory,
        detail = detail,
        priceCfa = basePriceCfa,
        optionHint = optionHint?.takeUnless { !showConfiguredBargaining && it.contains("historical minimum", ignoreCase = true) },
        originalPriceCfa = originalPriceCfa,
        hasDiscount = originalPriceCfa?.let { original -> basePriceCfa?.let { original > it } } ?: false,
        bargainingEnabled = if (showConfiguredBargaining) bargainingEnabled else acceptsBargaining(),
        bargainFloorCfa = bargainFloorCfa.takeIf { showConfiguredBargaining },
        cameraVerified = cameraVerified,
        capturedAtLabel = capturedAtLabel,
        photoRequired = photoRequired,
        imageUrl = imageUrl,
    )

private fun CatalogPromotionRecord.toDto(): CatalogPromotionDto =
    CatalogPromotionDto(id, headline, title, subtitle, productId)

private fun BargainingThreadRecord.toDto(offers: List<BargainingOfferRecord>): BargainingThreadDto =
    BargainingThreadDto(
        id = id,
        customerId = customerId,
        merchantId = merchantId,
        productId = productId,
        status = status,
        lastOfferCfa = lastOfferCfa,
        acceptedPriceCfa = acceptedPriceCfa,
        openedAt = openedAt,
        offers = offers.map { it.toDto() },
    )

private fun BargainingOfferRecord.toDto(): BargainingOfferDto =
    BargainingOfferDto(id, actorUserId, actorType, offerType, amountCfa, message, createdAt)
