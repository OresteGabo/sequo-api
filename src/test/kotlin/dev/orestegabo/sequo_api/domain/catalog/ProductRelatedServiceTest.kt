package dev.orestegabo.sequo_api.domain.catalog

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class ProductRelatedServiceTest @Autowired constructor(
    private val productRepository: ProductRepository,
    private val productRelatedService: ProductRelatedService,
) {
    @Test
    fun relatedProductsUseSameCategoryActiveProductsAndExcludeTarget() {
        val targetId = UUID.randomUUID()
        val newestRelatedId = UUID.randomUUID().toString()
        val olderRelatedId = UUID.randomUUID().toString()

        productRepository.saveAll(
            listOf(
                product(
                    id = targetId.toString(),
                    name = "Target rice",
                    category = "GeneralGoods",
                    createdAt = Instant.parse("2026-09-01T10:00:00Z"),
                ),
                product(
                    id = olderRelatedId,
                    name = "Older rice",
                    category = "GeneralGoods",
                    createdAt = Instant.parse("2026-09-02T10:00:00Z"),
                ),
                product(
                    id = newestRelatedId,
                    name = "Newer rice",
                    category = "GeneralGoods",
                    createdAt = Instant.parse("2026-09-03T10:00:00Z"),
                ),
                product(
                    id = UUID.randomUUID().toString(),
                    name = "Inactive rice",
                    category = "GeneralGoods",
                    status = CatalogProductStatus.INACTIVE,
                    createdAt = Instant.parse("2026-09-04T10:00:00Z"),
                ),
                product(
                    id = UUID.randomUUID().toString(),
                    name = "Chicken plate",
                    category = "Food",
                    createdAt = Instant.parse("2026-09-05T10:00:00Z"),
                ),
            )
        )

        val related = productRelatedService.getRelatedProducts(targetId, limit = 6)

        assertEquals(listOf(newestRelatedId, olderRelatedId), related.map { it.id })
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

        assertEquals(3, productRelatedService.getRelatedProducts(targetId, limit = 3).size)
        assertEquals(6, productRelatedService.getRelatedProducts(targetId, limit = 99).size)
        assertTrue(productRelatedService.getRelatedProducts(targetId, limit = 0).isEmpty())
    }

    private fun product(
        id: String,
        name: String,
        category: String?,
        status: CatalogProductStatus = CatalogProductStatus.ACTIVE,
        createdAt: Instant,
    ): ProductRecord =
        ProductRecord(
            id = id,
            merchantId = null,
            name = name,
            status = status,
            category = category,
            basePriceCfa = 1_000,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}
