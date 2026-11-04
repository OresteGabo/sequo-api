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
    @Column(nullable = false, length = 8 * 1024 * 1024) val jpeg: ByteArray,
)

interface CatalogCameraPayloadRepository : JpaRepository<CatalogCameraPayload, String>

@Service
class CatalogCameraMedia(private val payloads: CatalogCameraPayloadRepository) {
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
