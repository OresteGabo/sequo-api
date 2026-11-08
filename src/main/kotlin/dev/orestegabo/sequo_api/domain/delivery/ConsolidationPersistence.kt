package dev.orestegabo.sequo_api.domain.delivery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.order.readPendingRequest
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
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
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_consolidation_manifests_order"))
    val order: CustomerOrderRecord? = null,
    @Column(name = "customer_id", nullable = false) val customerId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_consolidation_manifests_customer"))
    val customer: User? = null,
    @Column(name = "seller_packages_json", nullable = false, length = 12000) var sellerPackagesJson: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64) var status: ConsolidationStatus,
    @Column(name = "final_package_id") var finalPackageId: String? = null,
    @Column(name = "sequo_custody_at") var sequoCustodyAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "approved_by_user_id") var approvedByUserId: String? = null,
    @Column(name = "cooperative_id") var cooperativeId: String? = null,
    @Column(name = "history_json", columnDefinition = "text") var historyJson: String? = null,
    @Version @Column(nullable = false) var version: Long = 0,
)

interface ConsolidationManifestRepository : JpaRepository<ConsolidationManifestRecord, String> {
    @org.springframework.data.jpa.repository.Query("select m.orderId from ConsolidationManifestRecord m where m.id = :id")
    fun orderReference(id: String): String?
    @org.springframework.data.jpa.repository.Query("select m.id from ConsolidationManifestRecord m where m.orderId = :orderId")
    fun manifestReference(orderId: String): String?
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from ConsolidationManifestRecord m where m.id = :id")
    fun lockForAction(id: String): ConsolidationManifestRecord?

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
    private val orders: dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository,
    private val subOrders: MerchantSubOrderRepository,
    private val cooperatives: dev.orestegabo.sequo_api.domain.platform.MerchantCooperativeRepository,
    private val members: dev.orestegabo.sequo_api.domain.platform.MerchantCooperativeMemberRepository,
    private val entityManager: jakarta.persistence.EntityManager,
    private val pending: dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRepository,
    private val publisher: org.springframework.context.ApplicationEventPublisher,
) {
    private val objectMapper = ObjectMapper()
    private val transitionService = SequoConsolidationService()

    @Transactional(readOnly = true)
    fun list(): List<SequoConsolidationManifest> = manifests.findAll().sortedByDescending { it.updatedAt }.map { it.toDomain() }

    @Transactional
    fun createForOrder(manifestId: String, orderId: String, actor: String, cooperativeId: String?): SequoConsolidationManifest {
        val order = requireNotNull(orders.lockForCompletion(orderId)) { "Order is unavailable." }
        manifests.findByOrderId(orderId)?.let {
            require(it.cooperativeId == cooperativeId) { "Grouping retry targets a different cooperative." }
            return it.toDomain()
        }
        require(order.paymentStatus == dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus.Validated && order.orderStatus == dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT) { "Only a paid active order may be grouped." }
        require(missions.findByOrderIdOrderByCreatedAtAsc(orderId).isEmpty()) { "Group before courier dispatch; active missions require operational review." }
        require(order.route != dev.orestegabo.sequo_api.domain.order.OrderRoute.PointDeRelai) { "Relay routing cannot be silently changed to consolidation." }
        val parts = subOrders.findByOrderId(orderId)
        require(parts.size >= 2 && parts.all { it.status == MerchantSubOrderStatus.PACKED_READY && it.packageCount > 0 }) { "At least two merchants must confirm their prepared package counts before grouping." }
        cooperativeId?.let { id ->
            val cooperative = requireNotNull(cooperatives.lockForReview(id)) { "Cooperative is unavailable." }
            require(cooperative.status == dev.orestegabo.sequo_api.domain.platform.CooperativeApprovalStatus.APPROVED) { "Cooperative requires SEQUO approval." }
            val merchantIds = members.findByCooperativeIdOrderByCreatedAtAsc(id).map { it.merchantId }.toSet()
            require(parts.all { it.merchantId in merchantIds }) { "Every merchant must belong to the approved cooperative." }
        }
        val now = Instant.now()
        val result = create(SequoConsolidationManifest(manifestId, orderId, order.customerId,
            parts.map { ConsolidationSellerPackage(requireNotNull(it.id), it.merchantId, it.packageCount, true) },
            status = ConsolidationStatus.ReadyForSequoPickup, approvedByUserId = actor, cooperativeId = cooperativeId,
            history = listOf(OperatorHistoryEntry("APPROVE_GROUP", actor, "SEQUO validated grouping of prepared merchant packages.", now))), now)
        order.route = dev.orestegabo.sequo_api.domain.order.OrderRoute.GroupedSequo
        order.requiresConsolidation = true; order.customerFacingStatus = "Awaiting SEQUO consolidation"; order.updatedAt = now
        orders.save(order)
        return result
    }

    private fun lock(id: String): ConsolidationManifestRecord {
        val orderId = requireNotNull(manifests.orderReference(id)) { "Consolidation manifest was not found." }
        orders.lockForCompletion(orderId)
        return requireNotNull(manifests.lockForAction(id)) { "Consolidation manifest was not found." }
    }

    private fun recordHistory(record: ConsolidationManifestRecord, action: String, actor: String?, message: String, at: Instant) {
        val history = readHistory(record.historyJson)
        record.historyJson = writeHistory(history + OperatorHistoryEntry(action, actor, message, at))
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun recordCourierPickupForOrder(orderId: String, subOrderId: String, actor: String?, at: Instant) {
        val id = manifests.manifestReference(orderId) ?: return
        val record = lock(id)
        if (readHistory(record.historyJson).any { it.action == "COURIER_PICKUP:$subOrderId" }) return
        recordHistory(record, "COURIER_PICKUP:$subOrderId", actor, "Merchant parcel picked up; SEQUO receipt still required.", at)
        record.updatedAt = at
        manifests.save(record)
    }

    @Transactional
    fun create(manifest: SequoConsolidationManifest, at: Instant = Instant.now()): SequoConsolidationManifest {
        require(manifests.findByOrderId(manifest.orderId) == null) { "A consolidation manifest already exists for this order." }
        require(!manifests.existsById(manifest.manifestId)) { "Manifest ID is already used by another order." }
        val record = manifest.toRecord(at)
        entityManager.persist(record)
        entityManager.flush()
        return record.toDomain()
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
        val record = lock(manifestId)
        val manifest = record.toDomain()
        val packageEntry = manifest.sellerPackages.firstOrNull { it.subOrderId == subOrderId }
            ?: throw IllegalArgumentException("Seller package was not found in this manifest.")
        require(packageEntry.merchantId == merchantId) { "This merchant cannot update another merchant's package." }
        if (packageEntry.ready) return manifest
        require(manifest.status == ConsolidationStatus.AwaitingSellerPackages) {
            "Seller packages can only be updated while the manifest is awaiting packages."
        }
        subOrders.findById(subOrderId).orElse(null)?.let { part ->
            require(part.orderId == manifest.orderId && part.merchantId == merchantId &&
                part.status == MerchantSubOrderStatus.PACKED_READY && part.packageCount == packageEntry.packageCount) {
                "Merchant preparation and the recorded package count must be confirmed first."
            }
        }

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
        actorUserId: String? = null,
        metadata: String = "SEQUO confirmed package receipt.",
    ): SequoConsolidationManifest {
        val record = lock(manifestId)
        val manifest = record.toDomain()
        if (manifest.sellerPackages.firstOrNull { it.subOrderId == subOrderId }?.collected == true) return manifest
        require(manifest.status == ConsolidationStatus.ReadyForSequoPickup) {
            "Seller packages can only be collected when the manifest is ready for pickup."
        }
        require(manifest.sellerPackages.any { it.subOrderId == subOrderId }) {
            "Seller package was not found in this manifest."
        }
        if (actorUserId != null) {
            val receipt = requireNotNull(missions.lockByMerchantSubOrderId(subOrderId)) { "Collection mission is missing." }
            require(receipt.orderId == manifest.orderId && receipt.destinationType == DeliveryMissionRecordDestination.SEQUO_CONSOLIDATION &&
                receipt.status == DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER && receipt.pickupAt != null) { "Assigned courier pickup is required before SEQUO receipt." }
            receipt.status = DeliveryMissionRecordStatus.HANDED_TO_SEQUO
            receipt.dropoffProofMetadata = metadata; receipt.dropoffProofActorId = actorUserId; receipt.updatedAt = at
            missions.save(receipt)
            publisher.publishEvent(dev.orestegabo.sequo_api.domain.settlement.DeliverySettlementReadyEvent(requireNotNull(receipt.id)))
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
        recordHistory(record, "SEQUO_RECEIPT:$subOrderId", actorUserId, metadata, at)
        record.apply(custody, at)
        manifests.save(record)
        return record.toDomain()
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
    fun transition(request: ConsolidationTransitionRequest, actorUserId: String? = null): ConsolidationTransition {
        val current = lock(request.manifest.manifestId)
        if (request.event == ConsolidationEvent.SequoCollectsAllPackages && !current.toDomain().allSellerPackagesCollected)
            return ConsolidationTransition(false, current.toDomain(), "Every package needs its own SEQUO receipt.")
        if (request.event == ConsolidationEvent.DispatchesFinalPackage && actorUserId != null)
            return ConsolidationTransition(false, current.toDomain(), "Use final dispatch to create the delivery mission atomically.")
        if (request.event == ConsolidationEvent.SequoCreatesFinalPackage && current.status in setOf(ConsolidationStatus.Consolidated, ConsolidationStatus.Dispatched) && current.finalPackageId == request.finalPackageId)
            return ConsolidationTransition(true, current.toDomain(), "Existing sealed package retained.")
        val transition = transitionService.transition(request.copy(manifest = current.toDomain()))
        if (!transition.accepted) return transition
        recordHistory(current, request.event.name, actorUserId, transition.reason, request.at)
        current.apply(transition.manifest, request.at)
        manifests.save(current)
        return transition.copy(manifest = current.toDomain())
    }

    @Transactional
    fun dispatchFinalPackage(
        manifestId: String,
        customerDeliveryFeeCfa: Int = 0,
        courierFeeCfa: Int = 0,
        at: Instant = Instant.now(),
        actorUserId: String? = null,
    ): FinalPackageDispatchResult {
        require(customerDeliveryFeeCfa >= 0) { "Customer delivery fee cannot be negative." }
        require(courierFeeCfa >= 0) { "Courier fee cannot be negative." }
        val record = lock(manifestId)
        val manifest = record.toDomain()
        require(manifest.status == ConsolidationStatus.Consolidated || manifest.status == ConsolidationStatus.Dispatched) {
            "A final package can only be dispatched after consolidation."
        }
        if (actorUserId != null && manifest.status != ConsolidationStatus.Dispatched) {
            val order = orders.findById(manifest.orderId).orElseThrow()
            require(order.paymentStatus == dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus.Validated &&
                order.orderStatus == dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT) {
                "Only a paid active order may be dispatched."
            }
            require(manifest.allSellerPackagesCollected) { "Every package needs its own SEQUO receipt." }
        }
        val deliveryCode = finalDeliveryCode(manifest.manifestId)
        val existingMission = missions.findByDeliveryCode(deliveryCode)
        val order = orders.findById(manifest.orderId).orElse(null)
        val checkout = order?.let { pending.findById(it.checkoutId).orElse(null)?.requestPayload }?.let { objectMapper.readPendingRequest(it) }
        val mission = existingMission?.toSnapshot() ?: deliveryMissionService.create(
            CreateDeliveryMissionCommand(
                deliveryCode = deliveryCode,
                orderId = manifest.orderId,
                deliveryMode = checkout?.deliveryOption?.missionMode ?: DeliveryMissionRecordMode.STANDARD,
                deliveryOption = checkout?.deliveryOption,
                subscriberPriority = checkout?.subscriberPriority ?: false,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
                customerDeliveryFeeCfa = if (actorUserId != null) requireNotNull(order).deliveryFeeCfa else customerDeliveryFeeCfa,
                courierFeeCfa = courierFeeCfa,
            ),
            at,
        )
        if (manifest.status == ConsolidationStatus.Consolidated) {
            val transition = transitionService.transition(
                ConsolidationTransitionRequest(manifest, ConsolidationEvent.DispatchesFinalPackage, at)
            )
            require(transition.accepted) { transition.reason }
            recordHistory(record, "FINAL_DISPATCH", actorUserId, "Final delivery mission ${mission.id} created for sealed package ${manifest.finalPackageId}.", at)
            record.apply(transition.manifest, at)
            manifests.save(record)
            return FinalPackageDispatchResult(record.toDomain(), mission, alreadyDispatched = false)
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
        approvedByUserId = approvedByUserId,
        cooperativeId = cooperativeId,
        historyJson = writeHistory(history),
    )

    private fun ConsolidationManifestRecord.toDomain() = SequoConsolidationManifest(
        manifestId = id,
        orderId = orderId,
        customerId = customerId,
        sellerPackages = objectMapper.readTree(sellerPackagesJson).map { it.toSellerPackage() },
        status = status,
        finalPackageId = finalPackageId,
        sequoCustodyAt = sequoCustodyAt,
        approvedByUserId = approvedByUserId,
        cooperativeId = cooperativeId,
        history = readHistory(historyJson),
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

internal fun writeHistory(entries: List<OperatorHistoryEntry>): String = ObjectMapper().writeValueAsString(entries.map {
    mapOf("action" to it.action, "actorUserId" to it.actorUserId, "metadata" to it.metadata, "createdAt" to it.createdAt.toString())
})
internal fun readHistory(payload: String?): List<OperatorHistoryEntry> = payload?.let { ObjectMapper().readTree(it).map { n ->
    OperatorHistoryEntry(n["action"].asText(), n.path("actorUserId").takeIf { it.isTextual }?.asText(), n["metadata"].asText(), Instant.parse(n["createdAt"].asText()))
} }.orEmpty()
