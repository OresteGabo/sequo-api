package dev.orestegabo.sequo_api.domain.catalog

import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val DEFAULT_RELATED_PRODUCTS_LIMIT = 6
private const val MAX_RELATED_PRODUCTS_LIMIT = 6

data class SequoProductDto(
    val id: String,
    val merchantId: String?,
    val name: String,
    val kind: CatalogProductKind,
    val status: CatalogProductStatus,
    val category: String?,
    val basePriceCfa: Int?,
    val createdAt: Instant,
)

interface ProductRepository : JpaRepository<ProductRecord, String> {
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
    fun findRelatedActiveProductsByCategory(
        targetId: String,
        pageable: org.springframework.data.domain.Pageable,
    ): List<ProductRecord>
}

@Service
class ProductRelatedService(
    private val productRepository: ProductRepository,
) {
    fun getRelatedProducts(productId: UUID, limit: Int = DEFAULT_RELATED_PRODUCTS_LIMIT): List<SequoProductDto> {
        if (limit <= 0) return emptyList()

        val boundedLimit = limit.coerceAtMost(MAX_RELATED_PRODUCTS_LIMIT)
        return productRepository
            .findRelatedActiveProductsByCategory(
                targetId = productId.toString(),
                pageable = PageRequest.of(0, boundedLimit),
            )
            .map { it.toDto() }
    }
}

@RestController
@RequestMapping("/api/v1/products")
class ProductRelatedController(
    private val productRelatedService: ProductRelatedService,
) {
    @GetMapping("/{id}/related")
    fun relatedProducts(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = DEFAULT_RELATED_PRODUCTS_LIMIT.toString()) limit: Int,
    ): ResponseEntity<Any> =
        if (limit !in 1..MAX_RELATED_PRODUCTS_LIMIT) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "code" to "invalid_related_products_request",
                    "message" to "limit must be between 1 and $MAX_RELATED_PRODUCTS_LIMIT.",
                )
            )
        } else {
            ResponseEntity.ok(productRelatedService.getRelatedProducts(id, limit))
        }
}

private fun ProductRecord.toDto(): SequoProductDto =
    SequoProductDto(
        id = id,
        merchantId = merchantId,
        name = name,
        kind = kind,
        status = status,
        category = category,
        basePriceCfa = basePriceCfa,
        createdAt = createdAt,
    )
