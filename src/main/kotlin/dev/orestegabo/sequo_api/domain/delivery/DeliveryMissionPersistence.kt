package dev.orestegabo.sequo_api.domain.delivery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

enum class DeliveryMissionRecordMode { STANDARD, EXPRESS, PROGRAMMED, CLICK_COLLECT, RELAY }
enum class DeliveryMissionRecordDestination { CUSTOMER_ADDRESS, RELAY_POINT, SEQUO_CONSOLIDATION }
enum class DeliveryMissionRecordStatus { CREATED, OFFERED_TO_COURIER, ACCEPTED_BY_COURIER, PICKED_UP_FROM_SELLER, DEPOSITED_AT_RELAY, DELIVERED_TO_CUSTOMER, RELEASED_BY_RELAY, PROBLEM_REPORTED, CANCELLED }

@Entity
@Table(name = "delivery_missions")
class DeliveryMission(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: String? = null,
    @Column(name = "delivery_code", nullable = false, unique = true, length = 64) val deliveryCode: String,
    @Column(name = "order_id", nullable = false) val orderId: String,
    @Column(name = "merchant_sub_order_id") val merchantSubOrderId: String? = null,
    @Column(name = "courier_id") var courierId: String? = null,
    @Enumerated(EnumType.STRING) @Column(name = "delivery_mode", nullable = false, length = 64) val deliveryMode: DeliveryMissionRecordMode,
    @Enumerated(EnumType.STRING) @Column(name = "destination_type", nullable = false, length = 64) val destinationType: DeliveryMissionRecordDestination,
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 64) var status: DeliveryMissionRecordStatus = DeliveryMissionRecordStatus.CREATED,
    @Column(name = "customer_delivery_fee_cfa", nullable = false) val customerDeliveryFeeCfa: Int = 0,
    @Column(name = "courier_fee_cfa", nullable = false) val courierFeeCfa: Int = 0,
    @Column(name = "shortfall_cfa", nullable = false) val shortfallCfa: Int = 0,
    @Column(name = "assigned_at") var assignedAt: Instant? = null,
    @Column(name = "accepted_at") var acceptedAt: Instant? = null,
    @Column(name = "pickup_at") var pickupAt: Instant? = null,
    @Column(name = "relay_deposited_at") var relayDepositedAt: Instant? = null,
    @Column(name = "delivered_at") var deliveredAt: Instant? = null,
    @Column(name = "pickup_proof_metadata") var pickupProofMetadata: String? = null,
    @Column(name = "pickup_proof_actor_id") var pickupProofActorId: String? = null,
    @Column(name = "dropoff_proof_metadata") var dropoffProofMetadata: String? = null,
    @Column(name = "dropoff_proof_actor_id") var dropoffProofActorId: String? = null,
    @Column(name = "relay_deposit_proof_metadata") var relayDepositProofMetadata: String? = null,
    @Column(name = "relay_deposit_proof_actor_id") var relayDepositProofActorId: String? = null,
    @Column(name = "problem_metadata") var problemMetadata: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = createdAt,
    @Version @Column(name = "version", nullable = false) var version: Long = 0,
) {
    init {
        require(deliveryCode.isNotBlank()) { "deliveryCode cannot be blank." }
        require(orderId.isNotBlank()) { "orderId cannot be blank." }
        require(customerDeliveryFeeCfa >= 0 && courierFeeCfa >= 0 && shortfallCfa >= 0) { "Mission amounts cannot be negative." }
    }
}

interface DeliveryMissionRepository : JpaRepository<DeliveryMission, String> {
    fun findByDeliveryCode(deliveryCode: String): DeliveryMission?
    fun findByDeliveryCodeAndOrderId(deliveryCode: String, orderId: String): DeliveryMission?
    fun findByCourierIdOrderByUpdatedAtDesc(courierId: String): List<DeliveryMission>
    fun findByCourierIdAndStatusInOrderByUpdatedAtDesc(
        courierId: String,
        statuses: Collection<DeliveryMissionRecordStatus>,
    ): List<DeliveryMission>
    fun findByStatusInOrderByUpdatedAtDesc(statuses: Collection<DeliveryMissionRecordStatus>): List<DeliveryMission>
    fun countByStatusIn(statuses: Collection<DeliveryMissionRecordStatus>): Long
    fun countByCourierIdIsNullAndStatusIn(statuses: Collection<DeliveryMissionRecordStatus>): Long
    fun countByDestinationTypeAndStatusIn(
        destinationType: DeliveryMissionRecordDestination,
        statuses: Collection<DeliveryMissionRecordStatus>,
    ): Long
    fun countByShortfallCfaGreaterThanAndStatusIn(
        shortfallCfa: Int,
        statuses: Collection<DeliveryMissionRecordStatus>,
    ): Long
    fun findTop20ByStatusInOrderByUpdatedAtDesc(statuses: Collection<DeliveryMissionRecordStatus>): List<DeliveryMission>
}

data class CreateDeliveryMissionCommand(
    val deliveryCode: String,
    val orderId: String,
    val merchantSubOrderId: String? = null,
    val deliveryMode: DeliveryMissionRecordMode,
    val destinationType: DeliveryMissionRecordDestination,
    val customerDeliveryFeeCfa: Int = 0,
    val courierFeeCfa: Int = 0,
    val shortfallCfa: Int = 0,
) {
    init {
        require(deliveryCode.isNotBlank() && orderId.isNotBlank()) { "Mission references cannot be blank." }
        require(customerDeliveryFeeCfa >= 0 && courierFeeCfa >= 0 && shortfallCfa >= 0) { "Mission amounts cannot be negative." }
    }
}

data class DeliveryMissionSnapshot(
    val id: String,
    val deliveryCode: String,
    val orderId: String,
    val merchantSubOrderId: String?,
    val courierId: String?,
    val deliveryMode: DeliveryMissionRecordMode,
    val status: DeliveryMissionRecordStatus,
    val destinationType: DeliveryMissionRecordDestination,
    val assignedAt: Instant?,
    val acceptedAt: Instant?,
    val pickupAt: Instant?,
    val deliveredAt: Instant?,
    val relayDepositedAt: Instant?,
    val shortfallCfa: Int,
    val pickupProofMetadata: String?,
    val pickupProofActorId: String?,
    val dropoffProofMetadata: String?,
    val dropoffProofActorId: String?,
    val problemMetadata: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DeliveryTrackingSnapshot(
    val deliveryCode: String,
    val orderId: String,
    val status: DeliveryMissionRecordStatus,
    val deliveryMode: DeliveryMissionRecordMode,
    val destinationType: DeliveryMissionRecordDestination,
    val courierAssigned: Boolean,
    val currentStep: String,
    val nextStep: String?,
    val terminal: Boolean,
    val acceptedAt: Instant?,
    val pickupAt: Instant?,
    val relayDepositedAt: Instant?,
    val deliveredAt: Instant?,
    val updatedAt: Instant,
)

sealed class DeliveryMissionServiceResult {
    data class Success(val mission: DeliveryMissionSnapshot, val message: String) : DeliveryMissionServiceResult()
    data class Rejected(val code: String, val message: String) : DeliveryMissionServiceResult()
}

@Service
class DeliveryMissionService(
    private val repository: DeliveryMissionRepository,
    private val workflow: DeliveryMissionWorkflow,
) {
    @Transactional(readOnly = true)
    fun get(missionId: String): DeliveryMissionSnapshot? {
        require(missionId.isNotBlank()) { "missionId cannot be blank." }
        return find(missionId)?.toSnapshot()
    }

    @Transactional(readOnly = true)
    fun listForCourier(
        courierId: String,
        statuses: Set<DeliveryMissionRecordStatus> = activeCourierStatuses,
    ): List<DeliveryMissionSnapshot> {
        require(courierId.isNotBlank()) { "courierId cannot be blank." }
        return if (statuses.isEmpty()) {
            repository.findByCourierIdOrderByUpdatedAtDesc(courierId)
        } else {
            repository.findByCourierIdAndStatusInOrderByUpdatedAtDesc(courierId, statuses)
        }.map { it.toSnapshot() }
    }

    @Transactional(readOnly = true)
    fun listOperational(
        statuses: Set<DeliveryMissionRecordStatus> = activeOperationalStatuses,
    ): List<DeliveryMissionSnapshot> {
        require(statuses.isNotEmpty()) { "At least one mission status is required." }
        return repository.findByStatusInOrderByUpdatedAtDesc(statuses).map { it.toSnapshot() }
    }

    @Transactional(readOnly = true)
    fun track(deliveryCode: String, orderId: String): DeliveryTrackingSnapshot? {
        require(deliveryCode.isNotBlank()) { "deliveryCode cannot be blank." }
        require(orderId.isNotBlank()) { "orderId cannot be blank." }
        return repository.findByDeliveryCodeAndOrderId(deliveryCode, orderId)?.toTrackingSnapshot()
    }

    @Transactional
    fun create(command: CreateDeliveryMissionCommand, at: Instant = Instant.now()): DeliveryMissionSnapshot =
        repository.save(
            DeliveryMission(
                deliveryCode = command.deliveryCode,
                orderId = command.orderId,
                merchantSubOrderId = command.merchantSubOrderId,
                deliveryMode = command.deliveryMode,
                destinationType = command.destinationType,
                customerDeliveryFeeCfa = command.customerDeliveryFeeCfa,
                courierFeeCfa = command.courierFeeCfa,
                shortfallCfa = command.shortfallCfa,
                createdAt = at,
                updatedAt = at,
            )
        ).toSnapshot()

    @Transactional
    fun assignCourier(missionId: String, courierId: String, at: Instant = Instant.now()): DeliveryMissionServiceResult {
        if (courierId.isBlank()) return rejected("missing_courier", "Courier id is required.")
        val mission = find(missionId) ?: return rejected("mission_not_found", "Delivery mission was not found.")
        if (mission.status != DeliveryMissionRecordStatus.CREATED) return rejected("invalid_mission_state", "Only a new mission can be assigned.")
        mission.courierId = courierId
        mission.assignedAt = at
        mission.updatedAt = at
        return success(repository.save(mission), "Courier assigned to delivery mission.")
    }

    @Transactional
    fun reassignCourier(
        missionId: String,
        courierId: String,
        at: Instant = Instant.now(),
    ): DeliveryMissionServiceResult {
        if (courierId.isBlank()) return rejected("missing_courier", "Courier id is required.")
        val mission = find(missionId) ?: return rejected("mission_not_found", "Delivery mission was not found.")
        if (mission.status !in reassignableStatuses) {
            return rejected("mission_not_reassignable", "Only missions that have not been picked up can be reassigned.")
        }

        mission.courierId = courierId
        mission.assignedAt = at
        mission.acceptedAt = null
        mission.status = DeliveryMissionRecordStatus.CREATED
        mission.updatedAt = at
        return success(repository.save(mission), "Courier reassigned; mission must be offered again.")
    }

    @Transactional
    fun cancel(
        missionId: String,
        actorId: String,
        reason: String,
        at: Instant = Instant.now(),
    ): DeliveryMissionServiceResult =
        adminTransition(
            missionId = missionId,
            actorId = actorId,
            event = DeliveryMissionEvent.Cancel,
            reason = reason,
            at = at,
            metadataPrefix = "cancelled",
        )

    @Transactional
    fun forceProblem(
        missionId: String,
        actorId: String,
        reason: String,
        at: Instant = Instant.now(),
    ): DeliveryMissionServiceResult =
        adminTransition(
            missionId = missionId,
            actorId = actorId,
            event = DeliveryMissionEvent.ReportProblem,
            reason = reason,
            at = at,
            metadataPrefix = "admin_problem",
        )

    @Transactional(readOnly = true)
    fun validateDirectDeliveryAttempt(
        missionId: String,
        actorId: String,
    ): DeliveryMissionServiceResult {
        val mission = find(missionId) ?: return rejected("mission_not_found", "Delivery mission was not found.")
        if (mission.courierId != actorId) {
            return rejected("courier_scope_mismatch", "Only the assigned courier can update this mission.")
        }
        if (mission.status != DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER) {
            return rejected("invalid_mission_transition", "Package must be picked up before direct delivery.")
        }
        if (mission.destinationType != DeliveryMissionRecordDestination.CUSTOMER_ADDRESS) {
            return rejected("invalid_mission_transition", "Only customer-address missions can be delivered directly.")
        }
        return success(mission, "Direct delivery attempt can proceed.")
    }

    @Transactional
    fun transition(missionId: String, actorId: String, event: DeliveryMissionEvent, proof: String? = null, deliveryPinValidated: Boolean = false, relayPickupValidated: Boolean = false, identityValidated: Boolean = false, problemReason: String? = null, at: Instant = Instant.now()): DeliveryMissionServiceResult {
        val mission = find(missionId) ?: return rejected("mission_not_found", "Delivery mission was not found.")
        if (event in courierScopedEvents && mission.courierId != actorId) {
            return rejected("courier_scope_mismatch", "Only the assigned courier can update this mission.")
        }
        if (proof != null && proof.isNotBlank() && event in proofEvents && proofAlreadySubmitted(mission, event)) {
            return rejected("proof_already_submitted", "Proof for this delivery step was already submitted.")
        }
        val result = workflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = mission.status.toWorkflowStatus(),
                event = event,
                destinationType = mission.destinationType.toWorkflowType(),
                proofProvided = !proof.isNullOrBlank(),
                deliveryPinValidated = deliveryPinValidated,
                relayPickupCodeValidated = relayPickupValidated,
                identityValidated = identityValidated,
                problemReason = problemReason,
            )
        )
        if (!result.accepted) return rejected("invalid_mission_transition", result.reason)
        mission.status = result.nextStatus.toRecordStatus()
        mission.updatedAt = at
        when (event) {
            DeliveryMissionEvent.CourierAccepts -> mission.acceptedAt = at
            DeliveryMissionEvent.CourierPicksUpFromSeller -> { mission.pickupAt = at; mission.pickupProofMetadata = proof; mission.pickupProofActorId = actorId }
            DeliveryMissionEvent.CourierDeliversToCustomer,
            DeliveryMissionEvent.RelayReleasesToCustomer -> { mission.deliveredAt = at; mission.dropoffProofMetadata = proof; mission.dropoffProofActorId = actorId }
            DeliveryMissionEvent.CourierDepositsAtRelay -> { mission.relayDepositedAt = at; mission.relayDepositProofMetadata = proof; mission.relayDepositProofActorId = actorId }
            DeliveryMissionEvent.ReportProblem -> mission.problemMetadata = problemReason
            DeliveryMissionEvent.OfferToCourier, DeliveryMissionEvent.Cancel -> Unit
        }
        return success(repository.save(mission), result.reason)
    }

    private fun adminTransition(
        missionId: String,
        actorId: String,
        event: DeliveryMissionEvent,
        reason: String,
        at: Instant,
        metadataPrefix: String,
    ): DeliveryMissionServiceResult {
        if (actorId.isBlank()) return rejected("missing_actor", "Actor id is required.")
        if (reason.isBlank()) return rejected("missing_reason", "A reason is required.")
        val mission = find(missionId) ?: return rejected("mission_not_found", "Delivery mission was not found.")
        val result = workflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = mission.status.toWorkflowStatus(),
                event = event,
                destinationType = mission.destinationType.toWorkflowType(),
                problemReason = reason,
            )
        )
        if (!result.accepted) return rejected("invalid_mission_transition", result.reason)

        mission.status = result.nextStatus.toRecordStatus()
        mission.problemMetadata = "$metadataPrefix by $actorId: $reason"
        mission.updatedAt = at
        return success(repository.save(mission), result.reason)
    }

    private fun find(id: String) = repository.findById(id).orElse(null)
    private fun proofAlreadySubmitted(mission: DeliveryMission, event: DeliveryMissionEvent) =
        when (event) {
            DeliveryMissionEvent.CourierPicksUpFromSeller -> mission.pickupProofMetadata != null
            DeliveryMissionEvent.CourierDeliversToCustomer,
            DeliveryMissionEvent.RelayReleasesToCustomer -> mission.dropoffProofMetadata != null
            DeliveryMissionEvent.CourierDepositsAtRelay -> mission.relayDepositProofMetadata != null
            else -> false
        }
    private fun success(mission: DeliveryMission, message: String) = DeliveryMissionServiceResult.Success(mission.toSnapshot(), message)
    private fun rejected(code: String, message: String) = DeliveryMissionServiceResult.Rejected(code, message)

    companion object {
        val activeCourierStatuses = setOf(
            DeliveryMissionRecordStatus.CREATED,
            DeliveryMissionRecordStatus.OFFERED_TO_COURIER,
            DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER,
            DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER,
            DeliveryMissionRecordStatus.DEPOSITED_AT_RELAY,
            DeliveryMissionRecordStatus.PROBLEM_REPORTED,
        )
        val activeOperationalStatuses = activeCourierStatuses
        val reassignableStatuses = setOf(
            DeliveryMissionRecordStatus.CREATED,
            DeliveryMissionRecordStatus.OFFERED_TO_COURIER,
            DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER,
        )
        val courierScopedEvents = setOf(
            DeliveryMissionEvent.CourierAccepts,
            DeliveryMissionEvent.CourierPicksUpFromSeller,
            DeliveryMissionEvent.CourierDeliversToCustomer,
            DeliveryMissionEvent.CourierDepositsAtRelay,
            DeliveryMissionEvent.ReportProblem,
        )
        val proofEvents = setOf(
            DeliveryMissionEvent.CourierPicksUpFromSeller,
            DeliveryMissionEvent.CourierDeliversToCustomer,
            DeliveryMissionEvent.CourierDepositsAtRelay,
            DeliveryMissionEvent.RelayReleasesToCustomer,
        )
    }
}

private fun DeliveryMission.toSnapshot() =
    DeliveryMissionSnapshot(
        id = requireNotNull(id),
        deliveryCode = deliveryCode,
        orderId = orderId,
        merchantSubOrderId = merchantSubOrderId,
        courierId = courierId,
        deliveryMode = deliveryMode,
        status = status,
        destinationType = destinationType,
        assignedAt = assignedAt,
        acceptedAt = acceptedAt,
        pickupAt = pickupAt,
        deliveredAt = deliveredAt,
        relayDepositedAt = relayDepositedAt,
        shortfallCfa = shortfallCfa,
        pickupProofMetadata = pickupProofMetadata,
        pickupProofActorId = pickupProofActorId,
        dropoffProofMetadata = dropoffProofMetadata,
        dropoffProofActorId = dropoffProofActorId,
        problemMetadata = problemMetadata,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun DeliveryMission.toTrackingSnapshot() =
    DeliveryTrackingSnapshot(
        deliveryCode = deliveryCode,
        orderId = orderId,
        status = status,
        deliveryMode = deliveryMode,
        destinationType = destinationType,
        courierAssigned = courierId != null,
        currentStep = status.customerFacingStep(destinationType),
        nextStep = status.nextCustomerFacingStep(destinationType),
        terminal = status in trackingTerminalStatuses,
        acceptedAt = acceptedAt,
        pickupAt = pickupAt,
        relayDepositedAt = relayDepositedAt,
        deliveredAt = deliveredAt,
        updatedAt = updatedAt,
    )

private val trackingTerminalStatuses = setOf(
    DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER,
    DeliveryMissionRecordStatus.RELEASED_BY_RELAY,
    DeliveryMissionRecordStatus.PROBLEM_REPORTED,
    DeliveryMissionRecordStatus.CANCELLED,
)

private fun DeliveryMissionRecordStatus.customerFacingStep(destination: DeliveryMissionRecordDestination): String =
    when (this) {
        DeliveryMissionRecordStatus.CREATED -> "Preparing dispatch"
        DeliveryMissionRecordStatus.OFFERED_TO_COURIER -> "Waiting for courier acceptance"
        DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER -> "Courier assigned"
        DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER -> if (destination == DeliveryMissionRecordDestination.RELAY_POINT) {
            "On the way to relay point"
        } else {
            "On the way to customer"
        }
        DeliveryMissionRecordStatus.DEPOSITED_AT_RELAY -> "Available at relay point"
        DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER -> "Delivered"
        DeliveryMissionRecordStatus.RELEASED_BY_RELAY -> "Collected from relay point"
        DeliveryMissionRecordStatus.PROBLEM_REPORTED -> "Delivery needs support"
        DeliveryMissionRecordStatus.CANCELLED -> "Delivery cancelled"
    }

private fun DeliveryMissionRecordStatus.nextCustomerFacingStep(destination: DeliveryMissionRecordDestination): String? =
    when (this) {
        DeliveryMissionRecordStatus.CREATED -> "Courier offer"
        DeliveryMissionRecordStatus.OFFERED_TO_COURIER -> "Courier acceptance"
        DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER -> "Seller pickup"
        DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER -> if (destination == DeliveryMissionRecordDestination.RELAY_POINT) {
            "Relay deposit"
        } else {
            "Customer delivery"
        }
        DeliveryMissionRecordStatus.DEPOSITED_AT_RELAY -> "Customer pickup at relay"
        DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER,
        DeliveryMissionRecordStatus.RELEASED_BY_RELAY,
        DeliveryMissionRecordStatus.PROBLEM_REPORTED,
        DeliveryMissionRecordStatus.CANCELLED -> null
    }
private fun DeliveryMissionRecordStatus.toWorkflowStatus(): DeliveryMissionStatus =
    when (this) {
        DeliveryMissionRecordStatus.CREATED -> DeliveryMissionStatus.Created
        DeliveryMissionRecordStatus.OFFERED_TO_COURIER -> DeliveryMissionStatus.OfferedToCourier
        DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER -> DeliveryMissionStatus.AcceptedByCourier
        DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER -> DeliveryMissionStatus.PickedUpFromSeller
        DeliveryMissionRecordStatus.DEPOSITED_AT_RELAY -> DeliveryMissionStatus.DepositedAtRelay
        DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER -> DeliveryMissionStatus.DeliveredToCustomer
        DeliveryMissionRecordStatus.RELEASED_BY_RELAY -> DeliveryMissionStatus.ReleasedByRelay
        DeliveryMissionRecordStatus.PROBLEM_REPORTED -> DeliveryMissionStatus.ProblemReported
        DeliveryMissionRecordStatus.CANCELLED -> DeliveryMissionStatus.Cancelled
    }

private fun DeliveryMissionStatus.toRecordStatus(): DeliveryMissionRecordStatus =
    when (this) {
        DeliveryMissionStatus.Created -> DeliveryMissionRecordStatus.CREATED
        DeliveryMissionStatus.OfferedToCourier -> DeliveryMissionRecordStatus.OFFERED_TO_COURIER
        DeliveryMissionStatus.AcceptedByCourier -> DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER
        DeliveryMissionStatus.PickedUpFromSeller -> DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER
        DeliveryMissionStatus.DepositedAtRelay -> DeliveryMissionRecordStatus.DEPOSITED_AT_RELAY
        DeliveryMissionStatus.DeliveredToCustomer -> DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER
        DeliveryMissionStatus.ReleasedByRelay -> DeliveryMissionRecordStatus.RELEASED_BY_RELAY
        DeliveryMissionStatus.ProblemReported -> DeliveryMissionRecordStatus.PROBLEM_REPORTED
        DeliveryMissionStatus.Cancelled -> DeliveryMissionRecordStatus.CANCELLED
    }

private fun DeliveryMissionRecordDestination.toWorkflowType(): DeliveryDestinationType =
    when (this) {
        DeliveryMissionRecordDestination.CUSTOMER_ADDRESS -> DeliveryDestinationType.CustomerAddress
        DeliveryMissionRecordDestination.RELAY_POINT -> DeliveryDestinationType.RelayPoint
        DeliveryMissionRecordDestination.SEQUO_CONSOLIDATION -> DeliveryDestinationType.SequoConsolidation
    }
