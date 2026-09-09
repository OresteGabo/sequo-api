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
    @Column(name = "seller_packages_json", nullable = false, length = 12000) val sellerPackagesJson: String,
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

@Service
class ConsolidationPersistenceService(
    private val manifests: ConsolidationManifestRepository,
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
    fun transition(request: ConsolidationTransitionRequest): ConsolidationTransition {
        val current = manifests.findById(request.manifest.manifestId).orElse(null)
            ?: return ConsolidationTransition(false, request.manifest, "Consolidation manifest was not found.")
        val transition = transitionService.transition(request.copy(manifest = current.toDomain()))
        if (!transition.accepted) return transition
        current.apply(transition.manifest, request.at)
        manifests.save(current)
        return transition
    }

    private fun SequoConsolidationManifest.toRecord(at: Instant) = ConsolidationManifestRecord(
        id = manifestId,
        orderId = orderId,
        customerId = customerId,
        sellerPackagesJson = objectMapper.writeValueAsString(sellerPackages.map {
            mapOf("subOrderId" to it.subOrderId, "merchantId" to it.merchantId, "packageCount" to it.packageCount, "ready" to it.ready)
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
    )

    private fun ConsolidationManifestRecord.apply(manifest: SequoConsolidationManifest, at: Instant) {
        status = manifest.status
        finalPackageId = manifest.finalPackageId
        sequoCustodyAt = manifest.sequoCustodyAt
        updatedAt = at
    }
}

private fun JsonNode.requiredText(name: String): String =
    get(name)?.takeIf { it.isTextual && it.textValue().isNotBlank() }?.textValue()
        ?: throw IllegalArgumentException("Consolidation package field $name is required.")

private fun JsonNode.requiredInt(name: String): Int =
    get(name)?.takeIf { it.isInt && it.intValue() > 0 }?.intValue()
        ?: throw IllegalArgumentException("Consolidation package field $name must be a positive integer.")
