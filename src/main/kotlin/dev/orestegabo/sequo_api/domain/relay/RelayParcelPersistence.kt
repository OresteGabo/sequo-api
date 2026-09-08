package dev.orestegabo.sequo_api.domain.relay

import dev.orestegabo.sequo_api.domain.settlement.RelayStorageFeeLedgerCommand
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import jakarta.persistence.Column
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(name = "relay_parcels")
class RelayParcelRecord(
    @Id val id: String,
    @Column(name = "relay_point_id", nullable = false) val relayPointId: String,
    @Column(name = "locker_id") val lockerId: String? = null,
    @Column(name = "order_id") val orderId: String? = null,
    @Column(name = "delivery_mission_id") val deliveryMissionId: String? = null,
    @Column(name = "return_id") val returnId: String? = null,
    @Column(name = "deposit_code", unique = true) val depositCode: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val category: RelayParcelCategory,
    @Convert(converter = RelayParcelStatusConverter::class) @Column(nullable = false) var status: RelayParcelStatus,
    @Column(name = "deposited_at") var depositedAt: java.time.Instant? = null,
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
    @Column(name = "actor_user_id") val actorUserId: String? = null,
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
    @Column(name = "relay_point_id", nullable = false) val relayPointId: String,
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

interface RelayParcelRecordRepository : JpaRepository<RelayParcelRecord, String> {
    fun findByRelayPointIdOrderByUpdatedAtDesc(relayPointId: String): List<RelayParcelRecord>
    fun countByStatusIn(statuses: Collection<RelayParcelStatus>): Long
    fun countByReturnIdIsNotNullAndStatusIn(statuses: Collection<RelayParcelStatus>): Long
    fun findTop50ByStatusInOrderByUpdatedAtAsc(statuses: Collection<RelayParcelStatus>): List<RelayParcelRecord>
    @Query("select distinct p.relayPointId from RelayParcelRecord p")
    fun findDistinctRelayPointIds(): List<String>
}
interface RelayPickupCodeRecordRepository : JpaRepository<RelayPickupCodeRecord, String> {
    fun findFirstByRelayParcelIdOrderByCreatedAtDesc(relayParcelId: String): RelayPickupCodeRecord?
}
interface RelayCustodyEventRecordRepository : JpaRepository<RelayCustodyEventRecord, String> {
    fun findByRelayParcelIdOrderByCreatedAtAsc(relayParcelId: String): List<RelayCustodyEventRecord>
}
interface RelayStorageFeeAssessmentRecordRepository : JpaRepository<RelayStorageFeeAssessmentRecord, String> {
    fun findByRelayParcelId(relayParcelId: String): RelayStorageFeeAssessmentRecord?
    fun findByRelayPointIdOrderByUpdatedAtDesc(relayPointId: String): List<RelayStorageFeeAssessmentRecord>
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
) {
    fun findParcel(id: String): RelayParcel? = parcels.findById(id).orElse(null)?.toDomain(
        events.findByRelayParcelIdOrderByCreatedAtAsc(id).map { it.toDomain() }
    )

    fun findPickupCode(parcelId: String): RelayPickupCode? = pickupCodes.findFirstByRelayParcelIdOrderByCreatedAtDesc(parcelId)?.toDomain()

    fun listParcels(relayPointId: String, status: RelayParcelStatus? = null): List<RelayParcel> =
        parcels.findByRelayPointIdOrderByUpdatedAtDesc(relayPointId)
            .asSequence()
            .filter { status == null || it.status == status }
            .map { it.toDomain() }
            .toList()

    fun relayPointIds(): List<String> = parcels.findDistinctRelayPointIds()

    fun listStorageFeeAssessments(relayPointId: String): List<RelayStorageFeeAssessment> =
        storageFees.findByRelayPointIdOrderByUpdatedAtDesc(relayPointId).map { it.toDomain() }

    @Transactional
    fun save(parcel: RelayParcel): RelayParcel = parcels.save(parcel.toRecord()).toDomain()

    @Transactional
    fun saveCreated(result: RelayParcelServiceResult.Accepted): RelayParcel = result.value.parcel.also { parcel ->
        parcels.save(parcel.toRecord())
        result.value.event?.let { events.save(it.toRecord()) }
    }

    @Transactional
    fun savePickupCode(result: RelayParcelServiceResult.Accepted): RelayPickupCode =
        requireNotNull(result.value.pickupCode) { "Accepted relay pickup result must contain a pickup code." }.also { code ->
            pickupCodes.save(code.toRecord())
        }

    @Transactional
    fun saveVerification(result: RelayParcelServiceResult.Accepted): RelayParcel = result.value.parcel.also { parcel ->
        parcels.save(parcel.toRecord())
        result.value.pickupCode?.let { pickupCodes.save(it.toRecord()) }
        result.value.event?.let { events.save(it.toRecord()) }
    }

    @Transactional
    fun saveProblem(parcel: RelayParcel, event: RelayCustodyEvent): RelayParcel = parcel.also {
        parcels.save(it.toRecord())
        events.save(event.toRecord())
    }

    @Transactional
    fun saveStorageFeeAssessment(
        parcel: RelayParcel,
        fee: RelayStorageFeeSnapshot,
        assessedAt: java.time.Instant,
    ): Pair<RelayStorageFeeAssessment, Int> {
        val id = "${parcel.id}:storage-fee"
        val existing = storageFees.findByRelayParcelId(parcel.id)
        val previousTotal = existing?.totalFeeCfa ?: 0
        val increment = fee.totalFeeCfa - previousTotal
        val saved = if (existing == null) {
            RelayStorageFeeAssessmentRecord(
                id = id,
                relayParcelId = parcel.id,
                relayPointId = parcel.relayPointId,
                dailyFeeCfa = fee.dailyFeeCfa,
                chargeableDays = fee.chargeableDays,
                totalFeeCfa = fee.totalFeeCfa,
                lastIncrementCfa = increment.coerceAtLeast(0),
                feeStartsAt = fee.feeStartsAt,
                measuredUntil = fee.measuredUntil,
                createdAt = assessedAt,
                updatedAt = assessedAt,
            )
        } else {
            existing.apply {
                chargeableDays = fee.chargeableDays
                totalFeeCfa = maxOf(totalFeeCfa, fee.totalFeeCfa)
                lastIncrementCfa = increment.coerceAtLeast(0)
                feeStartsAt = fee.feeStartsAt
                measuredUntil = fee.measuredUntil
                updatedAt = assessedAt
            }
        }
        return storageFees.save(saved).toDomain() to increment.coerceAtLeast(0)
    }
}

@Service
class RelayParcelApplicationService(
    private val domain: RelayParcelService,
    private val persistence: RelayParcelPersistenceService,
    private val settlements: SettlementPersistenceService,
) {
    private val storageFeeService = RelayStorageFeeService()

    @Transactional
    fun createParcel(command: RelayParcelCreateCommand): RelayParcelServiceResult {
        val result = domain.createParcel(command)
        if (result is RelayParcelServiceResult.Accepted) persistence.saveCreated(result)
        return result
    }

    @Transactional
    fun createPickupCode(command: RelayPickupCodeCreateCommand): RelayParcelServiceResult {
        val result = domain.createPickupCode(command)
        if (result is RelayParcelServiceResult.Accepted) persistence.savePickupCode(result)
        return result
    }

    @Transactional
    fun createPickupCode(parcelId: String, command: RelayPickupCodeCreateCommand): RelayParcelServiceResult {
        val parcel = persistence.findParcel(parcelId)
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
        val parcel = persistence.findParcel(parcelId)
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
        val parcel = persistence.findParcel(parcelId)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_found", "Relay parcel was not found."))
        val code = persistence.findPickupCode(parcelId)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("pickup_code_not_found", "Pickup code was not found."))
        val result = domain.verifyPickup(
            RelayPickupVerificationCommand(parcel, code, relayPointId, actorUserId, rawNumericCode, rawQrNonce, identityDocumentMatched, eventId, idempotencyKey, verifiedAt)
        )
        if (result is RelayParcelServiceResult.Accepted) persistence.saveVerification(result)
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
        persistence.listParcels(relayPointId)
            .map { parcel -> domain.markDelayedIfNeeded(parcel, evaluatedAt) }
            .filter { it.status == RelayParcelStatus.Delayed || it.status == RelayParcelStatus.ReturnToSellerReview }
            .map(persistence::save)

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
        return persistence.listParcels(relayPointId)
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
        val parcel = persistence.findParcel(parcelId)
            ?: return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_found", "Relay parcel was not found."))
        parcel.custodyEvents.firstOrNull { it.type == RelayCustodyEventType.Problem && it.idempotencyKey == idempotencyKey }?.let {
            return RelayParcelServiceResult.Accepted(RelayParcelAccepted(parcel, event = it))
        }
        if (parcel.status == RelayParcelStatus.PickedUp || parcel.status == RelayParcelStatus.CollectedBySequo || parcel.status == RelayParcelStatus.ReturnedToSeller) {
            return RelayParcelServiceResult.Rejected(RelayParcelRejection("parcel_not_reportable", "Resolved parcel cannot be reported as a problem."))
        }
        val event = RelayCustodyEvent(eventId, parcelId, actorUserId, RelayCustodyEventType.Problem, metadata, idempotencyKey, reportedAt)
        val updated = parcel.copy(status = RelayParcelStatus.Problem, updatedAt = reportedAt, custodyEvents = parcel.custodyEvents + event)
        persistence.saveProblem(updated, event)
        return RelayParcelServiceResult.Accepted(RelayParcelAccepted(updated, event = event))
    }

    private fun RelayParcel.assessStorageFee(
        dailyFeeCfa: Int,
        evaluatedAt: java.time.Instant,
    ): RelayStorageFeeAssessment? {
        val depositedAt = depositedAt ?: return null
        if (status == RelayParcelStatus.PickedUp || status == RelayParcelStatus.CollectedBySequo || status == RelayParcelStatus.ReturnedToSeller) {
            return null
        }
        val fee = storageFeeService.calculate(
            RelayStorageFeeRequest(
                depositedAt = depositedAt,
                evaluatedAt = evaluatedAt,
                pickedUpAt = pickedUpAt,
                collectedAt = collectedAt,
                dailyFeeCfa = dailyFeeCfa,
            )
        )
        if (!fee.eligible || fee.totalFeeCfa <= 0) return null

        val (assessment, increment) = persistence.saveStorageFeeAssessment(this, fee, evaluatedAt)
        if (increment > 0) {
            settlements.postRelayStorageFee(
                RelayStorageFeeLedgerCommand(
                    entryId = "${id}:storage-fee:${assessment.totalFeeCfa}",
                    relayParcelId = id,
                    relayPointId = relayPointId,
                    amountCfa = increment,
                    chargeableDays = assessment.chargeableDays,
                    createdAt = evaluatedAt,
                )
            )
        }
        return assessment
    }
}

private fun RelayParcel.toRecord() = RelayParcelRecord(id, relayPointId, lockerId, orderId, deliveryMissionId, returnId, depositCode, category, status, depositedAt, pickedUpAt, collectedAt, createdAt, updatedAt)
private fun RelayPickupCode.toRecord() = RelayPickupCodeRecord(id, relayParcelId, codeHash, qrNonceHash, identityCheckRequired, expiresAt, usedAt, attemptCount, createdAt)
private fun RelayCustodyEvent.toRecord() = RelayCustodyEventRecord(id, relayParcelId, actorUserId, type, metadata, idempotencyKey, createdAt)
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
    pickedUpAt = pickedUpAt,
    collectedAt = collectedAt,
    custodyEvents = custodyEvents,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
private fun RelayPickupCodeRecord.toDomain() = RelayPickupCode(id, relayParcelId, codeHash, qrNonceHash, identityCheckRequired, expiresAt, usedAt, attemptCount, createdAt)
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
    }

private fun String.toRelayCustodyEventType(): RelayCustodyEventType =
    when (this) {
        "DEPOSIT" -> RelayCustodyEventType.Deposit
        "PICKUP" -> RelayCustodyEventType.Pickup
        "RELAY_RELEASE" -> RelayCustodyEventType.RelayRelease
        "SEQUO_COLLECTION" -> RelayCustodyEventType.SequoCollection
        "RETURN_DROPOFF" -> RelayCustodyEventType.ReturnDropoff
        "PROBLEM" -> RelayCustodyEventType.Problem
        else -> error("Unknown relay custody event type: $this")
    }
