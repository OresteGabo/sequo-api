package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry

import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import org.springframework.web.bind.annotation.*

@Service
class PersistedDeliveryAssignmentService(private val missions: DeliveryMissionRepository,
    private val couriers: SettlementCourierRecordRepository,
    private val delivery: DeliveryMissionService, private val orders: CustomerOrderRecordRepository,
    private val eligibility: CourierDispatchEligibility,
    private val references: PublicReferenceRegistry? = null) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun assign(missionId: String): DeliveryMissionServiceResult {
        val mission = requireNotNull(missions.lockForAction(missionId)) { "Mission is unavailable." }
        if (mission.courierId != null) return DeliveryMissionServiceResult.Success(mission.toSnapshot(references), "Existing assignment retained.")
        require(mission.status == DeliveryMissionRecordStatus.CREATED) { "Only an unassigned new mission can be assigned by policy." }
        val order = requireNotNull(orders.findById(mission.orderId).orElse(null)) { "Order is unavailable." }
        require(order.paymentStatus == PaymentValidationStatus.Validated && order.orderStatus == CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT) { "Order is not paid and active." }
        // Lock the authoritative candidates in a stable order; two missions cannot consume the same last capacity.
        val candidates = couriers.lockActiveForDispatch().mapNotNull { eligibility.candidate(it, missionId) }
        val result = DeliveryAssignmentPolicy().assign(DeliveryAssignmentRequest(
            channel = when (mission.deliveryMode) {
                DeliveryMissionRecordMode.EXPRESS -> DeliveryAssignmentChannel.Express
                DeliveryMissionRecordMode.PROGRAMMED -> DeliveryAssignmentChannel.ProgrammedConsolidation
                else -> DeliveryAssignmentChannel.StandardLocal
            }, subscriberOrder = mission.subscriberPriority, customerDeliveryFeeCfa = mission.customerDeliveryFeeCfa,
            freelancerCourierFeeCfa = mission.freelancerFeeEstimateCfa ?: mission.courierFeeCfa, availableCouriers = candidates))
        if (result is DeliveryAssignmentResult.Unassigned) return DeliveryMissionServiceResult.Rejected("no_courier_capacity", result.reason)
        val assignment = (result as DeliveryAssignmentResult.Assigned).assignment
        val saved = delivery.assignCourier(missionId, assignment.courierId)
        require(saved is DeliveryMissionServiceResult.Success) { "Assignment could not be saved." }
        mission.assignmentWorkforceType = assignment.workforceType; mission.assignmentReason = assignment.reason
        mission.courierFeeCfa = assignment.freelancerPayableCfa; mission.shortfallCfa = assignment.sequoShortfallCfa
        return DeliveryMissionServiceResult.Success(missions.save(mission).toSnapshot(references), assignment.reason)
    }
}

@RestController
class PersistedDeliveryAssignmentController(private val service: GuardedDeliveryAssignmentService) {
    @PostMapping("/api/delivery/missions/{missionId}/assign-policy")
    fun assign(authentication: Authentication?, @PathVariable missionId: String,
        @RequestBody(required = false) request: DeliveryAdminReasonRequest? = null): ResponseEntity<Any> =
        if (authentication == null) ResponseEntity.status(401).build()
        else if (!authentication.hasAnyRole(RoleGroups.AdminOnly)) ResponseEntity.status(403).build()
        else when (val result = service.assignPolicy(missionId, authentication.name, request?.reason, request?.idempotencyKey)) {
            is DeliveryMissionServiceResult.Success -> ResponseEntity.ok(result.mission)
            is DeliveryMissionServiceResult.Rejected -> ResponseEntity.status(409).body(result)
        }
}
