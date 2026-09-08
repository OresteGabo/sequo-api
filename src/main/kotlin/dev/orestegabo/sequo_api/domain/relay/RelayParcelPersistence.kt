package dev.orestegabo.sequo_api.domain.relay

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

interface RelayParcelRecordRepository : JpaRepository<RelayParcelRecord, String> {
    fun findByRelayPointIdOrderByUpdatedAtDesc(relayPointId: String): List<RelayParcelRecord>
}
interface RelayPickupCodeRecordRepository : JpaRepository<RelayPickupCodeRecord, String> {
    fun findFirstByRelayParcelIdOrderByCreatedAtDesc(relayParcelId: String): RelayPickupCodeRecord?
}
interface RelayCustodyEventRecordRepository : JpaRepository<RelayCustodyEventRecord, String> {
    fun findByRelayParcelIdOrderByCreatedAtAsc(relayParcelId: String): List<RelayCustodyEventRecord>
}

@Service
class RelayParcelPersistenceService(
    private val parcels: RelayParcelRecordRepository,
    private val pickupCodes: RelayPickupCodeRecordRepository,
    private val events: RelayCustodyEventRecordRepository,
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

    @Transactional
    fun save(parcel: RelayParcel): RelayParcel = parcels.save(parcel.toRecord()).toDomain()

    @Transactional
    fun saveCreated(result: RelayParcelServiceResult.Accepted): RelayParcel = result.value.parcel.also { parcel ->
        parcels.save(parcel.toRecord())
        result.value.event?.let { events.save(it.toRecord()) }
    }

    @Transactional
    fun savePickupCode(result: RelayParcelServiceResult.Accepted): RelayPickupCode = result.value.pickupCode!!.also { code ->
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
}

@Service
class RelayParcelApplicationService(
    private val domain: RelayParcelService,
    private val persistence: RelayParcelPersistenceService,
) {
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

    @Transactional
    fun evaluateDelayed(relayPointId: String, evaluatedAt: java.time.Instant): List<RelayParcel> =
        persistence.listParcels(relayPointId)
            .map { parcel -> domain.markDelayedIfNeeded(parcel, evaluatedAt) }
            .filter { it.status == RelayParcelStatus.Delayed || it.status == RelayParcelStatus.ReturnToSellerReview }
            .map(persistence::save)

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

@Converter
class RelayParcelStatusConverter : AttributeConverter<RelayParcelStatus, String> {
    override fun convertToDatabaseColumn(attribute: RelayParcelStatus?): String? = attribute?.name.toSqlEnum()
    override fun convertToEntityAttribute(dbData: String?): RelayParcelStatus? = dbData?.let { RelayParcelStatus.valueOf(it.toCamelEnum()) }
}

@Converter
class RelayCustodyEventTypeConverter : AttributeConverter<RelayCustodyEventType, String> {
    override fun convertToDatabaseColumn(attribute: RelayCustodyEventType?): String? = attribute?.name.toSqlEnum()
    override fun convertToEntityAttribute(dbData: String?): RelayCustodyEventType? = dbData?.let { RelayCustodyEventType.valueOf(it.toCamelEnum()) }
}

private fun String?.toSqlEnum(): String? = this?.replace(Regex("([a-z])([A-Z])"), "$1_$2")?.uppercase()
private fun String.toCamelEnum(): String = lowercase().split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
