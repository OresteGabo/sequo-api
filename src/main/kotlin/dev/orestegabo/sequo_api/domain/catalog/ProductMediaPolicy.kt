package dev.orestegabo.sequo_api.domain.catalog

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

enum class CatalogProductKind {
    SellerSpecific,
    GenericSealedItem,
}

enum class ProductMediaSource {
    LiveCamera,
    GenericCatalogReference,
    GalleryUpload,
    WebImage,
}

data class LiveCameraMetadata(
    val capturedAt: Instant,
    val deviceId: String,
    val contentHash: String,
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId is required." }
        require(contentHash.isNotBlank()) { "contentHash is required." }
    }
}

sealed class ProductMediaSubmission {
    abstract val productId: String
    abstract val productKind: CatalogProductKind

    data class LiveCameraCapture(
        override val productId: String,
        override val productKind: CatalogProductKind,
        val fileName: String,
        val contentType: String,
        val sizeBytes: Long,
        val contentBytes: ByteArray,
        val metadata: LiveCameraMetadata,
    ) : ProductMediaSubmission() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as LiveCameraCapture

            if (productId != other.productId) return false
            if (productKind != other.productKind) return false
            if (fileName != other.fileName) return false
            if (contentType != other.contentType) return false
            if (sizeBytes != other.sizeBytes) return false
            if (!contentBytes.contentEquals(other.contentBytes)) return false
            if (metadata != other.metadata) return false

            return true
        }

        override fun hashCode(): Int {
            var result = productId.hashCode()
            result = 31 * result + productKind.hashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + contentType.hashCode()
            result = 31 * result + sizeBytes.hashCode()
            result = 31 * result + contentBytes.contentHashCode()
            result = 31 * result + metadata.hashCode()
            return result
        }
    }

    data class GenericCatalogReference(
        override val productId: String,
        override val productKind: CatalogProductKind,
        val catalogImageId: String,
        val sourceUrl: String? = null,
    ) : ProductMediaSubmission()

    data class RejectedClientSource(
        override val productId: String,
        override val productKind: CatalogProductKind,
        val source: ProductMediaSource,
    ) : ProductMediaSubmission()
}

enum class ProductMediaModerationStatus {
    Approved,
    Rejected,
    NeedsReview,
}

data class ProductMediaModerationResult(
    val status: ProductMediaModerationStatus,
    val reason: String? = null,
)

data class StoredProductMedia(
    val storageKey: String,
    val publicUrl: String,
) {
    init {
        require(storageKey.isNotBlank()) { "storageKey is required." }
        require(publicUrl.isNotBlank()) { "publicUrl is required." }
    }
}

data class ProductMediaSnapshot(
    val productId: String,
    val productKind: CatalogProductKind,
    val source: ProductMediaSource,
    val storageKey: String?,
    val publicUrl: String?,
    val catalogImageId: String?,
    val capturedAt: Instant?,
    val deviceId: String?,
    val contentHash: String?,
    val moderationStatus: ProductMediaModerationStatus,
)

data class ProductMediaValidationError(
    val code: String,
    val message: String,
)

sealed class ProductMediaUploadResult {
    data class Accepted(val snapshot: ProductMediaSnapshot) : ProductMediaUploadResult()
    data class Rejected(val error: ProductMediaValidationError) : ProductMediaUploadResult()
}

interface ProductMediaStorage {
    fun storeLiveCameraCapture(submission: ProductMediaSubmission.LiveCameraCapture): StoredProductMedia
}

interface ProductMediaModerator {
    fun moderate(submission: ProductMediaSubmission.LiveCameraCapture): ProductMediaModerationResult
}

class ProductMediaPolicyService(
    private val storage: ProductMediaStorage,
    private val moderator: ProductMediaModerator,
    private val now: () -> Instant = { Instant.now() },
) {
    fun accept(submission: ProductMediaSubmission): ProductMediaUploadResult {
        if (submission.productId.isBlank()) {
            return rejected("missing_product_id", "Product id is required.")
        }

        return when (submission) {
            is ProductMediaSubmission.LiveCameraCapture -> acceptLiveCameraCapture(submission)
            is ProductMediaSubmission.GenericCatalogReference -> acceptGenericCatalogReference(submission)
            is ProductMediaSubmission.RejectedClientSource -> rejected(
                code = "unsupported_media_source",
                message = "${submission.source.name} is not accepted for product media uploads.",
            )
        }
    }

    private fun acceptLiveCameraCapture(
        submission: ProductMediaSubmission.LiveCameraCapture,
    ): ProductMediaUploadResult {
        validateLiveCameraCapture(submission)?.let { return ProductMediaUploadResult.Rejected(it) }

        val moderation = moderator.moderate(submission)
        if (moderation.status != ProductMediaModerationStatus.Approved) {
            return rejected(
                code = "media_moderation_failed",
                message = moderation.reason ?: "Product media did not pass moderation.",
            )
        }

        val stored = storage.storeLiveCameraCapture(submission)
        return ProductMediaUploadResult.Accepted(
            ProductMediaSnapshot(
                productId = submission.productId,
                productKind = submission.productKind,
                source = ProductMediaSource.LiveCamera,
                storageKey = stored.storageKey,
                publicUrl = stored.publicUrl,
                catalogImageId = null,
                capturedAt = submission.metadata.capturedAt,
                deviceId = submission.metadata.deviceId,
                contentHash = submission.contentBytes.sha256Hash(),
                moderationStatus = moderation.status,
            )
        )
    }

    private fun acceptGenericCatalogReference(
        submission: ProductMediaSubmission.GenericCatalogReference,
    ): ProductMediaUploadResult {
        if (submission.productKind != CatalogProductKind.GenericSealedItem) {
            return rejected(
                code = "generic_reference_not_allowed",
                message = "Generic catalog references are allowed only for generic sealed products.",
            )
        }
        if (submission.catalogImageId.isBlank()) {
            return rejected("missing_catalog_image_id", "Catalog image id is required.")
        }
        val sourceUrl = submission.sourceUrl?.trim()
        if (sourceUrl != null && !sourceUrl.startsWith("https://")) {
            return rejected("unsafe_catalog_source_url", "Catalog source URL must use HTTPS.")
        }

        return ProductMediaUploadResult.Accepted(
            ProductMediaSnapshot(
                productId = submission.productId,
                productKind = submission.productKind,
                source = ProductMediaSource.GenericCatalogReference,
                storageKey = null,
                publicUrl = sourceUrl,
                catalogImageId = submission.catalogImageId,
                capturedAt = null,
                deviceId = null,
                contentHash = null,
                moderationStatus = ProductMediaModerationStatus.Approved,
            )
        )
    }

    private fun validateLiveCameraCapture(
        submission: ProductMediaSubmission.LiveCameraCapture,
    ): ProductMediaValidationError? {
        if (submission.fileName.isBlank()) return ProductMediaValidationError("missing_file_name", "File name is required.")
        if (!submission.fileName.isSafeFileName()) {
            return ProductMediaValidationError("unsafe_file_name", "File name must not contain paths or control characters.")
        }

        val normalizedContentType = submission.contentType.trim().lowercase(Locale.ROOT)
        if (normalizedContentType !in ALLOWED_IMAGE_CONTENT_TYPES) {
            return ProductMediaValidationError("unsupported_content_type", "Only JPEG, PNG, and WebP images are accepted.")
        }
        if (!extensionMatchesContentType(submission.fileName, normalizedContentType)) {
            return ProductMediaValidationError("content_type_extension_mismatch", "File extension must match the declared image type.")
        }
        if (submission.sizeBytes <= 0) return ProductMediaValidationError("empty_media", "Product media cannot be empty.")
        if (submission.sizeBytes != submission.contentBytes.size.toLong()) {
            return ProductMediaValidationError("media_size_mismatch", "Declared media size must match the uploaded bytes.")
        }
        if (submission.sizeBytes > MAX_IMAGE_SIZE_BYTES) {
            return ProductMediaValidationError("media_too_large", "Product media exceeds the maximum allowed size.")
        }
        if (!sampleMatchesContentType(submission.contentBytes, normalizedContentType)) {
            return ProductMediaValidationError("media_signature_mismatch", "File content does not match the declared image type.")
        }
        if (submission.contentBytes.containsActiveContentMarker()) {
            return ProductMediaValidationError("active_content_detected", "Product media must not contain script or active content markers.")
        }
        if (submission.metadata.contentHash != submission.contentBytes.sha256Hash()) {
            return ProductMediaValidationError("content_hash_mismatch", "Client media hash must match the uploaded bytes.")
        }
        if (submission.metadata.capturedAt.isAfter(now().plusSeconds(CLOCK_SKEW_SECONDS))) {
            return ProductMediaValidationError("capture_time_in_future", "Live camera capture time cannot be in the future.")
        }

        return null
    }

    private fun rejected(code: String, message: String): ProductMediaUploadResult.Rejected =
        ProductMediaUploadResult.Rejected(ProductMediaValidationError(code, message))

    private fun String.isSafeFileName(): Boolean =
        isNotBlank() &&
            none { it.isISOControl() } &&
            "/" !in this &&
            "\\" !in this &&
            this != "." &&
            this != ".."

    private fun extensionMatchesContentType(fileName: String, contentType: String): Boolean {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return when (contentType) {
            "image/jpeg" -> extension in setOf("jpg", "jpeg")
            "image/png" -> extension == "png"
            "image/webp" -> extension == "webp"
            else -> false
        }
    }

    private fun sampleMatchesContentType(sample: ByteArray, contentType: String): Boolean =
        when (contentType) {
            "image/jpeg" -> sample.startsWith(0xFF, 0xD8, 0xFF)
            "image/png" -> sample.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            "image/webp" -> sample.size >= 12 &&
                sample.startsWithAscii("RIFF") &&
                sample.sliceArray(8 until 12).startsWithAscii("WEBP")
            else -> false
        }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { this[it].toInt() and 0xFF == bytes[it] }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it].toInt().toChar() == prefix[it] }

    private fun ByteArray.containsActiveContentMarker(): Boolean {
        val text = toString(Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        return ACTIVE_CONTENT_MARKERS.any { it in text }
    }

    private fun ByteArray.sha256Hash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val ALLOWED_IMAGE_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val ACTIVE_CONTENT_MARKERS = setOf("<script", "javascript:", "<?php", "<svg", "<html")
        const val MAX_IMAGE_SIZE_BYTES = 8L * 1024L * 1024L
        const val CLOCK_SKEW_SECONDS = 60L
    }
}
