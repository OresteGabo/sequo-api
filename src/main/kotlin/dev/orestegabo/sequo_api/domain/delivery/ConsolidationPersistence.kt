package dev.orestegabo.sequo_api.domain.delivery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "consolidation_manifests")
class ConsolidationManifestRecord(
    @Id val id: String,
    @Column(name = "order_id", nullable = false, unique = true) val orderId: String,
    @Column(name = "customer_id", nullable = false) val customerId: String,
    @Column(name = "seller_packages_json", nullable = false, length = 12000) var sellerPackagesJson: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) var status: ConsolidationStatus,
    @Column(name = "final_package_id") var finalPackageId: String? = null,
    @Column(name = "sequo_custody_at") var sequoCustodyAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Version @Column(nullable = false) var version: Long = 0,
)

interface ConsolidationManifestRepository : JpaRepository<ConsolidationManifestRecord, String> {
    fun findByOrderId(orderId: String): ConsolidationManifestRecord?
    fun findByStatusOrderByUpdatedAtAsc(status: ConsolidationStatus): List<ConsolidationManifestRecord>
}

data class FinalPackageDispatchResult(
    val manifest: SequoConsolidationManifest,
    val mission: DeliveryMissionSnapshot,
    val alreadyDispatched: Boolean,
)

data class ConsolidationTrackingSnapshot(
    val manifestId: String,
    val customerId: String,
    val status: ConsolidationStatus,
    val finalPackageId: String?,
    val tracking: DeliveryTrackingSnapshot?,
)

@Service
class ConsolidationPersistenceService(
    private val manifests: ConsolidationManifestRepository,
    private val missions: DeliveryMissionRepository,
    private val deliveryMissionService: DeliveryMissionService,
) {
    private val objectMapper = ObjectMapper()
    private val transitionService = SequoConsolidationService()

    @Transactional
    fun create(manifest: SequoConsolidationManifest, at: Instant = Instant.now()): SequoConsolidationManifest {
        require(manifests.findByOrderId(manifest.orderId) == null) { "A consolidation manifest already exists for this order." }
        return manifests.save(manifest.toRecord(at)).toDomain()
    }

    @Transactional(readOnly = true)
    fun get(manifestId: String): SequoConsolidationManifest? =
        manifests.findById(manifestId).orElse(null)?.toDomain()

    @Transactional(readOnly = true)
    fun findByOrderId(orderId: String): SequoConsolidationManifest? =
        manifests.findByOrderId(orderId)?.toDomain()

    @Transactional
    fun markSellerPackageReady(
        manifestId: String,
        subOrderId: String,
        merchantId: String,
        at: Instant = Instant.now(),
    ): SequoConsolidationManifest {
        val record = manifests.findById(manifestId).orElse(null)
            ?: throw IllegalArgumentException("Consolidation manifest was not found.")
        val manifest = record.toDomain()
        require(manifest.status == ConsolidationStatus.AwaitingSellerPackages) {
            "Seller packages can only be updated while the manifest is awaiting packages."
        }
        val packageEntry = manifest.sellerPackages.firstOrNull { it.subOrderId == subOrderId }
            ?: throw IllegalArgumentException("Seller package was not found in this manifest.")
        require(packageEntry.merchantId == merchantId) { "This merchant cannot update another merchant's package." }
        if (packageEntry.ready) return manifest

        val updated = manifest.copy(
            sellerPackages = manifest.sellerPackages.map {
                if (it.subOrderId == subOrderId) it.copy(ready = true) else it
            }
        )
        val readyManifest = if (updated.allSellerPackagesReady) {
            transitionService.transition(
                ConsolidationTransitionRequest(updated, ConsolidationEvent.SellerPackageReady, at)
            ).manifest
        } else {
            updated
        }
        record.apply(readyManifest, at)
        manifests.save(record)
        return readyManifest
    }

    @Transactional
    fun markSellerPackageCollected(
        manifestId: String,
        subOrderId: String,
        at: Instant = Instant.now(),
    ): SequoConsolidationManifest {
        val record = manifests.findById(manifestId).orElse(null)
            ?: throw IllegalArgumentException("Consolidation manifest was not found.")
        val manifest = record.toDomain()
        require(manifest.status == ConsolidationStatus.ReadyForSequoPickup) {
            "Seller packages can only be collected when the manifest is ready for pickup."
        }
        require(manifest.sellerPackages.any { it.subOrderId == subOrderId }) {
            "Seller package was not found in this manifest."
        }
        val updated = manifest.copy(
            sellerPackages = manifest.sellerPackages.map {
                if (it.subOrderId == subOrderId) it.copy(collected = true) else it
            }
        )
        val custody = if (updated.allSellerPackagesCollected) {
            transitionService.transition(
                ConsolidationTransitionRequest(updated, ConsolidationEvent.SequoCollectsAllPackages, at)
            ).manifest
        } else {
            updated
        }
        record.apply(custody, at)
        manifests.save(record)
        return custody
    }

    @Transactional
    fun markSellerPackageCollectedForOrder(
        orderId: String,
        subOrderId: String,
        at: Instant = Instant.now(),
    ): SequoConsolidationManifest {
        val manifest = manifests.findByOrderId(orderId)
            ?: throw IllegalArgumentException("Consolidation manifest was not found for this order.")
        return markSellerPackageCollected(manifest.id, subOrderId, at)
    }

    @Transactional
    fun transition(request: ConsolidationTransitionRequest): ConsolidationTransition {
        val current = manifests.findById(request.manifest.manifestId).orElse(null)
            ?: return ConsolidationTransition(false, request.manifest, "Consolidation manifest was not found.")
        val transition = transitionService.transition(request.copy(manifest = current.toDomain()))
        if (!transition.accepted) return transition
        current.apply(transition.manifest, request.at)
        manifests.save(current)
        return transition
    }

    @Transactional
    fun dispatchFinalPackage(
        manifestId: String,
        customerDeliveryFeeCfa: Int = 0,
        courierFeeCfa: Int = 0,
        at: Instant = Instant.now(),
    ): FinalPackageDispatchResult {
        require(customerDeliveryFeeCfa >= 0) { "Customer delivery fee cannot be negative." }
        require(courierFeeCfa >= 0) { "Courier fee cannot be negative." }
        val record = manifests.findById(manifestId).orElse(null)
            ?: throw IllegalArgumentException("Consolidation manifest was not found.")
        val manifest = record.toDomain()
        require(manifest.status == ConsolidationStatus.Consolidated || manifest.status == ConsolidationStatus.Dispatched) {
            "A final package can only be dispatched after consolidation."
        }
        val deliveryCode = finalDeliveryCode(manifest.manifestId)
        val existingMission = missions.findByDeliveryCode(deliveryCode)
        val mission = existingMission?.toSnapshot() ?: deliveryMissionService.create(
            CreateDeliveryMissionCommand(
                deliveryCode = deliveryCode,
                orderId = manifest.orderId,
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
                customerDeliveryFeeCfa = customerDeliveryFeeCfa,
                courierFeeCfa = courierFeeCfa,
            ),
            at,
        )
        if (manifest.status == ConsolidationStatus.Consolidated) {
            val transition = transitionService.transition(
                ConsolidationTransitionRequest(manifest, ConsolidationEvent.DispatchesFinalPackage, at)
            )
            require(transition.accepted) { transition.reason }
            record.apply(transition.manifest, at)
            manifests.save(record)
            return FinalPackageDispatchResult(transition.manifest, mission, alreadyDispatched = false)
        }
        return FinalPackageDispatchResult(manifest, mission, alreadyDispatched = true)
    }

    @Transactional(readOnly = true)
    fun trackFinalPackage(manifestId: String): ConsolidationTrackingSnapshot? {
        val manifest = manifests.findById(manifestId).orElse(null)?.toDomain() ?: return null
        return ConsolidationTrackingSnapshot(
            manifestId = manifest.manifestId,
            customerId = manifest.customerId,
            status = manifest.status,
            finalPackageId = manifest.finalPackageId,
            tracking = missions.findByDeliveryCode(finalDeliveryCode(manifest.manifestId))?.toTrackingSnapshot(),
        )
    }

    private fun SequoConsolidationManifest.toRecord(at: Instant) = ConsolidationManifestRecord(
        id = manifestId,
        orderId = orderId,
        customerId = customerId,
        sellerPackagesJson = objectMapper.writeValueAsString(sellerPackages.map {
            mapOf("subOrderId" to it.subOrderId, "merchantId" to it.merchantId, "packageCount" to it.packageCount, "ready" to it.ready, "collected" to it.collected)
        }),
        status = status,
        finalPackageId = finalPackageId,
        sequoCustodyAt = sequoCustodyAt,
        createdAt = at,
        updatedAt = at,
    )

    private fun ConsolidationManifestRecord.toDomain() = SequoConsolidationManifest(
        manifestId = id,
        orderId = orderId,
        customerId = customerId,
        sellerPackages = objectMapper.readTree(sellerPackagesJson).map { it.toSellerPackage() },
        status = status,
        finalPackageId = finalPackageId,
        sequoCustodyAt = sequoCustodyAt,
    )

    private fun JsonNode.toSellerPackage() = ConsolidationSellerPackage(
        subOrderId = requiredText("subOrderId"),
        merchantId = requiredText("merchantId"),
        packageCount = requiredInt("packageCount"),
        ready = required("ready").asBoolean(),
        collected = get("collected")?.asBoolean() ?: false,
    )

    private fun ConsolidationManifestRecord.apply(manifest: SequoConsolidationManifest, at: Instant) {
        sellerPackagesJson = objectMapper.writeValueAsString(manifest.sellerPackages.map {
            mapOf("subOrderId" to it.subOrderId, "merchantId" to it.merchantId, "packageCount" to it.packageCount, "ready" to it.ready, "collected" to it.collected)
        })
        status = manifest.status
        finalPackageId = manifest.finalPackageId
        sequoCustodyAt = manifest.sequoCustodyAt
        updatedAt = at
    }
}

private fun finalDeliveryCode(manifestId: String): String =
    "SEQ-FINAL-${MessageDigest.getInstance("SHA-256")
        .digest(manifestId.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)}"

private fun JsonNode.requiredText(name: String): String =
    get(name)?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
        ?: throw IllegalArgumentException("Consolidation package field $name is required.")

private fun JsonNode.requiredInt(name: String): Int =
    get(name)?.takeIf { it.isInt && it.intValue() > 0 }?.intValue()
        ?: throw IllegalArgumentException("Consolidation package field $name must be a positive integer.")
