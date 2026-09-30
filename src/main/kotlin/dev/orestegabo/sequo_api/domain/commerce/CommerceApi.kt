package dev.orestegabo.sequo_api.domain.commerce

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
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
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
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
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(nullable = false) var version: Long = 0,
)

enum class BargainingThreadStatus { OPEN, ACCEPTED, REJECTED, EXPIRED, CANCELLED }
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
    @Column(name = "expires_at") val expiresAt: Instant? = null,
    @Column(name = "last_offer_cfa") var lastOfferCfa: Int? = null,
    @Column(name = "accepted_price_cfa") var acceptedPriceCfa: Int? = null,
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "bargaining_offers")
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
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

interface CatalogCategoryRepository : JpaRepository<CatalogCategoryRecord, String> {
    fun findByActiveTrueOrderBySortOrderAsc(): List<CatalogCategoryRecord>
}

interface CommerceProductRepository : JpaRepository<ProductRecord, String> {
    fun findByStatusOrderBySortOrderAscCreatedAtDesc(status: CatalogProductStatus): List<ProductRecord>
    fun findByIdAndStatus(id: String, status: CatalogProductStatus): ProductRecord?

    @Query(
        """
        select related
        from ProductRecord related
        where related.id <> :targetId
          and related.status = dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus.ACTIVE
          and related.category is not null
          and related.category = (
              select target.category
              from ProductRecord target
              where target.id = :targetId
          )
        order by related.createdAt desc
        """
    )
    fun findRelatedActiveProductsByCategory(targetId: String, pageable: Pageable): List<ProductRecord>
}

interface CommerceMerchantRepository : JpaRepository<MerchantRecord, String>

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
    fun findByUserIdAndProductId(userId: String, productId: String): CustomerCartItemRecord?
    fun deleteByUserId(userId: String): Long
}

interface BargainingThreadRepository : JpaRepository<BargainingThreadRecord, String> {
    fun findByCustomerIdOrderByOpenedAtDesc(customerId: String): List<BargainingThreadRecord>
    fun findByIdAndCustomerId(id: String, customerId: String): BargainingThreadRecord?
}

interface BargainingOfferRepository : JpaRepository<BargainingOfferRecord, String> {
    fun findByThreadIdOrderByCreatedAtAsc(threadId: String): List<BargainingOfferRecord>
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
)

data class CatalogPromotionDto(
    val id: String,
    val headline: String,
    val title: String,
    val subtitle: String,
    val productId: String,
)

data class CommerceCartDto(val items: List<CommerceCartItemDto>, val totalItems: Int, val subtotalCfa: Int)
data class CommerceCartItemDto(val id: String, val product: CatalogProductDto, val quantity: Int, val lineTotalCfa: Int)
data class UpsertCartItemRequest(val productId: String, val quantity: Int)

data class ManageCatalogProductRequest(
    val merchantId: String? = null,
    val name: String,
    val kind: CatalogProductKind = CatalogProductKind.SellerSpecific,
    val status: CatalogProductStatus = CatalogProductStatus.ACTIVE,
    val category: String? = null,
    val subcategory: String? = null,
    val detail: String? = null,
    val basePriceCfa: Int? = null,
    val optionHint: String? = null,
    val originalPriceCfa: Int? = null,
    val bargainingEnabled: Boolean = false,
    val bargainFloorCfa: Int? = null,
    val cameraVerified: Boolean = false,
    val capturedAtLabel: String? = null,
    val sortOrder: Int = 0,
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

data class CreateBargainingThreadRequest(val productId: String, val amountCfa: Int, val message: String? = null)
data class CreateBargainingOfferRequest(val amountCfa: Int? = null, val message: String? = null)
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
    private val bargainingThreads: BargainingThreadRepository,
    private val bargainingOffers: BargainingOfferRepository,
) {
    @Transactional(readOnly = true)
    fun home(category: String? = null, subcategory: String? = null): CommerceHomeDto {
        val activeProducts = products.findByStatusOrderBySortOrderAscCreatedAtDesc(CatalogProductStatus.ACTIVE)
            .filter { category == null || it.category == category }
            .filter { subcategory == null || it.subcategory == subcategory }
        val productsByMerchant = activeProducts.groupBy { it.merchantId }
        val merchantIds = activeProducts.mapNotNull { it.merchantId }.distinct()
        val storefrontDtos = if (merchantIds.isEmpty()) {
            emptyList()
        } else {
            val merchantsById = merchants.findAllById(merchantIds).associateBy { it.id }
            storefronts.findByMerchantIdIn(merchantIds)
                .filter { it.active }
                .sortedBy { it.sortOrder }
                .map { storefront -> storefront.toDto(merchantsById[storefront.merchantId], productsByMerchant[storefront.merchantId].orEmpty()) }
        }

        return CommerceHomeDto(
            categories = categories.findByActiveTrueOrderBySortOrderAsc().map { it.toDto() },
            merchants = storefrontDtos,
            products = activeProducts.map { it.toDto() },
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
        return products.findAll()
            .filter { it.status in allowedStatuses }
            .filter { category == null || it.category == category }
            .filter { subcategory == null || it.subcategory == subcategory }
            .filter { merchantId == null || it.merchantId == merchantId }
            .sortedWith(compareBy<ProductRecord> { it.sortOrder }.thenByDescending { it.createdAt })
            .map { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun relatedProducts(productId: String, limit: Int): List<CatalogProductDto> {
        val safeProductId = ApiInputPolicy.requiredIdentifier(productId, "productId")
        require(limit in 1..MAX_RELATED_PRODUCTS_LIMIT) {
            "limit must be between 1 and $MAX_RELATED_PRODUCTS_LIMIT."
        }
        return products
            .findRelatedActiveProductsByCategory(safeProductId, PageRequest.of(0, limit))
            .map { it.toDto() }
    }

    @Transactional
    fun createProduct(request: ManageCatalogProductRequest): CatalogProductDto {
        val merchantId = ApiInputPolicy.optionalIdentifier(request.merchantId, "merchantId")
        merchantId?.let {
            require(merchants.existsById(it)) { "merchantId does not reference an existing merchant." }
        }
        val price = request.basePriceCfa
        require(price == null || price >= 0) { "basePriceCfa must be non-negative." }
        request.originalPriceCfa?.let { require(it >= 0) { "originalPriceCfa must be non-negative." } }
        request.bargainFloorCfa?.let { require(it >= 0) { "bargainFloorCfa must be non-negative." } }

        val now = Instant.now()
        return products.save(
            ProductRecord(
                id = UUID.randomUUID().toString(),
                merchantId = merchantId,
                name = ApiInputPolicy.requiredShortText(request.name, "name", 180),
                kind = request.kind,
                status = request.status,
                category = ApiInputPolicy.optionalIdentifier(request.category, "category"),
                subcategory = ApiInputPolicy.optionalShortText(request.subcategory, "subcategory", 128),
                detail = ApiInputPolicy.optionalLongText(request.detail, "detail", 500),
                basePriceCfa = price,
                optionHint = ApiInputPolicy.optionalShortText(request.optionHint, "optionHint", 240),
                originalPriceCfa = request.originalPriceCfa,
                bargainingEnabled = request.bargainingEnabled,
                bargainFloorCfa = request.bargainFloorCfa,
                cameraVerified = request.cameraVerified,
                capturedAtLabel = ApiInputPolicy.optionalShortText(request.capturedAtLabel, "capturedAtLabel", 120),
                sortOrder = request.sortOrder,
                createdAt = now,
                updatedAt = now,
            )
        ).toDto()
    }

    @Transactional
    fun updateProduct(productId: String, request: PatchCatalogProductRequest): CatalogProductDto {
        val safeProductId = ApiInputPolicy.requiredIdentifier(productId, "productId")
        val product = products.findById(safeProductId).orElseThrow {
            IllegalArgumentException("productId does not reference an existing product.")
        }
        request.name?.let { product.name = ApiInputPolicy.requiredShortText(it, "name", 180) }
        request.status?.let { product.status = it }
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
        return product.toDto()
    }

    @Transactional
    fun archiveProduct(productId: String): CatalogProductDto =
        updateProduct(productId, PatchCatalogProductRequest(status = CatalogProductStatus.ARCHIVED))

    @Transactional(readOnly = true)
    fun cart(userId: String): CommerceCartDto {
        val items = cartItems.findByUserIdOrderByUpdatedAtDesc(userId).mapNotNull { item ->
            val product = item.product ?: products.findById(item.productId).orElse(null) ?: return@mapNotNull null
            CommerceCartItemDto(
                id = item.id,
                product = product.toDto(),
                quantity = item.quantity,
                lineTotalCfa = (product.basePriceCfa ?: 0) * item.quantity,
            )
        }
        return CommerceCartDto(items, items.sumOf { it.quantity }, items.sumOf { it.lineTotalCfa })
    }

    @Transactional
    fun putCartItem(userId: String, request: UpsertCartItemRequest): CommerceCartDto {
        val productId = ApiInputPolicy.requiredIdentifier(request.productId, "productId")
        require(request.quantity in 1..99) { "quantity must be between 1 and 99." }
        products.findByIdAndStatus(productId, CatalogProductStatus.ACTIVE)
            ?: throw IllegalArgumentException("productId does not reference an active product.")

        cartItems.findByUserIdAndProductId(userId, productId)?.also {
            it.quantity = request.quantity
            it.updatedAt = Instant.now()
        } ?: cartItems.save(
            CustomerCartItemRecord(
                id = UUID.randomUUID().toString(),
                userId = userId,
                productId = productId,
                quantity = request.quantity,
            )
        )
        return cart(userId)
    }

    @Transactional
    fun removeCartItem(userId: String, itemId: String): CommerceCartDto {
        val safeItemId = ApiInputPolicy.requiredIdentifier(itemId, "itemId")
        cartItems.findByIdAndUserId(safeItemId, userId)?.let(cartItems::delete)
        return cart(userId)
    }

    @Transactional
    fun clearCart(userId: String): CommerceCartDto {
        cartItems.deleteByUserId(userId)
        return cart(userId)
    }

    @Transactional
    fun createBargainingThread(userId: String, request: CreateBargainingThreadRequest): BargainingThreadDto {
        val productId = ApiInputPolicy.requiredIdentifier(request.productId, "productId")
        require(request.amountCfa >= 0) { "amountCfa must be non-negative." }
        val product = products.findByIdAndStatus(productId, CatalogProductStatus.ACTIVE)
            ?: throw IllegalArgumentException("productId does not reference an active product.")
        require(product.bargainingEnabled) { "This product does not accept bargaining." }
        val merchantId = product.merchantId ?: throw IllegalArgumentException("Product has no merchant to bargain with.")
        product.bargainFloorCfa?.let { floor -> require(request.amountCfa >= floor) { "amountCfa is below the bargaining floor." } }

        val thread = bargainingThreads.save(
            BargainingThreadRecord(
                id = UUID.randomUUID().toString(),
                customerId = userId,
                merchantId = merchantId,
                productId = productId,
                lastOfferCfa = request.amountCfa,
            )
        )
        bargainingOffers.save(
            BargainingOfferRecord(
                id = UUID.randomUUID().toString(),
                threadId = thread.id,
                actorUserId = userId,
                actorType = BargainingActorType.CUSTOMER,
                offerType = BargainingOfferType.OFFER,
                amountCfa = request.amountCfa,
                message = ApiInputPolicy.optionalLongText(request.message, "message", 500),
            )
        )
        return thread.toDto(bargainingOffers.findByThreadIdOrderByCreatedAtAsc(thread.id))
    }

    @Transactional(readOnly = true)
    fun bargainingThreads(userId: String): List<BargainingThreadDto> =
        bargainingThreads.findByCustomerIdOrderByOpenedAtDesc(userId)
            .map { it.toDto(bargainingOffers.findByThreadIdOrderByCreatedAtAsc(it.id)) }

    @Transactional
    fun addCustomerBargainingOffer(userId: String, threadId: String, request: CreateBargainingOfferRequest): BargainingThreadDto {
        val safeThreadId = ApiInputPolicy.requiredIdentifier(threadId, "threadId")
        val thread = bargainingThreads.findByIdAndCustomerId(safeThreadId, userId)
            ?: throw IllegalArgumentException("threadId does not reference one of this customer's bargaining threads.")
        require(thread.status == BargainingThreadStatus.OPEN) { "Bargaining thread is not open." }
        request.amountCfa?.let { require(it >= 0) { "amountCfa must be non-negative." } }
        require(request.amountCfa != null || !request.message.isNullOrBlank()) { "amountCfa or message is required." }

        val offerType = if (request.amountCfa == null) BargainingOfferType.MESSAGE else BargainingOfferType.OFFER
        request.amountCfa?.let { thread.lastOfferCfa = it }
        bargainingOffers.save(
            BargainingOfferRecord(
                id = UUID.randomUUID().toString(),
                threadId = thread.id,
                actorUserId = userId,
                actorType = BargainingActorType.CUSTOMER,
                offerType = offerType,
                amountCfa = request.amountCfa,
                message = ApiInputPolicy.optionalLongText(request.message, "message", 500),
            )
        )
        return thread.toDto(bargainingOffers.findByThreadIdOrderByCreatedAtAsc(thread.id))
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
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) subcategory: String?,
        @RequestParam(required = false) merchantId: String?,
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
    ): ResponseEntity<Any> = ok {
        commerce.listProducts(
            ApiInputPolicy.optionalIdentifier(category, "category"),
            ApiInputPolicy.optionalShortText(subcategory, "subcategory"),
            ApiInputPolicy.optionalIdentifier(merchantId, "merchantId"),
            includeArchived,
        )
    }

    @GetMapping("/products/{productId}/related")
    fun relatedProducts(
        @PathVariable productId: String,
        @RequestParam(defaultValue = "6") limit: Int,
    ): ResponseEntity<Any> = ok { commerce.relatedProducts(productId, limit) }

    @PostMapping("/products")
    fun createProduct(@RequestBody request: ManageCatalogProductRequest): ResponseEntity<Any> =
        ok { commerce.createProduct(request) }

    @PatchMapping("/products/{productId}")
    fun updateProduct(@PathVariable productId: String, @RequestBody request: PatchCatalogProductRequest): ResponseEntity<Any> =
        ok { commerce.updateProduct(productId, request) }

    @PostMapping("/products/{productId}/archive")
    fun archiveProduct(@PathVariable productId: String): ResponseEntity<Any> =
        ok { commerce.archiveProduct(productId) }
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
class BargainingController(private val commerce: CommerceService) {
    @GetMapping("/threads")
    fun threads(@AuthenticationPrincipal userId: String?): ResponseEntity<Any> =
        authenticated(userId) { commerce.bargainingThreads(it) }

    @PostMapping("/threads")
    fun createThread(@AuthenticationPrincipal userId: String?, @RequestBody request: CreateBargainingThreadRequest): ResponseEntity<Any> =
        authenticated(userId) { commerce.createBargainingThread(it, request) }

    @PostMapping("/threads/{threadId}/offers")
    fun addOffer(
        @AuthenticationPrincipal userId: String?,
        @PathVariable threadId: String,
        @RequestBody request: CreateBargainingOfferRequest,
    ): ResponseEntity<Any> =
        authenticated(userId) { commerce.addCustomerBargainingOffer(it, threadId, request) }
}

private fun ok(block: () -> Any): ResponseEntity<Any> = ResponseEntity.ok(block())

private fun authenticated(userId: String?, block: (String) -> Any): ResponseEntity<Any> {
    if (userId == null) return ResponseEntity.status(401).build()
    return ok { block(userId) }
}

private fun CatalogCategoryRecord.toDto(): CatalogCategoryDto =
    CatalogCategoryDto(key, title, supportLabel, accentHex)

private fun MerchantStorefrontRecord.toDto(merchantRecord: MerchantRecord?, storeProducts: List<ProductRecord>): MerchantStorefrontDto =
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
        products = storeProducts.map { it.toDto() },
    )

private fun ProductRecord.toDto(): CatalogProductDto =
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
        optionHint = optionHint,
        originalPriceCfa = originalPriceCfa,
        hasDiscount = originalPriceCfa?.let { original -> basePriceCfa?.let { original > it } } ?: false,
        bargainingEnabled = bargainingEnabled,
        bargainFloorCfa = bargainFloorCfa,
        cameraVerified = cameraVerified,
        capturedAtLabel = capturedAtLabel,
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
