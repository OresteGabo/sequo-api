package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.party.CourierRecord
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import dev.orestegabo.sequo_api.domain.settlement.courierWorkforce
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Shared by automated policy selection and explicit operator assignment. Capacity remains one. */
@Service
class CourierDispatchEligibility(
    private val users: UserRepository,
    private val availability: CourierAvailabilityService,
    private val missions: DeliveryMissionRepository,
) {
    private val busy = setOf(DeliveryMissionRecordStatus.CREATED, DeliveryMissionRecordStatus.OFFERED_TO_COURIER,
        DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER, DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER, DeliveryMissionRecordStatus.PROBLEM_REPORTED)

    fun rejection(courier: CourierRecord?, missionId: String, at: Instant = Instant.now()): DeliveryMissionServiceResult.Rejected? {
        if (courier == null) return reject("courier_not_found", "Select an existing courier.")
        val user = courier.userId?.let { users.findById(it).orElse(null) }
        if (courier.status != "ACTIVE" || user == null || !user.active || !user.status.canAuthenticate() || RoleCode.COURIER !in user.roles) {
            return reject("courier_unavailable", "An active linked courier account is required for a new assignment.")
        }
        if (availability.isPaused(courier.id, at)) return reject("courier_paused", "Courier is paused and cannot receive delivery missions.")
        if (courierWorkforce(courier.workforceType) == null) return reject("courier_workforce_unknown", "Review the courier workforce before assigning.")
        if (DeliveryVehicleType.entries.none { it.name.equals(courier.vehicleType, true) }) return reject("courier_vehicle_unknown", "A supported courier vehicle is required.")
        if (missions.countByCourierIdAndStatusInAndIdNot(courier.id, busy, missionId) != 0L) {
            return reject("courier_capacity_exhausted", "Courier already has an active mission.")
        }
        return null
    }

    fun candidate(courier: CourierRecord, missionId: String): AvailableCourier? {
        if (rejection(courier, missionId) != null) return null
        return AvailableCourier(courier.id, requireNotNull(courierWorkforce(courier.workforceType)),
            DeliveryVehicleType.entries.first { it.name.equals(courier.vehicleType, true) }, 1)
    }

    private fun reject(code: String, message: String) = DeliveryMissionServiceResult.Rejected(code, message)
}

/** HTTP operations verify paid persisted ownership context; legacy domain records remain readable. */
@Service
class GuardedDeliveryAssignmentService(
    private val missions: DeliveryMissionRepository,
    private val couriers: SettlementCourierRecordRepository,
    private val orders: CustomerOrderRecordRepository,
    private val eligibility: CourierDispatchEligibility,
    private val delivery: DeliveryMissionService,
    private val audit: DeliveryAdminAudit,
    private val policy: PersistedDeliveryAssignmentService,
    private val availability: CourierAvailabilityService,
    private val references: PublicReferenceRegistry? = null,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun assign(missionId: String, courierId: String, actor: String, reason: String? = null, key: String? = null, reassign: Boolean = false): DeliveryMissionServiceResult {
        if (courierId.isBlank()) return reject("missing_courier", "Courier id is required.")
        val mission = missions.lockForAction(missionId) ?: return reject("mission_not_found", "Delivery mission was not found.")
        val intent = audit.intent("DELIVERY_MISSION", missionId, if (reassign) "REASSIGN_COURIER" else "ASSIGN_COURIER", actor, courierId, reason, key)
        if (audit.replay(intent)) return DeliveryMissionServiceResult.Success(mission.toSnapshot(references), "Recorded action retained.")
        // Replay never resets a proof/acceptance timestamp or applies today's workforce to an old assignment.
        if (mission.courierId == courierId && mission.assignedAt != null) {
            audit.record(intent, mission.courierId, mission.courierId)
            return DeliveryMissionServiceResult.Success(mission.toSnapshot(references), "Existing assignment retained.")
        }
        if (reassign && mission.status !in DeliveryMissionService.reassignableStatuses) return reject("mission_not_reassignable", "Only missions before pickup can be reassigned.")
        if (!reassign && (mission.status != DeliveryMissionRecordStatus.CREATED || mission.courierId != null)) return reject("invalid_mission_state", "Use explicit reassignment for a mission already assigned.")
        paidOrderRejection(mission)?.let { return it }
        // Same courier-row lock is used by policy selection and administrator workforce updates.
        val courier = couriers.lockForAdministration(courierId)
        eligibility.rejection(courier, missionId)?.let { return it }
        val before = mission.courierId
        val result = if (reassign) delivery.reassignCourier(missionId, courierId) else delivery.assignCourier(missionId, courierId)
        if (result is DeliveryMissionServiceResult.Success) audit.record(intent, before, result.mission.courierId)
        return result
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun offer(missionId: String, actor: String, reason: String? = null, key: String? = null): DeliveryMissionServiceResult {
        val mission = missions.lockForAction(missionId) ?: return reject("mission_not_found", "Delivery mission was not found.")
        val intent = audit.intent("DELIVERY_MISSION", missionId, "OFFER_MISSION", actor, "OFFERED_TO_COURIER", reason, key)
        if (audit.replay(intent)) return DeliveryMissionServiceResult.Success(mission.toSnapshot(references), "Recorded action retained.")
        paidOrderRejection(mission)?.let { return it }
        val courierId = mission.courierId ?: return reject("courier_assignment_required", "Assign an eligible courier before offering this mission.")
        eligibility.rejection(couriers.lockForAdministration(courierId), missionId)?.let { return it }
        val before = mission.status.name
        val result = delivery.transition(missionId, actor, DeliveryMissionEvent.OfferToCourier)
        if (result is DeliveryMissionServiceResult.Success) audit.record(intent, before, result.mission.status.name)
        return result
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun assignPolicy(missionId: String, actor: String, reason: String?, key: String?): DeliveryMissionServiceResult {
        val mission = missions.lockForAction(missionId) ?: return reject("mission_not_found", "Delivery mission was not found.")
        val intent = audit.intent("DELIVERY_MISSION", missionId, "ASSIGN_POLICY", actor, null, reason, key)
        if (audit.replay(intent)) return DeliveryMissionServiceResult.Success(mission.toSnapshot(references), "Recorded action retained.")
        val before = mission.courierId
        val result = policy.assign(missionId)
        if (result is DeliveryMissionServiceResult.Success) audit.record(intent, before, result.mission.courierId)
        return result
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun unpause(courierId: String, actor: String, reason: String?, key: String?): CourierAvailabilitySnapshot {
        requireNotNull(couriers.lockForAdministration(courierId)) { "Select an existing courier." }
        val intent = audit.intent("COURIER_AVAILABILITY", courierId, "UNPAUSE_COURIER", actor, "ACTIVE", reason, key)
        if (audit.replay(intent)) return availability.get(courierId)
        val before = availability.get(courierId).status.name
        val result = availability.unpause(courierId, actor)
        audit.record(intent, before, result.status.name)
        return result
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun resolve(missionId: String, actor: String, action: DeliveryProblemResolutionAction, reason: String, replacement: String?): DeliveryMissionServiceResult {
        val mission = missions.lockForAction(missionId) ?: return reject("mission_not_found", "Delivery mission was not found.")
        if (action == DeliveryProblemResolutionAction.REQUEUE_FOR_DISPATCH) {
            paidOrderRejection(mission)?.let { return it }
            replacement?.let { eligibility.rejection(couriers.lockForAdministration(it), missionId)?.let { rejection -> return rejection } }
        }
        return delivery.resolveProblem(missionId, actor, action, reason, replacement)
    }

    private fun paidOrderRejection(mission: DeliveryMission): DeliveryMissionServiceResult.Rejected? {
        val order = orders.findById(mission.orderId).orElse(null)
            ?: return reject("order_unverified_for_assignment", "Legacy order payment is unavailable; review it before a new assignment. Existing records remain readable.")
        return if (order.paymentStatus == PaymentValidationStatus.Validated && order.orderStatus == CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT) null
        else reject("order_not_paid_active", "Only a paid active order may enter dispatch.")
    }
    private fun reject(code: String, message: String) = DeliveryMissionServiceResult.Rejected(code, message)
}
