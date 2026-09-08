package dev.orestegabo.sequo_api.domain.catalog

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductMediaPolicyServiceTest {

    private val now = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun acceptsLiveCameraCaptureWithMetadataModerationAndStorageSnapshot() {
        val storage = RecordingStorage()
        val service = service(storage = storage)

        val result = service.accept(liveCapture())

        assertTrue(result is ProductMediaUploadResult.Accepted)
        assertEquals(ProductMediaSource.LiveCamera, result.snapshot.source)
        assertEquals("products/attieke-1/live-photo.webp", result.snapshot.storageKey)
        assertEquals("device-123", result.snapshot.deviceId)
        assertEquals(webpSample().sha256Hash(), result.snapshot.contentHash)
        assertEquals(ProductMediaModerationStatus.Approved, result.snapshot.moderationStatus)
        assertEquals(1, storage.stored.size)
    }

    @Test
    fun allowsGenericCatalogReferencesOnlyForGenericSealedProducts() {
        val service = service()

        val accepted = service.accept(
            ProductMediaSubmission.GenericCatalogReference(
                productId = "sealed-rice-1",
                productKind = CatalogProductKind.GenericSealedItem,
                catalogImageId = "catalog-rice-photo",
                sourceUrl = "https://cdn.sequo.example/catalog/rice.webp",
            )
        )
        val rejected = service.accept(
            ProductMediaSubmission.GenericCatalogReference(
                productId = "attieke-1",
                productKind = CatalogProductKind.SellerSpecific,
                catalogImageId = "catalog-attieke-photo",
            )
        )

        assertTrue(accepted is ProductMediaUploadResult.Accepted)
        assertEquals("catalog-rice-photo", accepted.snapshot.catalogImageId)
        assertTrue(rejected is ProductMediaUploadResult.Rejected)
        assertEquals("generic_reference_not_allowed", rejected.error.code)
    }

    @Test
    fun rejectsGalleryAndWebImagesAsClientUploadSources() {
        val service = service()

        val gallery = service.accept(
            ProductMediaSubmission.RejectedClientSource(
                productId = "attieke-1",
                productKind = CatalogProductKind.SellerSpecific,
                source = ProductMediaSource.GalleryUpload,
            )
        )
        val webImage = service.accept(
            ProductMediaSubmission.RejectedClientSource(
                productId = "attieke-1",
                productKind = CatalogProductKind.SellerSpecific,
                source = ProductMediaSource.WebImage,
            )
        )

        assertTrue(gallery is ProductMediaUploadResult.Rejected)
        assertEquals("unsupported_media_source", gallery.error.code)
        assertTrue(webImage is ProductMediaUploadResult.Rejected)
        assertEquals("unsupported_media_source", webImage.error.code)
    }

    @Test
    fun rejectsInvalidLiveCaptureBeforeStorage() {
        val storage = RecordingStorage()
        val service = service(storage = storage)

        val result = service.accept(
            liveCapture(
                contentType = "application/pdf",
            )
        )

        assertTrue(result is ProductMediaUploadResult.Rejected)
        assertEquals("unsupported_content_type", result.error.code)
        assertEquals(0, storage.stored.size)
    }

    @Test
    fun rejectsJavaScriptRenamedAsImageBeforeStorage() {
        val storage = RecordingStorage()
        val service = service(storage = storage)

        val result = service.accept(
            liveCapture(
                fileName = "menu-photo.jpg",
                contentType = "image/jpeg",
                contentBytes = "alert('xss')".encodeToByteArray(),
            )
        )

        assertTrue(result is ProductMediaUploadResult.Rejected)
        assertEquals("media_signature_mismatch", result.error.code)
        assertEquals(0, storage.stored.size)
    }

    @Test
    fun rejectsImageWhenExtensionDoesNotMatchDeclaredContentType() {
        val storage = RecordingStorage()
        val service = service(storage = storage)

        val result = service.accept(
            liveCapture(
                fileName = "menu-photo.js",
                contentType = "image/jpeg",
                contentBytes = jpegSample(),
            )
        )

        assertTrue(result is ProductMediaUploadResult.Rejected)
        assertEquals("content_type_extension_mismatch", result.error.code)
        assertEquals(0, storage.stored.size)
    }

    @Test
    fun rejectsPathTraversalFileNamesBeforeStorage() {
        val storage = RecordingStorage()
        val service = service(storage = storage)

        val result = service.accept(
            liveCapture(fileName = "../menu-photo.webp")
        )

        assertTrue(result is ProductMediaUploadResult.Rejected)
        assertEquals("unsafe_file_name", result.error.code)
        assertEquals(0, storage.stored.size)
    }

    @Test
    fun rejectsImagePolyglotWithActiveScriptMarker() {
        val storage = RecordingStorage()
        val service = service(storage = storage)
        val polyglot = jpegSample() + "<script>alert('xss')</script>".encodeToByteArray()

        val result = service.accept(
            liveCapture(
                fileName = "menu-photo.jpg",
                contentType = "image/jpeg",
                contentBytes = polyglot,
            )
        )

        assertTrue(result is ProductMediaUploadResult.Rejected)
        assertEquals("active_content_detected", result.error.code)
        assertEquals(0, storage.stored.size)
    }

    @Test
    fun rejectsUploadWhenDeclaredSizeDoesNotMatchBytes() {
        val storage = RecordingStorage()
        val service = service(storage = storage)

        val result = service.accept(
            liveCapture(sizeBytes = 99)
        )

        assertTrue(result is ProductMediaUploadResult.Rejected)
        assertEquals("media_size_mismatch", result.error.code)
        assertEquals(0, storage.stored.size)
    }

    @Test
    fun rejectsUploadWhenClientHashDoesNotMatchBytes() {
        val storage = RecordingStorage()
        val service = service(storage = storage)

        val result = service.accept(
            liveCapture(contentHash = "sha256:not-the-uploaded-file")
        )

        assertTrue(result is ProductMediaUploadResult.Rejected)
        assertEquals("content_hash_mismatch", result.error.code)
        assertEquals(0, storage.stored.size)
    }

    @Test
    fun rejectsUnsafeGenericCatalogSourceUrl() {
        val service = service()

        val result = service.accept(
            ProductMediaSubmission.GenericCatalogReference(
                productId = "sealed-rice-1",
                productKind = CatalogProductKind.GenericSealedItem,
                catalogImageId = "catalog-rice-photo",
                sourceUrl = "javascript:alert(1)",
            )
        )

        assertTrue(result is ProductMediaUploadResult.Rejected)
        assertEquals("unsafe_catalog_source_url", result.error.code)
    }

    @Test
    fun rejectsLiveCaptureWhenModerationFails() {
        val storage = RecordingStorage()
        val service = service(
            storage = storage,
            moderator = FixedModerator(
                ProductMediaModerationResult(
                    status = ProductMediaModerationStatus.Rejected,
                    reason = "Image is not related to the product.",
                )
            )
        )

        val result = service.accept(liveCapture())

        assertTrue(result is ProductMediaUploadResult.Rejected)
        assertEquals("media_moderation_failed", result.error.code)
        assertEquals(0, storage.stored.size)
    }

    private fun service(
        storage: RecordingStorage = RecordingStorage(),
        moderator: ProductMediaModerator = FixedModerator(ProductMediaModerationResult(ProductMediaModerationStatus.Approved)),
    ): ProductMediaPolicyService =
        ProductMediaPolicyService(
            storage = storage,
            moderator = moderator,
            now = { now },
        )

    private fun liveCapture(
        fileName: String = "live-photo.webp",
        contentType: String = "image/webp",
        contentBytes: ByteArray = webpSample(),
        sizeBytes: Long = contentBytes.size.toLong(),
        contentHash: String = contentBytes.sha256Hash(),
    ): ProductMediaSubmission.LiveCameraCapture =
        ProductMediaSubmission.LiveCameraCapture(
            productId = "attieke-1",
            productKind = CatalogProductKind.SellerSpecific,
            fileName = fileName,
            contentType = contentType,
            sizeBytes = sizeBytes,
            contentBytes = contentBytes,
            metadata = LiveCameraMetadata(
                capturedAt = Instant.parse("2026-09-08T09:59:30Z"),
                deviceId = "device-123",
                contentHash = contentHash,
            ),
        )

    private fun jpegSample(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    private fun webpSample(): ByteArray =
        byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x18, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x56, 0x50, 0x38, 0x20,
        )

    private fun ByteArray.sha256Hash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private class RecordingStorage : ProductMediaStorage {
        val stored = mutableListOf<ProductMediaSubmission.LiveCameraCapture>()

        override fun storeLiveCameraCapture(submission: ProductMediaSubmission.LiveCameraCapture): StoredProductMedia {
            stored += submission
            return StoredProductMedia(
                storageKey = "products/${submission.productId}/${submission.fileName}",
                publicUrl = "https://cdn.sequo.example/products/${submission.productId}/${submission.fileName}",
            )
        }
    }

    private class FixedModerator(
        private val result: ProductMediaModerationResult,
    ) : ProductMediaModerator {
        override fun moderate(submission: ProductMediaSubmission.LiveCameraCapture): ProductMediaModerationResult = result
    }
}
