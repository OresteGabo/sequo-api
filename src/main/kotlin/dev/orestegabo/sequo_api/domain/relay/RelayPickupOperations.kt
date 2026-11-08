package dev.orestegabo.sequo_api.domain.relay

import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.OrderDeliveryLifecycleService
import dev.orestegabo.sequo_api.domain.hub.*
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RelayDeadlines(val feeStartsAt: Instant?, val reviewDueAt: Instant, val storageFeeEligible: Boolean,
    val reviewDue: Boolean, val feePolicyKnown: Boolean)

fun RelayParcel.deadlines(now: Instant = Instant.now()): RelayDeadlines? = depositedAt?.let { deposit ->
    val unresolved = pickedUpAt == null && collectedAt == null && status !in setOf(RelayParcelStatus.PickedUp, RelayParcelStatus.ReturnedToSeller, RelayParcelStatus.CollectedBySequo)
    RelayDeadlines(feeStartsAt, deposit.plusSeconds(30L * 86400), unresolved && feeStartsAt?.let(now::isAfter) == true,
        unresolved && !now.isBefore(deposit.plusSeconds(30L * 86400)), feeStartsAt != null)
}

@Service
class RelayPickupOperations(private val service: RelayParcelApplicationService,
    private val persistence: RelayParcelPersistenceService, private val records: RelayParcelRecordRepository, private val missions: DeliveryMissionRepository,
    private val delivery: DeliveryMissionService, private val lifecycle: OrderDeliveryLifecycleService,
    private val orders: CustomerOrderRecordRepository, private val lockers: RelayLockerRepository) {
    @Transactional
    fun release(parcelId: String, relayPointId: String, actorUserId: String, rawNumericCode: String?, rawQrNonce: String?,
        identityDocumentMatched: Boolean, eventId: String, idempotencyKey: String): RelayParcelServiceResult {
        val reference = records.reference(parcelId) ?: return rejected("parcel_not_found", "Parcel is unavailable.")
        reference.orderId?.let { orders.lockForCompletion(it) }
        val mission = reference.deliveryMissionId?.let {
            requireNotNull(missions.lockForAction(it)) { "Linked delivery mission requires operational review." }
        }
        val preview = requireNotNull(persistence.lockParcel(parcelId))
        if (mission != null) require(mission.orderId == preview.orderId && mission.destinationType == DeliveryMissionRecordDestination.RELAY_POINT &&
            (mission.relayPointId == null || mission.relayPointId == relayPointId) && mission.status in setOf(
                DeliveryMissionRecordStatus.DEPOSITED_AT_RELAY, DeliveryMissionRecordStatus.RELEASED_BY_RELAY)) { "Delivery and parcel custody disagree; contact SEQUO." }
        // Identity remains mandatory on this public route even for older optional-identity records.
        if (!identityDocumentMatched && persistence.findPickupCode(parcelId)?.identityCheckRequired == false) return rejected("identity_check_failed", "Identity validation is required before relay release.")
        val result = service.verifyPickup(parcelId, relayPointId, actorUserId, rawNumericCode, rawQrNonce,
            identityDocumentMatched, eventId, idempotencyKey)
        if (result is RelayParcelServiceResult.Accepted && mission != null) {
            if (mission.status != DeliveryMissionRecordStatus.RELEASED_BY_RELAY) {
                val key = "relay-release:" + java.security.MessageDigest.getInstance("SHA-256").digest(parcelId.toByteArray()).toHexString()
                val completed = delivery.transitionIdempotent(requireNotNull(mission.id), actorUserId,
                    DeliveryMissionEvent.RelayReleasesToCustomer, key, "Pickup credential and identity checked at relay.",
                    relayPickupValidated = true, identityValidated = true, at = requireNotNull(result.value.parcel.pickedUpAt))
                require(completed is DeliveryMissionServiceResult.Success) { "Delivery could not be completed; custody release was rolled back." }
                lifecycle.markDeliveredFromMission(completed.mission, actorUserId)
            }
        }
        return result
    }

    @Transactional
    fun review(parcelId: String, actor: String, eventId: String, key: String, note: String): RelayParcelServiceResult {
        val parcel = persistence.lockParcel(parcelId) ?: return rejected("parcel_not_found", "Parcel is unavailable.")
        val deadline = parcel.deadlines()
        require(deadline?.let { it.storageFeeEligible || it.reviewDue } == true) { "Parcel is not overdue." }
        parcel.custodyEvents.firstOrNull { it.type == RelayCustodyEventType.Review && it.idempotencyKey == key && it.actorUserId == actor }?.let {
            return RelayParcelServiceResult.Accepted(RelayParcelAccepted(parcel, event = it))
        }
        require(!persistence.eventExists(eventId)) { "Event identifier already exists." }
        val event = RelayCustodyEvent(eventId, parcelId, actor, RelayCustodyEventType.Review, note, key, Instant.now())
        persistence.saveProblem(parcel, event)
        return RelayParcelServiceResult.Accepted(RelayParcelAccepted(parcel, event = event))
    }

    @Transactional
    fun returnToSeller(parcelId: String, actorUserId: String, eventId: String, idempotencyKey: String, metadata: String, returnedAt: Instant): RelayParcelServiceResult {
        val reference = records.reference(parcelId) ?: return rejected("parcel_not_found", "Parcel is unavailable.")
        reference.orderId?.let { orders.lockForCompletion(it) }
        val mission = reference.deliveryMissionId?.let { missions.lockForAction(it) }
        val preview = requireNotNull(persistence.lockParcel(parcelId))
        val result = service.returnToSeller(parcelId, actorUserId, eventId, idempotencyKey, metadata, returnedAt)
        if (result is RelayParcelServiceResult.Accepted) {
            if (mission != null && mission.status != DeliveryMissionRecordStatus.CANCELLED) {
                require(delivery.cancel(requireNotNull(mission.id), actorUserId, "Relay seller return: $metadata", returnedAt) is DeliveryMissionServiceResult.Success)
            }
            preview.lockerId?.let { lockers.lockForDeposit(it) }?.takeIf {
                it.relayPointId == preview.relayPointId && it.availabilityReason == HubLockerAvailabilityReason.SYSTEM_OCCUPIED
            }?.let { it.status = HubLockerStatus.AVAILABLE; it.updatedAt = returnedAt; it.updatedByUserId = actorUserId; lockers.save(it) }
        }
        return result
    }
    private fun rejected(code: String, message: String) = RelayParcelServiceResult.Rejected(RelayParcelRejection(code, message))
}
