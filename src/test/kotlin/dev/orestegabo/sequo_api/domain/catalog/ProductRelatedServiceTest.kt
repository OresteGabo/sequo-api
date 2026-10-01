package dev.orestegabo.sequo_api.domain.catalog

import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceService
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class ProductRelatedServiceTest @Autowired constructor(
    private val productRepository: CommerceProductRepository,
    private val commerceService: CommerceService,
) {
    @Test
    fun relatedProductsUseSameCategoryActiveProductsAndExcludeTarget() {
        val targetId = UUID.randomUUID()
        val closestRiceId = UUID.randomUUID().toString()
        val fartherRiceId = UUID.randomUUID().toString()
        val newerOtherSubcategoryId = UUID.randomUUID().toString()

        productRepository.saveAll(
            listOf(
                product(
                    id = targetId.toString(),
                    name = "Target rice",
                    category = "GeneralGoods",
                    subcategory = "Rice",
                    basePriceCfa = 5_000,
                    createdAt = Instant.parse("2026-09-01T10:00:00Z"),
                ),
                product(
                    id = fartherRiceId,
                    name = "Farther rice",
                    category = "GeneralGoods",
                    subcategory = "Rice",
                    basePriceCfa = 6_500,
                    createdAt = Instant.parse("2026-09-02T10:00:00Z"),
                ),
                product(
                    id = closestRiceId,
                    name = "Closest rice",
                    category = "GeneralGoods",
                    subcategory = "Rice",
                    basePriceCfa = 5_200,
                    createdAt = Instant.parse("2026-09-03T10:00:00Z"),
                ),
                product(
                    id = UUID.randomUUID().toString(),
                    name = "Inactive rice",
                    category = "GeneralGoods",
                    subcategory = "Rice",
                    basePriceCfa = 4_900,
                    status = CatalogProductStatus.INACTIVE,
                    createdAt = Instant.parse("2026-09-04T10:00:00Z"),
                ),
                product(
                    id = newerOtherSubcategoryId,
                    name = "Newer oil",
                    category = "GeneralGoods",
                    subcategory = "Oil",
                    basePriceCfa = 5_050,
                    createdAt = Instant.parse("2026-09-05T10:00:00Z"),
                ),
                product(
                    id = UUID.randomUUID().toString(),
                    name = "Chicken plate",
                    category = "Food",
                    createdAt = Instant.parse("2026-09-06T10:00:00Z"),
                ),
            )
        )

        val related = commerceService.relatedProducts(targetId.toString(), limit = 6)

        assertEquals(listOf(closestRiceId, fartherRiceId, newerOtherSubcategoryId), related.map { it.id })
        assertTrue(related.none { it.id == targetId.toString() })
    }

    @Test
    fun relatedProductsRespectRequestedLimitAndMaximumLimit() {
        val targetId = UUID.randomUUID()
        productRepository.save(
            product(
                id = targetId.toString(),
                name = "Target",
                category = "GeneralGoods",
                createdAt = Instant.parse("2026-09-01T10:00:00Z"),
            )
        )
        (1..8).forEach { index ->
            productRepository.save(
                product(
                    id = UUID.randomUUID().toString(),
                    name = "Related $index",
                    category = "GeneralGoods",
                    createdAt = Instant.parse("2026-09-0${index + 1}T10:00:00Z"),
                )
            )
        }

        assertEquals(3, commerceService.relatedProducts(targetId.toString(), limit = 3).size)
        assertFailsWith<IllegalArgumentException> {
            commerceService.relatedProducts(targetId.toString(), limit = 99)
        }
        assertFailsWith<IllegalArgumentException> {
            commerceService.relatedProducts(targetId.toString(), limit = 0)
        }
    }

    private fun product(
        id: String,
        name: String,
        category: String?,
        subcategory: String? = null,
        basePriceCfa: Int = 1_000,
        status: CatalogProductStatus = CatalogProductStatus.ACTIVE,
        createdAt: Instant,
    ): ProductRecord =
        ProductRecord(
            id = id,
            merchantId = null,
            name = name,
            status = status,
            category = category,
            subcategory = subcategory,
            basePriceCfa = basePriceCfa,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}
