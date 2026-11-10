package dev.orestegabo.sequo_api.domain.catalog

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.security.MessageDigest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity
import java.text.Normalizer

/** Shared product family, never a seller offer, price, stock item or exact branded SKU. */
@Entity
@Table(name = "catalog_product_references")
class ProductReferenceRecord(
    @Id val id: String,
    @Column(nullable = false) val name: String,
    @Column(nullable = false) val category: String,
    @Column(nullable = false, columnDefinition = "text") val aliases: String,
    @Column(nullable = false) val essential: Boolean = false,
) {
    fun dto() = ProductReferenceDto(id, name, category, aliases.split('|').filter(String::isNotBlank), essential)
}

data class ProductReferenceDto(val id: String, val name: String, val category: String, val aliases: List<String>, val essential: Boolean = false)
interface ProductReferenceRepository : JpaRepository<ProductReferenceRecord, String> {
    fun findByEssentialTrueOrderByCategoryAscNameAsc(): List<ProductReferenceRecord>
    @Modifying
    @Query(value = "INSERT INTO catalog_product_references(id,name,category,aliases,essential) VALUES (:id,:name,:category,'',FALSE) ON CONFLICT DO NOTHING", nativeQuery = true)
    fun insertIfMissing(id: String, name: String, category: String): Int
    @Modifying
    @Query(value = "MERGE INTO catalog_product_references r USING (VALUES (CAST(:id AS VARCHAR),CAST(:name AS VARCHAR),CAST(:category AS VARCHAR))) v(id,name,category) ON r.id=v.id WHEN NOT MATCHED THEN INSERT (id,name,category,aliases,essential) VALUES (v.id,v.name,v.category,'',FALSE)", nativeQuery = true)
    fun insertIfMissingEmbedded(id: String, name: String, category: String): Int
}

internal fun normalizeProductName(value: String): String = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace("œ", "oe").replace("æ", "ae")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

internal fun suggestProductReferences(records: List<ProductReferenceDto>, query: String, limit: Int): List<ProductReferenceDto> {
    val normalized = normalizeProductName(query)
    if (normalized.length < 2) return emptyList()
    val terms = normalized.split(' ')
    fun names(ref: ProductReferenceDto) = (listOf(ref.name) + ref.aliases).map(::normalizeProductName)
    return records.mapNotNull { ref ->
        val names = names(ref)
        val score = when {
            normalized in names -> 0
            names.any { it.startsWith(normalized) } -> 1
            names.any { name -> terms.all { term -> name.split(' ').any { it.startsWith(term) } } } -> 2
            else -> return@mapNotNull null
        }
        ref to score
    }.sortedWith(compareBy<Pair<ProductReferenceDto, Int>> { it.second }.thenBy { it.first.name })
        .take(limit).map { it.first }
}

@Service
class ProductReferenceService(private val repository: ProductReferenceRepository, private val dataSource: javax.sql.DataSource) {
    private val postgres by lazy { dataSource.connection.use { it.metaData.databaseProductName == "PostgreSQL" } }
    @Transactional(readOnly = true)
    fun suggestions(query: String, limit: Int): List<ProductReferenceDto> {
        require(query.length <= 180) { "q must be at most 180 characters." }
        require(limit in 1..12) { "limit must be between 1 and 12." }
        return suggestProductReferences(repository.findAll().map { it.dto() }, query, limit)
    }
    @Transactional(readOnly = true)
    fun essentials(): List<ProductReferenceDto> = repository.findByEssentialTrueOrderByCategoryAscNameAsc().map { it.dto() }

    fun get(id: String?): ProductReferenceDto? = id?.let { repository.findById(it).orElse(null)?.dto() }
    fun byIds(ids: Collection<String>): Map<String, ProductReferenceDto> = repository.findAllById(ids.distinct()).associate { it.id to it.dto() }
    fun resolve(id: String?, name: String, category: String?): String? {
        if (id != null) {
            if (id.isBlank()) return null // Explicit unlink; null in a patch means no change.
            require(id.length <= 128 && repository.existsById(id)) { "Choose an existing shared product reference." }
            return id
        }
        // Exact, unambiguous aliases only. Never infer identity from a substring or similar spelling.
        val normalized = normalizeProductName(name)
        val matching = repository.findAll().filter { ref ->
            (listOf(ref.name) + ref.dto().aliases).any { normalizeProductName(it) == normalized }
        }.map { it.id }.distinct()
        if (matching.size == 1) return matching.single()
        if (matching.size > 1) return null // Ambiguous aliases require an explicit vendor choice.
        if (normalized.length < 2) return null
        val key = MessageDigest.getInstance("MD5").digest(normalized.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val newId = "ref-custom-$key"
        // Same normalized name from two shops creates a single shared reference, even concurrently.
        val familyCategory = category?.takeIf { it.isNotBlank() } ?: "other"
        if (postgres) repository.insertIfMissing(newId, name.trim(), familyCategory)
        else repository.insertIfMissingEmbedded(newId, name.trim(), familyCategory)
        return newId
    }
}

@RestController
@RequestMapping("/api/catalog/product-references")
class ProductReferenceController(private val service: ProductReferenceService) {
    @GetMapping("/essentials")
    fun essentials() = service.essentials()

    @GetMapping
    fun suggestions(@RequestParam(defaultValue = "") q: String, @RequestParam(defaultValue = "6") limit: Int): ResponseEntity<Any> =
        try { ResponseEntity.ok(service.suggestions(q, limit)) }
        catch (invalid: IllegalArgumentException) { ResponseEntity.badRequest().body(mapOf("message" to invalid.message)) }
}
