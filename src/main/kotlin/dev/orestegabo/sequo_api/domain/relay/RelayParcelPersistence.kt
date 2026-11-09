package dev.orestegabo.sequo_api.domain.relay

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.delivery.DeliveryMission
import dev.orestegabo.sequo_api.domain.hub.RelayLockerRecord
import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import dev.orestegabo.sequo_api.domain.notification.NotificationWorkflowEvent
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import dev.orestegabo.sequo_api.domain.returns.ReturnRequestRecord
import dev.orestegabo.sequo_api.domain.settlement.RelayStorageFeeLedgerCommand
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import dev.orestegabo.sequo_api.domain.settlement.SettlementResult
import jakarta.persistence.Column
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.LockModeType
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "relay_parcels")
class RelayParcelRecord(
    @Id val id: String,
    @Column(name = "relay_point_id", nullable = false) val relayPointId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_point_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_parcels_relay_point"))
    val relayPoint: RelayPointRecord? = null,
    @Column(name = "locker_id") val lockerId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locker_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_parcels_locker"))
    val locker: RelayLockerRecord? = null,
    @Column(name = "order_id") val orderId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_parcels_order"))
    val order: CustomerOrderRecord? = null,
    @Column(name = "delivery_mission_id") val deliveryMissionId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_mission_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_parcels_delivery_mission"))
    val deliveryMission: DeliveryMission? = null,
    @Column(name = "return_id") val returnId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_parcels_return"))
    val returnRequest: ReturnRequestRecord? = null,
    @Column(name = "deposit_code", unique = true, length = 64) val depositCode: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val category: RelayParcelCategory,
    @Convert(converter = RelayParcelStatusConverter::class) @Column(nullable = false) var status: RelayParcelStatus,
    @Column(name = "deposited_at") var depositedAt: java.time.Instant? = null,
    @Column(name = "fee_starts_at", updatable = false) val feeStartsAt: java.time.Instant? = null,
    @Column(name = "picked_up_at") var pickedUpAt: java.time.Instant? = null,
    @Column(name = "collected_at") var collectedAt: java.time.Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: java.time.Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: java.time.Instant,
    @Version @Column(nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "relay_pickup_codes")
class RelayPickupCodeRecord(
    @Id val id: String,
    @Column(name = "relay_parcel_id", nullable = false) val relayParcelId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_parcel_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_pickup_codes_parcel"))
    val relayParcel: RelayParcelRecord? = null,
    @Column(name = "customer_delivery_version") val customerDeliveryVersion: Int? = null,
    @Column(name = "code_hash", nullable = false) val codeHash: String,
    @Column(name = "qr_nonce_hash") val qrNonceHash: String? = null,
    @Column(name = "identity_check_required", nullable = false) val identityCheckRequired: Boolean = true,
    @Column(name = "expires_at", nullable = false) val expiresAt: java.time.Instant,
    @Column(name = "used_at") var usedAt: java.time.Instant? = null,
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int = 0,
    @Column(name = "created_at", nullable = false) val createdAt: java.time.Instant,
)

@Entity
@Table(name = "relay_custody_events")
class RelayCustodyEventRecord(
    @Id val id: String,
    @Column(name = "relay_parcel_id", nullable = false) val relayParcelId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_parcel_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_custody_events_parcel"))
    val relayParcel: RelayParcelRecord? = null,
    @Column(name = "actor_user_id") val actorUserId: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_custody_events_actor"))
    val actor: User? = null,
    @Convert(converter = RelayCustodyEventTypeConverter::class) @Column(name = "event_type", nullable = false) val type: RelayCustodyEventType,
    @Column(nullable = false) val metadata: String,
    @Column(name = "idempotency_key") val idempotencyKey: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: java.time.Instant,
)

@Entity
@Table(name = "relay_storage_fee_assessments")
class RelayStorageFeeAssessmentRecord(
    @Id val id: String,
    @Column(name = "relay_parcel_id", nullable = false) val relayParcelId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_parcel_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_storage_fee_assessments_parcel"))
    val relayParcel: RelayParcelRecord? = null,
    @Column(name = "relay_point_id", nullable = false) val relayPointId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_point_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_storage_fee_assessments_relay_point"))
    val relayPoint: RelayPointRecord? = null,
    @Column(name = "daily_fee_cfa", nullable = false) val dailyFeeCfa: Int,
    @Column(name = "chargeable_days", nullable = false) var chargeableDays: Long,
    @Column(name = "total_fee_cfa", nullable = false) var totalFeeCfa: Int,
    @Column(name = "last_increment_cfa", nullable = false) var lastIncrementCfa: Int,
    @Column(name = "fee_starts_at", nullable = false) var feeStartsAt: java.time.Instant,
    @Column(name = "measured_until", nullable = false) var measuredUntil: java.time.Instant,
    @Column(name = "created_at", nullable = false) val createdAt: java.time.Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: java.time.Instant,
    @Version @Column(nullable = false) var version: Long = 0,
)

interface RelayParcelReference { val orderId: String?; val deliveryMissionId: String? }

interface RelayParcelRecordRepository : JpaRepository<RelayParcelRecord, String> {
    @org.springframework.data.jpa.repository.Query("select p.orderId as orderId, p.deliveryMissionId as deliveryMissionId from RelayParcelRecord p where p.id = :id")
    fun reference(id: String): RelayParcelReference?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from RelayParcelRecord p where p.relayPointId = :site order by p.id")
    fun lockForReview(site: String): List<RelayParcelRecord>

    fun findByOrderIdOrderByCreatedAtAsc(orderId: String): List<RelayParcelRecord>
    fun findByDeliveryMissionId(deliveryMissionId: String): List<RelayParcelRecord>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from RelayParcelRecord p where p.id = :id")
    fun lockForPickup(id: String): RelayParcelRecord?

    fun findByRelayPointIdOrderByUpdatedAtDesc(relayPointId: String): List<RelayParcelRecord>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByRelayPointIdAndFeeStartsAtLessThanOrderById(relayPointId: String, evaluatedAt: java.time.Instant): List<RelayParcelRecord>
    fun findByDepositCode(depositCode: String): RelayParcelRecord?
    fun findFirstByReturnIdAndRelayPointIdOrderByUpdatedAtDesc(returnId: String, relayPointId: String): RelayParcelRecord?
    fun findTopByRelayPointIdOrderByUpdatedAtDesc(relayPointId: String): RelayParcelRecord?
    fun countByRelayPointIdAndStatus(relayPointId: String, status: RelayParcelStatus): Long
    fun countByRelayPointIdAndStatusIn(relayPointId: String, statuses: Collection<RelayParcelStatus>): Long
    fun countByStatusIn(statuses: Collection<RelayParcelStatus>): Long
    fun countByReturnIdIsNotNullAndStatusIn(statuses: Collection<RelayParcelStatus>): Long
    fun findTop50ByStatusInOrderByUpdatedAtAsc(statuses: Collection<RelayParcelStatus>): List<RelayParcelRecord>
    @Query("select distinct p.relayPointId from RelayParcelRecord p")
    fun findDistinctRelayPointIds(): List<String>
}
interface RelayPickupCodeRecordRepository : JpaRepository<RelayPickupCodeRecord, String> {
    fun findFirstByRelayParcelIdOrderByCreatedAtDesc(relayParcelId: String): RelayPickupCodeRecord?
    fun findFirstByCodeHashOrQrNonceHash(codeHash: String, qrNonceHash: String): RelayPickupCodeRecord?
}
interface RelayCustodyEventRecordRepository : JpaRepository<RelayCustodyEventRecord, String> {
    fun findByRelayParcelIdOrderByCreatedAtAsc(relayParcelId: String): List<RelayCustodyEventRecord>
}
interface RelayStorageFeeAssessmentRecordRepository : JpaRepository<RelayStorageFeeAssessmentRecord, String> {
    fun findByRelayParcelId(relayParcelId: String): RelayStorageFeeAssessmentRecord?
    fun findByRelayPointIdOrderByUpdatedAtDesc(relayPointId: String): List<RelayStorageFeeAssessmentRecord>
    fun countByRelayPointIdAndTotalFeeCfaGreaterThan(relayPointId: String, totalFeeCfa: Int): Long
}

data class RelayStorageFeeAssessment(
    val id: String,
    val relayParcelId: String,
    val relayPointId: String,
    val dailyFeeCfa: Int,
    val chargeableDays: Long,
    val totalFeeCfa: Int,
    val lastIncrementCfa: Int,
    val feeStartsAt: java.time.Instant,
    val measuredUntil: java.time.Instant,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
)

@Service
class RelayParcelPersistenceService(
    private val parcels: RelayParcelRecordRepository,
    private val pickupCodes: RelayPickupCodeRecordRepository,
    private val events: RelayCustodyEventRecordRepository,
    private val storageFees: RelayStorageFeeAssessmentRecordRepository,
    private val entityManager: jakarta.persistence.EntityManager,
) {
    fun findParcel(id: String): RelayParcel? = parcels.findById(id).orElse(null)?.toDomain(
        events.findByRelayParcelIdOrderByCreatedAtAsc(id).map { it.toDomain() }
    )

    fun lockReviewParcels(site: String): List<RelayParcel> = parcels.lockForReview(site).map { requireNotNull(findParcel(it.id)) }

    fun lockParcel(id: String): RelayParcel? = parcels.lockForPickup(id)?.let { findParcel(id) }
    fun codeExists(id: String): Boolean = pickupCodes.existsById(id)
    fun eventExists(id: String): Boolean = events.existsById(id)
    fun saveRejectedCode(code: RelayPickupCode) { pickupCodes.save(code.toRecord()) }

    fun findPickupCode(parcelId: String): RelayPickupCode? = pickupCodes.findFirstByRelayParcelIdOrderByCreatedAtDesc(parcelId)?.toDomain()

    fun listParcels(relayPointId: String, status: RelayParcelStatus? = null): List<RelayParcel> =
        parcels.findByRelayPointIdOrderByUpdatedAtDesc(relayPointId)
            .asSequence()
            .filter { status == null || it.status == status }
            .map { it.toDomain() }
            .toList()

    fun lockParcelsForFeeAssessment(relayPointId: String, evaluatedAt: java.time.Instant): List<RelayParcel> =
        parcels.findByRelayPointIdAndFeeStartsAtLessThanOrderById(relayPointId, evaluatedAt).map { it.toDomain() }

    fun relayPointIds(): List<String> = parcels.findDistinctRelayPointIds()

    fun listStorageFeeAssessments(relayPointId: String): List<RelayStorageFeeAssessment> =
        storageFees.findByRelayPointIdOrderByUpdatedAtDesc(relayPointId).map { it.toDomain() }

    @Transactional
    fun save(parcel: RelayParcel): RelayParcel {
        val existing = parcels.findById(parcel.id).orElse(null)
        val record = existing?.apply {
            status = parcel.status
            depositedAt = parcel.depositedAt
            pickedUpAt = parcel.pickedUpAt
            collectedAt = parcel.collectedAt
            updatedAt = parcel.updatedAt
        } ?: parcel.toRecord()
        return parcels.save(record).toDomain()
    }

    @Transactional
    fun saveCreated(result: RelayParcelServiceResult.Accepted): RelayParcel = result.value.parcel.also { parcel ->
        entityManager.persist(parcel.toRecord())
        result.value.event?.let { entityManager.persist(it.toRecord()) }
    }

    @Transactional
    fun savePickupCode(result: RelayParcelServiceResult.Accepted): RelayPickupCode =
        requireNotNull(result.value.pickupCode) { "Accepted relay pickup result must contain a pickup code." }.also { code ->
            // Creation must insert: a concurrent caller must never merge over another site's ID.
            entityManager.persist(code.toRecord())
        }

    @Transactional
    fun saveVerification(result: RelayParcelServiceResult.Accepted): RelayParcel = result.value.parcel.also { parcel ->
        save(parcel)
        result.value.pickupCode?.let { pickupCodes.save(it.toRecord()) }
        result.value.event?.let { entityManager.persist(it.toRecord()) }
    }

    @Transactional
    fun saveProblem(parcel: RelayParcel, event: RelayCustodyEvent): RelayParcel = parcel.also {
        save(it)
        entityManager.persist(event.toRecord())
    }

    @Transactional
    fun saveReturnToSeller(parcel: RelayParcel, event: RelayCustodyEvent): RelayParcel = parcel.also {
        save(it)
        entityManager.persist(event.toRecord())
    }

    fun findStorageFeeAssessment(parcelId: String): RelayStorageFeeAssessment? =
        storageFees.findByRelayParcelId(parcelId)?.toDomain()

    @Transactional
    fun saveStorageFeeAssessment(
        parcel: RelayParcel,
        fee: RelayStorageFeeSnapshot,
        assessedAt: java.time.Instant,
    ): Pair<RelayStorageFeeAssessment, Int> {
        val id = "${parcel.id}:storage-fee"
        val existing = storageFees.findByRelayParcelId(parcel.id)
        val increment = fee.totalFeeCfa - (existing?.totalFeeCfa ?: 0)
        if (existing != null && increment <= 0) {
            return existing.toDomain().copy(lastIncrementCfa = 0) to 0
        }
        val saved = if (existing == null) {
            RelayStorageFeeAssessmentRecord(
                id = id,
                relayParcelId = parcel.id,
                relayPointId = parcel.relayPointId,
                dailyFeeCfa = fee.dailyFeeCfa,
                chargeableDays = fee.chargeableDays,
                totalFeeCfa = fee.totalFeeCfa,
                lastIncrementCfa = increment,
                feeStartsAt = fee.feeStartsAt,
                measuredUntil = fee.measuredUntil,
                createdAt = assessedAt,
                updatedAt = assessedAt,
            )
        } else {
            existing.apply {
                chargeableDays = fee.chargeableDays
                totalFeeCfa = fee.totalFeeCfa
                lastIncrementCfa = increment
                measuredUntil = fee.measuredUntil
                updatedAt = assessedAt
            }
        }
        return storageFees.save(saved).toDomain() to increment
    }
}

@Service
class RelayParcelApplicationService(
    private val domain: RelayParcelService,
    private val persistence: RelayParcelPersistenceService,
    private val settlements: SettlementPersistenceService,
    private val publisher: ApplicationEventPublisher,
    private val lockers: dev.orestegabo.sequo_api.domain.hub.RelayLockerRepository,
    private val eligibility: RelayOrderEligibilityService,
    private val orders: dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository? = null,
) {
    private val storageFeeService = RelayStorageFeeService()
    private val objectMapper = ObjectMapper()

    @Transactional
    fun createParcel(command: RelayParcelCreateCommand, actorUserId: String? = null): RelayParcelServiceResult {
        if (persistence.findParcel(command.parcelId) != null)
            return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_already_exists", "Parcel already exists; reload its current state."))
        val sourceCategories = command.orderId?.let(eligibility::categories).orEmpty()
        if (sourceCategories.isNotEmpty() && !relayCategoriesAllowed(sourceCategories))
            return RelayParcelServiceResult.Rejected(RelayParcelRejection("relay_not_allowed_for_category", "Food, perishable and sensitive orders cannot use relay pickup."))
        val result = domain.createParcel(command, actorUserId)
        if (result is RelayParcelServiceResult.Accepted) persistence.saveCreated(result)
        return result
    }

    @Transactional
    fun createPickupCode(command: RelayPickupCodeCreateCommand): RelayParcelServiceResult {
        val parcel = persistence.lockParcel(command.parcel.id)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_found", "Relay parcel was not found."))
        if (persistence.codeExists(command.codeId))
            return RelayParcelServiceResult.Rejected(RelayParcelRejection("pickup_code_already_exists", "Credential already exists; reload the parcel before issuing a new credential."))
        val result = domain.createPickupCode(command.copy(parcel = parcel))
        if (result is RelayParcelServiceResult.Accepted) persistence.savePickupCode(result)
        return result
    }

    @Transactional
    fun createPickupCode(parcelId: String, command: RelayPickupCodeCreateCommand): RelayParcelServiceResult {
        val parcel = persistence.lockParcel(parcelId)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_found", "Relay parcel was not found."))
        return createPickupCode(command.copy(parcel = parcel))
    }

    @Transactional
    fun createPickupCode(
        parcelId: String,
        codeId: String,
        rawNumericCode: String,
        rawQrNonce: String?,
        identityCheckRequired: Boolean,
        expiresAt: java.time.Instant,
        createdAt: java.time.Instant = java.time.Instant.now(),
    ): RelayParcelServiceResult {
        val parcel = persistence.lockParcel(parcelId)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_found", "Relay parcel was not found."))
        return createPickupCode(
            RelayPickupCodeCreateCommand(
                codeId = codeId,
                parcel = parcel,
                rawNumericCode = rawNumericCode,
                rawQrNonce = rawQrNonce,
                identityCheckRequired = identityCheckRequired,
                expiresAt = expiresAt,
                createdAt = createdAt,
            )
        )
    }

    @Transactional
    fun verifyPickup(
        parcelId: String,
        relayPointId: String,
        actorUserId: String,
        rawNumericCode: String?,
        rawQrNonce: String?,
        identityDocumentMatched: Boolean,
        eventId: String,
        idempotencyKey: String,
        verifiedAt: java.time.Instant = java.time.Instant.now(),
    ): RelayParcelServiceResult {
        val parcel = persistence.lockParcel(parcelId)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_found", "Relay parcel was not found."))
        val code = persistence.findPickupCode(parcelId)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("pickup_code_not_found", "Pickup code was not found."))
        val result = domain.verifyPickup(
            RelayPickupVerificationCommand(parcel, code, relayPointId, actorUserId, rawNumericCode, rawQrNonce, identityDocumentMatched, eventId, idempotencyKey, verifiedAt)
        )
        if (result is RelayParcelServiceResult.Accepted && parcel.status != RelayParcelStatus.PickedUp) {
            if (persistence.eventExists(eventId)) return RelayParcelServiceResult.Rejected(RelayParcelRejection("event_id_conflict", "Event identifier already exists."))
            persistence.saveVerification(result)
            parcel.lockerId?.let { lockers.lockForDeposit(it) }?.takeIf {
                it.relayPointId == parcel.relayPointId && it.status == dev.orestegabo.sequo_api.domain.hub.HubLockerStatus.OCCUPIED &&
                    it.availabilityReason == dev.orestegabo.sequo_api.domain.hub.HubLockerAvailabilityReason.SYSTEM_OCCUPIED
            }?.let {
                it.status = dev.orestegabo.sequo_api.domain.hub.HubLockerStatus.AVAILABLE
                it.availabilityReason = dev.orestegabo.sequo_api.domain.hub.HubLockerAvailabilityReason.OPERATOR_CONFIRMED_AVAILABLE
                it.updatedByUserId = actorUserId; it.updatedAt = verifiedAt
                lockers.save(it)
            }
        }
        if (result is RelayParcelServiceResult.Rejected) result.rejection.pickupCode?.let(persistence::saveRejectedCode)
        return result
    }

    @Transactional(readOnly = true)
    fun listParcels(relayPointId: String, status: RelayParcelStatus? = null): List<RelayParcel> {
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        return persistence.listParcels(relayPointId, status)
    }

    @Transactional(readOnly = true)
    fun getParcel(parcelId: String): RelayParcel? = persistence.findParcel(parcelId)

    @Transactional
    fun evaluateDelayed(relayPointId: String, evaluatedAt: java.time.Instant): List<RelayParcel> =
        persistence.lockReviewParcels(relayPointId).mapNotNull { parcel ->
            val updated = domain.markDelayedIfNeeded(parcel, evaluatedAt)
            if (updated.status !in delayedNotificationStatuses || updated.status == parcel.status) return@mapNotNull null
            val type = if (updated.status == RelayParcelStatus.ReturnToSellerReview) RelayCustodyEventType.Review else RelayCustodyEventType.Delay
            val event = RelayCustodyEvent("${parcel.id}:deadline:${updated.status.name}", parcel.id, null, type,
                "Relay deadline reached: ${updated.status.name}.", "deadline:${updated.status.name}", evaluatedAt)
            persistence.saveProblem(updated, event)
            publishRelayDelayEvent(updated)
            updated
        }

    @Transactional
    fun evaluateDelayedForAllRelayPoints(evaluatedAt: java.time.Instant): List<RelayParcel> =
        persistence.relayPointIds()
            .flatMap { relayPointId -> evaluateDelayed(relayPointId, evaluatedAt) }

    @Transactional
    fun assessStorageFees(
        relayPointId: String,
        dailyFeeCfa: Int,
        evaluatedAt: java.time.Instant = java.time.Instant.now(),
    ): List<RelayStorageFeeAssessment> {
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        require(dailyFeeCfa >= 0) { "dailyFeeCfa cannot be negative." }
        if (dailyFeeCfa == 0) return emptyList()
        return persistence.lockParcelsForFeeAssessment(relayPointId, evaluatedAt)
            .filter { parcel -> parcel.orderId?.let { orders?.findById(it)?.orElse(null)?.paymentReference }?.let { !dev.orestegabo.sequo_api.domain.testaccess.isNoFundsTestOrder(it) } ?: true }
            .mapNotNull { parcel -> parcel.assessStorageFee(dailyFeeCfa, evaluatedAt) }
    }

    @Transactional(readOnly = true)
    fun listStorageFeeAssessments(relayPointId: String): List<RelayStorageFeeAssessment> {
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        return persistence.listStorageFeeAssessments(relayPointId)
    }

    @Transactional
    fun reportProblem(
        parcelId: String,
        actorUserId: String,
        eventId: String,
        idempotencyKey: String,
        metadata: String,
        reportedAt: java.time.Instant = java.time.Instant.now(),
    ): RelayParcelServiceResult {
        require(actorUserId.isNotBlank() && eventId.isNotBlank() && idempotencyKey.isNotBlank()) { "Actor, event id, and idempotency key are required." }
        require(metadata.isNotBlank()) { "Problem metadata is required." }
        val parcel = persistence.lockParcel(parcelId)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_found", "Relay parcel was not found."))
        parcel.custodyEvents.firstOrNull { it.type == RelayCustodyEventType.Problem && it.idempotencyKey == idempotencyKey }?.let {
            return RelayParcelServiceResult.Accepted(RelayParcelAccepted(parcel, event = it))
        }
        if (parcel.status == RelayParcelStatus.PickedUp || parcel.status == RelayParcelStatus.CollectedBySequo || parcel.status == RelayParcelStatus.ReturnedToSeller) {
            return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_reportable", "Resolved parcel cannot be reported as a problem."))
        }
        if (persistence.eventExists(eventId)) return RelayParcelServiceResult.Rejected(RelayParcelRejection("event_id_conflict", "Event identifier already exists."))
        val event = RelayCustodyEvent(eventId, parcelId, actorUserId, RelayCustodyEventType.Problem, metadata, idempotencyKey, reportedAt)
        val updated = parcel.copy(status = RelayParcelStatus.Problem, updatedAt = reportedAt, custodyEvents = parcel.custodyEvents + event)
        persistence.saveProblem(updated, event)
        return RelayParcelServiceResult.Accepted(RelayParcelAccepted(updated, event = event))
    }

    @Transactional
    fun returnToSeller(
        parcelId: String,
        actorUserId: String,
        eventId: String,
        idempotencyKey: String,
        metadata: String,
        returnedAt: java.time.Instant = java.time.Instant.now(),
    ): RelayParcelServiceResult {
        require(actorUserId.isNotBlank() && eventId.isNotBlank() && idempotencyKey.isNotBlank()) {
            "Actor, event id, and idempotency key are required."
        }
        require(metadata.isNotBlank()) { "Return metadata is required." }
        val parcel = persistence.lockParcel(parcelId)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_found", "Relay parcel was not found."))
        parcel.custodyEvents.firstOrNull {
            it.type == RelayCustodyEventType.ReturnDropoff && it.idempotencyKey == idempotencyKey
        }?.let { existing ->
            return RelayParcelServiceResult.Accepted(RelayParcelAccepted(parcel, event = existing))
        }
        if (!domain.canReturnToSeller(parcel, returnedAt)) {
            return RelayParcelServiceResult.Rejected(
                RelayParcelRejection("return_review_required", "Parcel must be due for return-to-seller review before it can be returned."),
            )
        }
        if (persistence.eventExists(eventId)) return RelayParcelServiceResult.Rejected(RelayParcelRejection("event_id_conflict", "Event identifier already exists."))
        val event = RelayCustodyEvent(
            id = eventId,
            relayParcelId = parcelId,
            actorUserId = actorUserId,
            type = RelayCustodyEventType.ReturnDropoff,
            metadata = metadata,
            idempotencyKey = idempotencyKey,
            createdAt = returnedAt,
        )
        val returned = parcel.copy(
            status = RelayParcelStatus.ReturnedToSeller,
            collectedAt = returnedAt,
            updatedAt = returnedAt,
            custodyEvents = parcel.custodyEvents + event,
        )
        persistence.saveReturnToSeller(returned, event)
        return RelayParcelServiceResult.Accepted(RelayParcelAccepted(returned, event = event))
    }

    private fun RelayParcel.assessStorageFee(
        dailyFeeCfa: Int,
        evaluatedAt: java.time.Instant,
    ): RelayStorageFeeAssessment? {
        val depositedAt = depositedAt ?: return null
        if (status == RelayParcelStatus.PickedUp || status == RelayParcelStatus.CollectedBySequo || status == RelayParcelStatus.ReturnedToSeller) {
            return null
        }
        val feeStartsAt = feeStartsAt ?: return null
        val existing = persistence.findStorageFeeAssessment(id)
        val fee = storageFeeService.calculate(
            RelayStorageFeeRequest(
                depositedAt = depositedAt,
                evaluatedAt = evaluatedAt,
                pickedUpAt = pickedUpAt,
                collectedAt = collectedAt,
                dailyFeeCfa = existing?.dailyFeeCfa ?: dailyFeeCfa,
                feeStartsAt = feeStartsAt,
            )
        )
        if (!fee.eligible || fee.totalFeeCfa <= 0) return null

        val (assessment, increment) = persistence.saveStorageFeeAssessment(this, fee, evaluatedAt)
        if (increment > 0) {
            val result = settlements.postRelayStorageFee(
                RelayStorageFeeLedgerCommand(
                    entryId = "${id}:storage-fee:${assessment.totalFeeCfa}",
                    relayParcelId = id,
                    relayPointId = relayPointId,
                    amountCfa = increment,
                    chargeableDays = assessment.chargeableDays,
                    createdAt = evaluatedAt,
                )
            )
            check(result is SettlementResult.Posted) { "Relay storage fee ledger posting failed." }
        }
        return assessment
    }

    private fun publishRelayDelayEvent(parcel: RelayParcel) {
        publisher.publishEvent(
            NotificationWorkflowEvent(
                eventId = "${parcel.id}:relay-delay:${parcel.status.name}",
                eventType = NotificationEventType.RELAY_PARCEL_DELAYED,
                aggregateType = "RELAY_PARCEL",
                aggregateId = parcel.id,
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "relayUserIds" to setOf(parcel.relayPointId),
                        "title" to "Relay parcel delay",
                        "body" to parcel.delayNotificationBody(),
                        "actionUrl" to "/relay/parcels/${parcel.id}",
                        "messagePayload" to mapOf(
                            "parcelId" to parcel.id,
                            "relayPointId" to parcel.relayPointId,
                            "orderId" to parcel.orderId,
                            "returnId" to parcel.returnId,
                            "status" to parcel.status.name,
                        ),
                    )
                ),
            )
        )
    }

    private fun RelayParcel.delayNotificationBody(): String =
        when (status) {
            RelayParcelStatus.ReturnToSellerReview -> "Relay parcel $id needs return-to-seller review."
            else -> "Relay parcel $id has exceeded the normal pickup window."
        }

    private companion object {
        val delayedNotificationStatuses = setOf(RelayParcelStatus.Delayed, RelayParcelStatus.ReturnToSellerReview)
    }
}

private fun RelayParcel.toRecord() = RelayParcelRecord(
    id = id,
    relayPointId = relayPointId,
    lockerId = lockerId,
    orderId = orderId,
    deliveryMissionId = deliveryMissionId,
    returnId = returnId,
    depositCode = depositCode,
    category = category,
    status = status,
    depositedAt = depositedAt,
    feeStartsAt = feeStartsAt,
    pickedUpAt = pickedUpAt,
    collectedAt = collectedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun RelayPickupCode.toRecord() = RelayPickupCodeRecord(
    id = id,
    relayParcelId = relayParcelId,
    codeHash = codeHash,
    customerDeliveryVersion = customerDeliveryVersion,
    qrNonceHash = qrNonceHash,
    identityCheckRequired = identityCheckRequired,
    expiresAt = expiresAt,
    usedAt = usedAt,
    attemptCount = attemptCount,
    createdAt = createdAt,
)

private fun RelayCustodyEvent.toRecord() = RelayCustodyEventRecord(
    id = id,
    relayParcelId = relayParcelId,
    actorUserId = actorUserId,
    type = type,
    metadata = metadata,
    idempotencyKey = idempotencyKey,
    createdAt = createdAt,
)
private fun RelayParcelRecord.toDomain(custodyEvents: List<RelayCustodyEvent> = emptyList()) = RelayParcel(
    id = id,
    relayPointId = relayPointId,
    lockerId = lockerId,
    orderId = orderId,
    deliveryMissionId = deliveryMissionId,
    returnId = returnId,
    category = category,
    depositCode = depositCode ?: "",
    status = status,
    depositedAt = depositedAt,
    feeStartsAt = feeStartsAt,
    pickedUpAt = pickedUpAt,
    collectedAt = collectedAt,
    custodyEvents = custodyEvents,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
private fun RelayPickupCodeRecord.toDomain() = RelayPickupCode(id, relayParcelId, codeHash, qrNonceHash, identityCheckRequired, expiresAt, usedAt, attemptCount, createdAt, customerDeliveryVersion)
private fun RelayCustodyEventRecord.toDomain() = RelayCustodyEvent(id, relayParcelId, actorUserId, type, metadata, idempotencyKey, createdAt)
private fun RelayStorageFeeAssessmentRecord.toDomain() = RelayStorageFeeAssessment(
    id = id,
    relayParcelId = relayParcelId,
    relayPointId = relayPointId,
    dailyFeeCfa = dailyFeeCfa,
    chargeableDays = chargeableDays,
    totalFeeCfa = totalFeeCfa,
    lastIncrementCfa = lastIncrementCfa,
    feeStartsAt = feeStartsAt,
    measuredUntil = measuredUntil,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

@Converter
class RelayParcelStatusConverter : AttributeConverter<RelayParcelStatus, String> {
    override fun convertToDatabaseColumn(attribute: RelayParcelStatus?): String? = attribute?.toSqlValue()
    override fun convertToEntityAttribute(dbData: String?): RelayParcelStatus? = dbData?.toRelayParcelStatus()
}

@Converter
class RelayCustodyEventTypeConverter : AttributeConverter<RelayCustodyEventType, String> {
    override fun convertToDatabaseColumn(attribute: RelayCustodyEventType?): String? = attribute?.toSqlValue()
    override fun convertToEntityAttribute(dbData: String?): RelayCustodyEventType? = dbData?.toRelayCustodyEventType()
}

private fun RelayParcelStatus.toSqlValue(): String =
    when (this) {
        RelayParcelStatus.Created -> "CREATED"
        RelayParcelStatus.Deposited -> "DEPOSITED"
        RelayParcelStatus.PickedUp -> "PICKED_UP"
        RelayParcelStatus.CollectedBySequo -> "COLLECTED_BY_SEQUO"
        RelayParcelStatus.Delayed -> "DELAYED"
        RelayParcelStatus.ReturnToSellerReview -> "RETURN_TO_SELLER_REVIEW"
        RelayParcelStatus.ReturnedToSeller -> "RETURNED_TO_SELLER"
        RelayParcelStatus.Problem -> "PROBLEM"
    }

private fun String.toRelayParcelStatus(): RelayParcelStatus =
    when (this) {
        "CREATED" -> RelayParcelStatus.Created
        "DEPOSITED" -> RelayParcelStatus.Deposited
        "PICKED_UP" -> RelayParcelStatus.PickedUp
        "COLLECTED_BY_SEQUO" -> RelayParcelStatus.CollectedBySequo
        "DELAYED" -> RelayParcelStatus.Delayed
        "RETURN_TO_SELLER_REVIEW" -> RelayParcelStatus.ReturnToSellerReview
        "RETURNED_TO_SELLER" -> RelayParcelStatus.ReturnedToSeller
        "PROBLEM" -> RelayParcelStatus.Problem
        else -> error("Unknown relay parcel status: $this")
    }

private fun RelayCustodyEventType.toSqlValue(): String =
    when (this) {
        RelayCustodyEventType.Deposit -> "DEPOSIT"
        RelayCustodyEventType.Pickup -> "PICKUP"
        RelayCustodyEventType.RelayRelease -> "RELAY_RELEASE"
        RelayCustodyEventType.SequoCollection -> "SEQUO_COLLECTION"
        RelayCustodyEventType.ReturnDropoff -> "RETURN_DROPOFF"
        RelayCustodyEventType.Problem -> "PROBLEM"
        RelayCustodyEventType.Delay -> "DELAY"
        RelayCustodyEventType.Review -> "REVIEW"
    }

private fun String.toRelayCustodyEventType(): RelayCustodyEventType =
    when (this) {
        "DEPOSIT" -> RelayCustodyEventType.Deposit
        "PICKUP" -> RelayCustodyEventType.Pickup
        "RELAY_RELEASE" -> RelayCustodyEventType.RelayRelease
        "SEQUO_COLLECTION" -> RelayCustodyEventType.SequoCollection
        "RETURN_DROPOFF" -> RelayCustodyEventType.ReturnDropoff
        "PROBLEM" -> RelayCustodyEventType.Problem
        "DELAY" -> RelayCustodyEventType.Delay
        "REVIEW" -> RelayCustodyEventType.Review
        else -> error("Unknown relay custody event type: $this")
    }
