package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.*
import dev.orestegabo.sequo_api.domain.commerce.*
import jakarta.persistence.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

@Entity
@Table(name = "admin_catalog_media_changes", uniqueConstraints = [UniqueConstraint(name = "uk_admin_catalog_media_retry", columnNames = ["idempotency_key"])])
class AdminCatalogMediaChange(
    @Id val id: String,
    @Column(name = "product_id", nullable = false) val productId: String,
    @Column(name = "actor_user_id", nullable = false) val actorUserId: String,
    @Column(nullable = false, length = 32) val action: String,
    @Column(nullable = false, length = 1000) val reason: String,
    @Column(name = "idempotency_key", nullable = false, length = 128) val idempotencyKey: String,
    @Column(name = "intent_hash", nullable = false, length = 64) val intentHash: String,
    @Column(name = "before_value", nullable = false, columnDefinition = "text") val beforeValue: String,
    @Column(name = "after_value", nullable = false, columnDefinition = "text") val afterValue: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)
interface AdminCatalogMediaChangeRepository : JpaRepository<AdminCatalogMediaChange, String> {
    fun findByIdempotencyKey(idempotencyKey: String): AdminCatalogMediaChange?
    fun findTop50ByProductIdOrderByCreatedAtDesc(productId: String): List<AdminCatalogMediaChange>
}
data class AdminCatalogMediaProduct(val id: String, val merchantId: String?, val name: String, val kind: CatalogProductKind,
    val status: CatalogProductStatus, val photoRequired: Boolean, val cameraVerified: Boolean, val imageUrl: String?,
    val capturedAtLabel: String?, val updatedAt: Instant)
data class AdminCatalogMediaDto(val product: AdminCatalogMediaProduct, val approvedGenericImageUrl: String?,
    val previewDataUrl: String?, val history: List<AdminPartyChangeDto>)
data class AdminCatalogMediaRequest(val source: ProductMediaSource?, val catalogImageId: String? = null,
    val reason: String?, val expectedUpdatedAt: Instant?, val idempotencyKey: String?)
data class AdminCatalogPublicationRequest(val status: CatalogProductStatus?, val reason: String?, val expectedUpdatedAt: Instant?, val idempotencyKey: String?)

@Service
class AdminCatalogMediaService(
    private val products: CommerceProductRepository,
    private val commerce: CommerceService,
    private val media: CatalogCameraMedia,
    private val changes: AdminCatalogMediaChangeRepository,
    private val users: UserRepository,
    @Value("\${sequo.catalog.approved-generic-image-url:}") private val genericUrl: String,
) {
    @Transactional(readOnly = true)
    fun get(id: String): AdminCatalogMediaDto = detail(product(id, false))

    @Transactional(rollbackFor = [Exception::class])
    fun generic(id: String, request: AdminCatalogMediaRequest, actor: String): AdminCatalogMediaDto {
        require(request.source == ProductMediaSource.GenericCatalogReference) { "Use the actual camera upload for a live photograph." }
        require(request.catalogImageId == "standard") { "Select the server-approved standard image." }
        return change(id, actor, "GENERIC_IMAGE", request.reason, request.expectedUpdatedAt, request.idempotencyKey, listOf(request.source.name, request.catalogImageId)) {
            commerce.prepareAdminProductPhoto(it.id, MerchantProductPhotoRequest(source = ProductMediaSource.GenericCatalogReference, catalogImageId = "standard"))
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun camera(id: String, file: MultipartFile, capturedAt: Instant, deviceId: String, reason: String, revision: Instant, key: String, actor: String): AdminCatalogMediaDto {
        require(file.size in 1..8L * 1024 * 1024) { "Camera photos must be at most 8 MiB." }
        val hash = sha256(file.bytes)
        return change(id, actor, "CAMERA_IMAGE", reason, revision, key,
            listOf(capturedAt.toString(), deviceId, file.originalFilename.orEmpty(), file.contentType.orEmpty(), hash)) {
            commerce.uploadAdminCameraPhoto(it.id, file, capturedAt, deviceId)
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun publication(id: String, request: AdminCatalogPublicationRequest, actor: String): AdminCatalogMediaDto {
        require(request.status in setOf(CatalogProductStatus.ACTIVE, CatalogProductStatus.INACTIVE)) { "Select active or inactive publication state." }
        return change(id, actor, "PUBLICATION", request.reason, request.expectedUpdatedAt, request.idempotencyKey, listOf(request.status!!.name)) {
            commerce.updateProduct(it.id, PatchCatalogProductRequest(status = request.status))
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun genericUpload(id: String, file: MultipartFile, approved: Boolean, reason: String, revision: Instant, key: String, actor: String): AdminCatalogMediaDto {
        require(approved) { "Explicitly approve this image for the actual standard product." }
        require(file.size in 1..8L * 1024 * 1024) { "Generic images must be at most 8 MiB." }
        return change(id, actor, "GENERIC_UPLOAD", reason, revision, key, listOf(approved.toString(), file.originalFilename.orEmpty(), file.contentType.orEmpty(), sha256(file.bytes))) {
            val stored = media.storeApprovedGeneric(it, file.originalFilename.orEmpty(), file.contentType.orEmpty(), file.bytes)
            it.imageUrl = stored.publicUrl
            it.cameraVerified = false
            it.capturedAtLabel = "SEQUO-approved generic image"
        }
    }

    private fun change(id: String, actor: String, action: String, rawReason: String?, revision: Instant?, rawKey: String?, fields: List<String>, mutation: (ProductRecord) -> Unit): AdminCatalogMediaDto {
        val user = users.findById(actor).orElse(null)
        if (user == null || !user.active || !user.status.canAuthenticate() || user.roles.none { it in RoleGroups.AdminOnly })
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "An active SEQUO administrator is required.")
        val reason = ApiInputPolicy.requiredShortText(rawReason.orEmpty(), "reason", 1000)
        val key = ApiInputPolicy.requiredIdempotencyKey(rawKey.orEmpty())
        requireNotNull(revision) { "Read the current server revision before changing product media." }
        val product = product(id, true)
        val intent = sha256((listOf(actor, product.id, action, reason, revision.toString()) + fields).joinToString("\u0000").toByteArray())
        changes.findByIdempotencyKey(key)?.let {
            if (it.intentHash != intent) throw ResponseStatusException(HttpStatus.CONFLICT, "This retry key belongs to a different catalog change.")
            return detail(product)
        }
        if (product.updatedAt != revision) throw ResponseStatusException(HttpStatus.CONFLICT, "The product changed. Refresh and review before trying again.")
        val before = state(product)
        mutation(product)
        product.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        products.saveAndFlush(product)
        changes.saveAndFlush(AdminCatalogMediaChange(UUID.randomUUID().toString(), product.id, actor, action, reason, key, intent, before, state(product), product.updatedAt))
        return detail(product)
    }

    private fun product(id: String, lock: Boolean): ProductRecord {
        val safe = ApiInputPolicy.requiredIdentifier(id, "productId")
        return (if (lock) products.lockForCustomization(safe) else products.findById(safe).orElse(null))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Product was not found.")
    }
    private fun state(p: ProductRecord) = "status=${p.status};image=${p.imageUrl.orEmpty()};cameraVerified=${p.cameraVerified};capturedAt=${p.capturedAtLabel.orEmpty()}"
    private fun detail(p: ProductRecord): AdminCatalogMediaDto {
        val configured = genericUrl.trim().takeIf { url -> runCatching {
            val u = URI(url); url.length <= 500 && u.scheme == "https" && u.host != null && u.userInfo == null
        }.getOrDefault(false) }
        val preview = if (p.imageUrl == "/api/catalog/products/${p.id}/image") media.read(p.id)?.let(::thumbnail) else null
        return AdminCatalogMediaDto(AdminCatalogMediaProduct(p.id, p.merchantId, p.name, p.kind, p.status, p.photoRequired, p.cameraVerified,
            p.imageUrl, p.capturedAtLabel, p.updatedAt), configured, preview,
            changes.findTop50ByProductIdOrderByCreatedAtDesc(p.id).map { AdminPartyChangeDto(it.id, it.action, it.actorUserId, it.beforeValue, it.afterValue, it.reason, it.idempotencyKey, it.createdAt) })
    }
    private fun thumbnail(bytes: ByteArray): String? = runCatching {
        val image = ImageIO.read(bytes.inputStream()) ?: return null
        val scale = minOf(1.0, 256.0 / maxOf(image.width, image.height))
        val target = BufferedImage(maxOf(1, (image.width * scale).toInt()), maxOf(1, (image.height * scale).toInt()), BufferedImage.TYPE_INT_RGB)
        target.createGraphics().let { graphics -> try { graphics.drawImage(image, 0, 0, target.width, target.height, null) } finally { graphics.dispose() } }
        val out = ByteArrayOutputStream(); check(ImageIO.write(target, "jpeg", out))
        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }.getOrNull()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

@RestController
@RequestMapping("/api/admin/web/catalog/products/{productId}/media")
class AdminCatalogMediaController(private val service: AdminCatalogMediaService) {
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun concurrentChange(): ResponseEntity<Map<String, String>> = ResponseEntity.status(409).cacheControl(CacheControl.noStore())
        .body(mapOf("code" to "catalog_media_conflict", "message" to "A concurrent catalog change used this retry identity. Refresh and review before trying again."))
    @ExceptionHandler(ResponseStatusException::class)
    fun rejected(error: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(error.statusCode).cacheControl(CacheControl.noStore()).body(mapOf(
            "code" to "catalog_media_rejected", "message" to (error.reason ?: "The catalog media request was refused.")))
    @GetMapping fun get(@PathVariable productId: String, authentication: Authentication?) = access(authentication) { service.get(productId) }
    @PostMapping("/photos") fun generic(@PathVariable productId: String, authentication: Authentication?, @RequestBody request: AdminCatalogMediaRequest) =
        access(authentication) { service.generic(productId, request, requireNotNull(authentication).name) }
    @PostMapping("/photos/camera", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun camera(@PathVariable productId: String, authentication: Authentication?, @RequestParam file: MultipartFile,
        @RequestParam capturedAt: Instant, @RequestParam deviceId: String, @RequestParam reason: String,
        @RequestParam expectedUpdatedAt: Instant, @RequestParam idempotencyKey: String) = access(authentication) {
            service.camera(productId, file, capturedAt, deviceId, reason, expectedUpdatedAt, idempotencyKey, requireNotNull(authentication).name)
        }
    @PostMapping("/photos/generic", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun genericUpload(@PathVariable productId: String, authentication: Authentication?, @RequestParam file: MultipartFile,
        @RequestParam genericApproved: Boolean, @RequestParam reason: String, @RequestParam expectedUpdatedAt: Instant,
        @RequestParam idempotencyKey: String) = access(authentication) {
            service.genericUpload(productId, file, genericApproved, reason, expectedUpdatedAt, idempotencyKey, requireNotNull(authentication).name)
        }
    @PostMapping("/publication") fun publication(@PathVariable productId: String, authentication: Authentication?, @RequestBody request: AdminCatalogPublicationRequest) =
        access(authentication) { service.publication(productId, request, requireNotNull(authentication).name) }
    private fun access(authentication: Authentication?, block: () -> Any): ResponseEntity<Any> {
        if (authentication == null || !authentication.isAuthenticated) return ResponseEntity.status(401).build()
        if (!authentication.hasAnyRole(RoleGroups.AdminOnly)) return ResponseEntity.status(403).build()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(block())
    }
}
