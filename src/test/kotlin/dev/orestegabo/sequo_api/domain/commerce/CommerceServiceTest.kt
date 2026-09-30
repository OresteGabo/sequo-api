package dev.orestegabo.sequo_api.domain.commerce

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.auth.UserStatus
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:commerce_service;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
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
    fun bargainingThreadRequiresNegotiableProductAndStoresOffers() {
        seedCatalog()
        val userId = createCustomer("bargain@sequo.test")

        val thread = service.createBargainingThread(
            userId,
            CreateBargainingThreadRequest(productId = "bargain-product", amountCfa = 1500, message = "Can you accept this?"),
        )
        val updated = service.addCustomerBargainingOffer(userId, thread.id, CreateBargainingOfferRequest(amountCfa = 1600))

        assertEquals(BargainingThreadStatus.OPEN, updated.status)
        assertEquals(2, updated.offers.size)
        assertEquals(1600, updated.lastOfferCfa)
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
        promotions.save(CatalogPromotionRecord("promo-test-product", "Ready now", "Test plate", "Warm test meal", "test-product", sortOrder = 1))
    }

    private fun createCustomer(email: String = "customer@sequo.test"): String =
        requireNotNull(users.save(User(email = email, provider = AuthProvider.EMAIL, status = UserStatus.ACTIVE)).id)
}
