package dev.orestegabo.sequo_api.domain.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service

@Entity
@Table(name = "catalog_camera_payloads")
class CatalogCameraPayload(
    @Id @Column(name = "product_id") val productId: String,
    @Column(nullable = false, columnDefinition = "bytea") val jpeg: ByteArray,
)

interface CatalogCameraPayloadRepository : JpaRepository<CatalogCameraPayload, String>

@Service
class CatalogCameraMedia(private val payloads: CatalogCameraPayloadRepository) {
    /** Authorized SEQUO generic approval stores actual pixels without claiming camera evidence. */
    fun storeApprovedGeneric(product: ProductRecord, fileName: String, contentType: String, bytes: ByteArray): StoredProductMedia {
        require(product.kind == CatalogProductKind.GenericSealedItem) { "Generic uploads are limited to standard sealed products." }
        require(contentType == "image/jpeg") { "Generic uploads must be JPEG images." }
        require(fileName.isNotBlank() && fileName.none { it.isISOControl() || it == '/' || it == '\\' } &&
            fileName.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg")) { "Use a JPEG file name without paths." }
        require(bytes.size in 1..8 * 1024 * 1024) { "Generic images must be at most 8 MiB." }
        require(bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) { "The image is not a JPEG." }
        payloads.save(CatalogCameraPayload(product.id, normalizeJpeg(bytes)))
        return StoredProductMedia("products/${product.id}/generic.jpg", "/api/catalog/products/${product.id}/image")
    }

    fun upload(
        product: ProductRecord,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        capturedAt: java.time.Instant,
        deviceId: String,
    ): ProductMediaSnapshot {
        require(contentType == "image/jpeg") { "Camera uploads must be JPEG images." }
        val hash = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val policy = ProductMediaPolicyService(
            storage = { capture ->
                payloads.save(CatalogCameraPayload(capture.productId, normalizeJpeg(capture.contentBytes)))
                StoredProductMedia("products/${capture.productId}/camera.jpg", "/api/catalog/products/${capture.productId}/image")
            },
            moderator = { ProductMediaModerationResult(ProductMediaModerationStatus.Approved) },
        )
        return when (val result = policy.accept(
            ProductMediaSubmission.LiveCameraCapture(
                product.id, product.kind, fileName, contentType, bytes.size.toLong(), bytes,
                LiveCameraMetadata(capturedAt, deviceId, hash),
            )
        )) {
            is ProductMediaUploadResult.Accepted -> result.snapshot
            is ProductMediaUploadResult.Rejected -> throw IllegalArgumentException(result.error.message)
        }
    }

    fun read(productId: String): ByteArray? = payloads.findById(productId).orElse(null)?.jpeg

    private fun normalizeJpeg(bytes: ByteArray): ByteArray {
        val reader = ImageIO.getImageReadersByFormatName("JPEG").next()
        try {
            MemoryCacheImageInputStream(bytes.inputStream()).use { stream ->
                reader.input = stream
                require(reader.getWidth(0).toLong() * reader.getHeight(0) <= 12_000_000) {
                    "Camera image dimensions are too large."
                }
                val decoded = reader.read(0)
                val output = ByteArrayOutputStream()
                check(ImageIO.write(decoded, "jpeg", output))
                require(output.size() <= 8 * 1024 * 1024) { "Processed camera image exceeds the upload size limit." }
                return output.toByteArray()
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("Camera image is not a readable JPEG.", error)
        } finally {
            reader.dispose()
        }
    }
}
