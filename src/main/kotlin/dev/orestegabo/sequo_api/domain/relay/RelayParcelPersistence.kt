package dev.orestegabo.sequo_api.domain.relay

import jakarta.persistence.Column
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
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: RelayParcelStatus,
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
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false) val type: RelayCustodyEventType,
    @Column(nullable = false) val metadata: String,
    @Column(name = "idempotency_key") val idempotencyKey: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: java.time.Instant,
)

interface RelayParcelRecordRepository : JpaRepository<RelayParcelRecord, String>
interface RelayPickupCodeRecordRepository : JpaRepository<RelayPickupCodeRecord, String>
interface RelayCustodyEventRecordRepository : JpaRepository<RelayCustodyEventRecord, String>

@Service
class RelayParcelPersistenceService(
    private val parcels: RelayParcelRecordRepository,
    private val pickupCodes: RelayPickupCodeRecordRepository,
    private val events: RelayCustodyEventRecordRepository,
) {
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
}

private fun RelayParcel.toRecord() = RelayParcelRecord(id, relayPointId, lockerId, orderId, deliveryMissionId, returnId, depositCode, status, depositedAt, pickedUpAt, collectedAt, createdAt, updatedAt)
private fun RelayPickupCode.toRecord() = RelayPickupCodeRecord(id, relayParcelId, codeHash, qrNonceHash, identityCheckRequired, expiresAt, usedAt, attemptCount, createdAt)
private fun RelayCustodyEvent.toRecord() = RelayCustodyEventRecord(id, relayParcelId, actorUserId, type, metadata, idempotencyKey, createdAt)
