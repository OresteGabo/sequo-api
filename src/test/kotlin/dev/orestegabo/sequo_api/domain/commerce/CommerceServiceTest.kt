package dev.orestegabo.sequo_api.domain.commerce

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.auth.UserStatus
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus
import dev.orestegabo.sequo_api.domain.catalog.ProductMediaModerationStatus
import dev.orestegabo.sequo_api.domain.catalog.ProductMediaSource
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.catalog.FoodCustomizationSelection
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressKind
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressRecord
import dev.orestegabo.sequo_api.domain.platform.CustomerAddressRepository
import dev.orestegabo.sequo_api.domain.platform.PlatformCommerceService
import dev.orestegabo.sequo_api.domain.platform.ProductCustomizationGroupRequest
import dev.orestegabo.sequo_api.domain.platform.ProductCustomizationOptionRequest
import dev.orestegabo.sequo_api.domain.platform.ReplaceProductCustomizationsRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:commerce_service;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "sequo.cart.straight-line-dispatch-origin=0,0",
        "sequo.catalog.approved-generic-image-url=https://cdn.sequo.example/catalog/rice.webp",
    ]
)
@Transactional
class CommerceServiceTest @Autowired constructor(
    private val service: CommerceService,
    private val categories: CatalogCategoryRepository,
    private val products: CommerceProductRepository,
    private val merchants: CommerceMerchantRepository,
    private val storefronts: MerchantStorefrontRepository,
    private val promotions: CatalogPromotionRepository,
    private val users: UserRepository,
    private val addresses: CustomerAddressRepository,
    private val quotes: CartDeliveryQuoteService,
    private val platformCommerce: PlatformCommerceService,
) {
    @Test
    fun homeReturnsGeneralCatalogDataFromDatabase() {
        seedCatalog()

        val home = service.home(category = "food")

        assertEquals(listOf("food"), home.categories.map { it.key })
        assertEquals("Chez Test", home.merchants.single().name)
        assertTrue(home.products.any { it.name == "Test plate" })
        assertEquals("promo-test-product", home.promotions.single().id)
    }

    @Test
    fun cartPersistsGeneralProductQuantitiesForCustomer() {
        seedCatalog()
        val userId = createCustomer()

        val cart = service.putCartItem(userId, UpsertCartItemRequest("test-product", 3))

        assertEquals(3, cart.totalItems)
        assertEquals(6000, cart.subtotalCfa)
        assertEquals(0, service.clearCart(userId).totalItems)
    }

    @Test
    fun cartRestoresVariantsRepricesAndQuotesOnlyAvailableLinesForOwnedAddresses() {
        seedCatalog()
        val customer = createCustomer()
        val other = createCustomer("other-cart@sequo.test")
        val group = platformCommerce.replaceCustomizations(
            "test-product", ReplaceProductCustomizationsRequest(listOf(
                ProductCustomizationGroupRequest("Side", 1, 1, options = listOf(
                    ProductCustomizationOptionRequest("Plantain", 500),
                    ProductCustomizationOptionRequest("Salad", 200),
                )),
            )), "merchant-test",
        ).single()
        fun variant(option: Int, quantity: Int) = UpsertCartItemRequest(
            "test-product", quantity, listOf(FoodCustomizationSelection(group.id, setOf(group.options[option].id))),
        )
        service.putCartItem(customer, variant(0, 1))
        service.putCartItem(customer, variant(1, 1))
        service.putCartItem(customer, UpsertCartItemRequest("bargain-product", 1))
        service.putCartItem(customer, variant(0, 2))
        val restored = service.cart(customer)
        assertEquals(3, restored.items.size)
        assertEquals(4, restored.totalItems)
        assertEquals(9700, restored.subtotalCfa)
        assertEquals(0, service.cart(other).totalItems)

        for ((meters, fee) in listOf(4999 to 400, 5000 to 400, 5001 to 500)) {
            val id = "cart-address-$meters"
            addresses.save(CustomerAddressRecord(
                id, customer, CustomerAddressKind.HOME, "Home", "Fixture", "+22890000000",
                "Lomé", latitude = 0.0, longitude = Math.toDegrees(meters / 6_371_000.0),
            ))
            val quote = quotes.quote(customer, id)
            assertEquals(meters, quote.distanceMeters)
            assertEquals(fee, quote.deliveryFeeCfa)
            assertEquals(8500, quote.productTotalCfa)
            assertEquals(1200, quote.optionTotalCfa)
            assertEquals(9700 + fee, quote.totalCfa)
        }

        products.findById("test-product").orElseThrow().basePriceCfa = 2100
        assertEquals(10000, service.cart(customer).subtotalCfa)
        products.findById("bargain-product").orElseThrow().status = CatalogProductStatus.INACTIVE
        val unavailable = service.cart(customer).items.single { it.product.id == "bargain-product" }
        assertEquals(false, unavailable.available)
        assertEquals(null, unavailable.lineTotalCfa)
        service.removeCartItem(other, unavailable.id)
        assertFailsWith<IllegalArgumentException> { quotes.quote(customer, "cart-address-5000") }
        service.removeCartItem(customer, unavailable.id)
        assertEquals(7900, quotes.quote(customer, "cart-address-5000").totalCfa)
        assertFailsWith<IllegalArgumentException> { quotes.quote(other, "cart-address-5000") }
    }

    @Test
    fun merchantCatalogWritesUseAuthenticatedMerchantScopeAndValidateMoney() {
        seedCatalog()

        val created = service.createMerchantProduct(
            merchantId = "merchant-test",
            request = ManageCatalogProductRequest(
                name = "Merchant mango",
                basePriceCfa = 750,
                bargainingEnabled = true,
                bargainFloorCfa = 600,
            ),
        )

        assertEquals("merchant-test", created.merchantId)
        assertEquals(750, created.priceCfa)
        assertTrue(created.bargainingEnabled)
        assertFailsWith<IllegalArgumentException> {
            service.createMerchantProduct(
                merchantId = "merchant-test",
                request = ManageCatalogProductRequest(
                    merchantId = "other-merchant",
                    name = "Bad scope",
                    basePriceCfa = 500,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createMerchantProduct(
                merchantId = "merchant-test",
                request = ManageCatalogProductRequest(
                    name = "Bad price",
                    basePriceCfa = -1,
                ),
            )
        }
    }

    @Test
    fun merchantCatalogUpdatesRequireProductOwnershipAndToggleStatus() {
        seedCatalog()

        val updated = service.updateMerchantProduct(
            merchantId = "merchant-test",
            productId = "test-product",
            request = PatchCatalogProductRequest(name = "Updated plate", basePriceCfa = 2500),
        )
        val inactive = service.deactivateMerchantProduct("merchant-test", "test-product")
        val active = service.activateMerchantProduct("merchant-test", "test-product")

        assertEquals("Updated plate", updated.name)
        assertEquals(2500, updated.priceCfa)
        assertEquals(CatalogProductStatus.INACTIVE, inactive.status)
        assertEquals(CatalogProductStatus.ACTIVE, active.status)
        assertFailsWith<IllegalArgumentException> {
            service.updateMerchantProduct(
                merchantId = "other-merchant",
                productId = "test-product",
                request = PatchCatalogProductRequest(name = "Not yours"),
            )
        }
    }

    @Test
    fun merchantProductPhotoEndpointAcceptsLiveCameraMetadataWithoutBinaryStorage() {
        seedCatalog()

        val status = service.prepareMerchantProductPhoto(
            merchantId = "merchant-test",
            productId = "test-product",
            request = MerchantProductPhotoRequest(
                source = ProductMediaSource.LiveCamera,
                capturedAt = Instant.parse("2026-09-08T10:00:00Z"),
                deviceId = "vendor-device-1",
                contentHash = "sha256:abc123",
            ),
        )

        assertEquals("test-product", status.productId)
        assertEquals(ProductMediaSource.LiveCamera, status.source)
        assertEquals(ProductMediaModerationStatus.NeedsReview, status.moderationStatus)
        assertEquals(false, status.cameraVerified)
        assertEquals(false, status.storageReady)
        assertEquals(true, status.uploadRequired)
        assertEquals("PENDING_UPLOAD_STORAGE", status.status)
    }

    @Test
    fun merchantProductPhotoEndpointAllowsGenericReferencesOnlyForSealedItems() {
        seedCatalog()

        val accepted = service.prepareMerchantProductPhoto(
            merchantId = "merchant-test",
            productId = "sealed-product",
            request = MerchantProductPhotoRequest(
                source = ProductMediaSource.GenericCatalogReference,
                catalogImageId = "standard",
                sourceUrl = "https://cdn.sequo.example/catalog/rice.webp",
            ),
        )

        assertEquals(ProductMediaModerationStatus.Approved, accepted.moderationStatus)
        assertEquals("standard", accepted.catalogImageId)
        assertEquals(false, accepted.uploadRequired)
        assertFailsWith<IllegalArgumentException> {
            service.prepareMerchantProductPhoto(
                merchantId = "merchant-test",
                productId = "test-product",
                request = MerchantProductPhotoRequest(
                    source = ProductMediaSource.GenericCatalogReference,
                    catalogImageId = "catalog-plate-photo",
                ),
            )
        }
    }

    @Test
    fun merchantProductPhotoEndpointRejectsGallerySourcesAndWrongOwners() {
        seedCatalog()

        assertFailsWith<IllegalArgumentException> {
            service.prepareMerchantProductPhoto(
                merchantId = "merchant-test",
                productId = "test-product",
                request = MerchantProductPhotoRequest(source = ProductMediaSource.GalleryUpload),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.prepareMerchantProductPhoto(
                merchantId = "other-merchant",
                productId = "test-product",
                request = MerchantProductPhotoRequest(
                    source = ProductMediaSource.LiveCamera,
                    capturedAt = Instant.parse("2026-09-08T10:00:00Z"),
                    deviceId = "vendor-device-1",
                    contentHash = "sha256:abc123",
                ),
            )
        }
    }

    @Test
    fun bargainingThreadRequiresNegotiableProductAndStoresOffers() {
        seedCatalog()
        val userId = createCustomer("bargain@sequo.test")

        val thread = service.createBargainingThread(
            userId,
            CreateBargainingThreadRequest(productId = "bargain-product", amountCfa = 1500, message = "Can you accept this?"),
        )
        assertFailsWith<IllegalArgumentException> {
            service.addCustomerBargainingOffer(userId, thread.id, CreateBargainingOfferRequest(amountCfa = 1600))
        }
        val updated = service.addCustomerBargainingOffer(userId, thread.id, CreateBargainingOfferRequest(message = "Waiting for your reply"))

        assertEquals(BargainingThreadStatus.OPEN, updated.status)
        assertEquals(2, updated.offers.size)
        assertEquals(1500, updated.lastOfferCfa)
    }

    private fun seedCatalog() {
        categories.save(CatalogCategoryRecord("food", "Food", "Hot meals", "#E2693D", 1))
        merchants.save(MerchantRecord(id = "merchant-test", name = "Chez Test"))
        storefronts.save(
            MerchantStorefrontRecord(
                merchantId = "merchant-test",
                area = "Tokoin",
                kind = "Food now",
                distanceKm = 1.2,
                eta = "20 min",
                photoStatus = "Hot meals",
                openStatus = "Open now",
                rating = "4.8",
                consolidation = "Packed warm",
                sortOrder = 1,
            )
        )
        products.save(
            ProductRecord(
                id = "test-product",
                merchantId = "merchant-test",
                name = "Test plate",
                kind = CatalogProductKind.SellerSpecific,
                status = CatalogProductStatus.ACTIVE,
                category = "food",
                detail = "Rice and chicken",
                basePriceCfa = 2000,
                optionHint = "No pepper",
                sortOrder = 1,
            )
        )
        products.save(
            ProductRecord(
                id = "bargain-product",
                merchantId = "merchant-test",
                name = "Negotiable test item",
                kind = CatalogProductKind.SellerSpecific,
                status = CatalogProductStatus.ACTIVE,
                category = "food",
                detail = "Negotiable item",
                basePriceCfa = 2500,
                optionHint = "Make offer",
                bargainingEnabled = true,
                bargainFloorCfa = 1000,
                sortOrder = 2,
            )
        )
        products.save(
            ProductRecord(
                id = "sealed-product",
                merchantId = "merchant-test",
                name = "Sealed rice",
                kind = CatalogProductKind.GenericSealedItem,
                status = CatalogProductStatus.ACTIVE,
                category = "food",
                detail = "Factory sealed rice",
                basePriceCfa = 3500,
                sortOrder = 3,
            )
        )
        promotions.save(CatalogPromotionRecord("promo-test-product", "Ready now", "Test plate", "Warm test meal", "test-product", sortOrder = 1))
    }

    private fun createCustomer(email: String = "customer@sequo.test"): String =
        requireNotNull(users.save(User(email = email, provider = AuthProvider.EMAIL, status = UserStatus.ACTIVE)).id)
}
