package dev.orestegabo.sequo_api.domain.relay

import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@Service
class RelayFulfillmentService(private val missions: DeliveryMissionRepository, private val delivery: DeliveryMissionService,
    private val orders: CustomerOrderRecordRepository, private val eligibility: RelayOrderEligibilityService,
    private val availability: RelayAvailabilityService, private val lockers: RelayLockerRepository,
    private val credentials: RelayCustomerPickupService,
    private val parcels: RelayParcelApplicationService, private val parcelRecords: RelayParcelRecordRepository,
    private val administrativeUsers: dev.orestegabo.sequo_api.domain.auth.UserRepository,
    private val references: PublicReferenceRegistry? = null) {
    private fun requireEligible(orderId: String) {
        val order = orders.findById(orderId).orElseThrow { IllegalArgumentException("Order is unavailable.") }
        require(order.paymentStatus == PaymentValidationStatus.Validated && order.orderStatus == CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT) { "Order is not paid and active." }
        require(relayCategoriesAllowed(eligibility.categories(orderId))) { "This order is not eligible for relay delivery." }
    }

    @Transactional
    fun deposit(missionId: String, actor: String, relayPointId: String?, lockerCode: String?, proof: String?, key: String?): DeliveryMissionServiceResult {
        return depositInternal(missionId, actor, relayPointId, lockerCode, proof, key, false)
    }

    @Transactional
    fun depositBySequo(missionId: String, actor: String, relayPointId: String, lockerCode: String, proof: String, key: String): DeliveryMissionServiceResult {
        val user = administrativeUsers.findById(actor).orElse(null)
        require(user != null && user.active && user.status.canAuthenticate() && user.roles.any { it in RoleGroups.AdminOnly }) { "An active administrator is required." }
        return depositInternal(missionId, actor, relayPointId, lockerCode, proof, key, true)
    }

    private fun depositInternal(missionId: String, actor: String, relayPointId: String?, lockerCode: String?, proof: String?, key: String?, bySequo: Boolean): DeliveryMissionServiceResult {
        // Do not attach a mission before waiting for its parent lock: a concurrent completed
        // deposit would leave that managed version stale when the mission lock is acquired.
        val orderId = missions.orderReference(missionId) ?: throw IllegalArgumentException("Mission is unavailable.")
        orders.lockForCompletion(orderId) ?: throw IllegalArgumentException("Order is unavailable.")
        val mission = requireNotNull(missions.lockForAction(missionId)) { "Mission is unavailable." }
        if (!bySequo) require(mission.courierId != null && mission.courierId == delivery.courierIdForActor(actor)) { "Only the assigned courier may deposit this parcel." }
        require(!key.isNullOrBlank() && key.length <= 128) { "A stable deposit retry key is required." }
        require(!relayPointId.isNullOrBlank() && relayPointId == mission.relayPointId && mission.destinationType == DeliveryMissionRecordDestination.RELAY_POINT) { "Deposit must use the persisted relay destination." }
        val depositCode = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$missionId:$key".toByteArray()).toHexString()
        val existing = parcelRecords.findByDeliveryMissionId(missionId)
        if (existing.isNotEmpty()) {
            require(existing.size == 1 && existing.single().relayPointId == relayPointId &&
                existing.single().depositCode == depositCode && existing.single().lockerId?.let {
                    lockers.findById(it).orElse(null)?.lockerCode == lockerCode
                } == true) { "Deposit conflicts with existing custody; reload the mission." }
            return DeliveryMissionServiceResult.Success(mission.toSnapshot(references), "Relay deposit already recorded.")
        }
        require(mission.status == DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER) { "Pickup is required before relay deposit." }
        require(!proof.isNullOrBlank()) { "Relay deposit proof is required." }
        requireEligible(mission.orderId)
        availability.requireAvailable(relayPointId, mission.relayReroutedAt != null)
        val slot = lockerCode?.let { lockers.findByRelayPointIdAndLockerCode(relayPointId, it) }
        val locked = requireNotNull(slot?.let { lockers.lockForDeposit(it.id) }) { "Storage slot is unavailable at this relay." }
        require(locked.relayPointId == relayPointId && locked.status == HubLockerStatus.AVAILABLE) { "Storage slot is unavailable at this relay." }
        val result = if (bySequo) delivery.sequoDepositsAtRelay(missionId, actor, proof)
            else delivery.transitionIdempotent(missionId, actor, DeliveryMissionEvent.CourierDepositsAtRelay, key, proof)
        require(result is DeliveryMissionServiceResult.Success) { (result as DeliveryMissionServiceResult.Rejected).message }
        val created = parcels.createParcel(RelayParcelCreateCommand("mission-$missionId", relayPointId,
            orderId = mission.orderId, deliveryMissionId = missionId, category = RelayParcelCategory.GeneralGoods,
            depositCode = depositCode, availableLockers = listOf(RelayLocker(locked.id, relayPointId, true, false)), createdAt = Instant.now()), actor)
        require(created is RelayParcelServiceResult.Accepted) { "Relay custody could not be recorded." }
        locked.status = HubLockerStatus.OCCUPIED; locked.availabilityReason = HubLockerAvailabilityReason.SYSTEM_OCCUPIED
        locked.updatedByUserId = actor; locked.updatedAt = Instant.now()
        lockers.save(locked)
        credentials.issue("mission-$missionId")
        return result
    }

    @Transactional
    fun reroute(missionId: String, actor: String, request: RelayRerouteRequest): DeliveryMissionSnapshot {
        val mission = requireNotNull(missions.lockForAction(missionId)) { "Mission is unavailable." }
        require(request.customerApproved && request.reason.isNotBlank() && request.reason.length <= 2000 &&
            request.idempotencyKey.isNotBlank() && request.idempotencyKey.length <= 128) { "Customer approval, reason and retry key are required." }
        if (mission.relayRerouteKey == request.idempotencyKey) {
            require(mission.relayRerouteActorId == actor && mission.relayPointId == request.relayPointId) { "Reroute retry conflicts with original approval." }
            return mission.toSnapshot(references)
        }
        require(mission.destinationType == DeliveryMissionRecordDestination.CUSTOMER_ADDRESS &&
            mission.status == DeliveryMissionRecordStatus.PROBLEM_REPORTED && mission.pickupAt != null && mission.deliveredAt == null) { "Only a failed home handoff after pickup can be rerouted." }
        requireEligible(mission.orderId)
        val destination = availability.requireAvailable(request.relayPointId, reroute = true)
        mission.relayPointId = destination.id; mission.relayDestinationPayload = ObjectMapper().writeValueAsString(destination)
        mission.destinationType = DeliveryMissionRecordDestination.RELAY_POINT; mission.deliveryMode = DeliveryMissionRecordMode.RELAY
        mission.relayRerouteActorId = actor; mission.relayRerouteKey = request.idempotencyKey
        mission.relayRerouteReason = request.reason; mission.relayReroutedAt = Instant.now()
        mission.status = DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER; mission.updatedAt = Instant.now()
        return missions.save(mission).toSnapshot(references)
    }
}

data class RelayRerouteRequest(val relayPointId: String, val customerApproved: Boolean, val reason: String, val idempotencyKey: String)

@RestController
class RelayRerouteController(private val relay: RelayFulfillmentService) {
    @PostMapping("/api/delivery/missions/{missionId}/relay-reroute")
    fun reroute(authentication: Authentication?, @PathVariable missionId: String, @RequestBody request: RelayRerouteRequest): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (!authentication.hasAnyRole(RoleGroups.AdminOperations)) ResponseEntity.status(403).build()
        else ResponseEntity.ok(relay.reroute(missionId, authentication.name, request))
}
