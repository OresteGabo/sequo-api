package dev.orestegabo.sequo_api.domain.delivery

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class DeliveryMissionServiceTest @Autowired constructor(
    private val service: DeliveryMissionService,
    private val repository: DeliveryMissionRepository,
    private val courierAvailability: CourierAvailabilityService,
) {
    @Test
    fun persistsAssignedCourierProofAndDirectDelivery() {
        val mission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "MISSION-1",
                orderId = "order-1",
                deliveryMode = DeliveryMissionRecordMode.EXPRESS,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
                customerDeliveryFeeCfa = 400,
                courierFeeCfa = 700,
                shortfallCfa = 300,
            )
        )
        service.assignCourier(mission.id, "courier-1")
        service.transition(mission.id, "admin-1", DeliveryMissionEvent.OfferToCourier)
        service.transition(mission.id, "other-courier", DeliveryMissionEvent.CourierAccepts).also {
            assertTrue(it is DeliveryMissionServiceResult.Rejected)
        }
        service.transition(mission.id, "courier-1", DeliveryMissionEvent.CourierAccepts)
        service.transition(mission.id, "courier-1", DeliveryMissionEvent.CourierPicksUpFromSeller, proof = "pickup-proof")
        val delivered = service.transition(mission.id, "courier-1", DeliveryMissionEvent.CourierDeliversToCustomer, proof = "dropoff-proof")

        assertTrue(delivered is DeliveryMissionServiceResult.Success)
        assertEquals(DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, delivered.mission.status)
        assertEquals("pickup-proof", repository.findById(mission.id).orElseThrow().pickupProofMetadata)
        assertEquals("courier-1", repository.findById(mission.id).orElseThrow().pickupProofActorId)
        assertEquals("courier-1", repository.findById(mission.id).orElseThrow().dropoffProofActorId)
        assertEquals(300, delivered.mission.shortfallCfa)
    }

    @Test
    fun relayDeliveryRequiresProofAndValidatedCustomerRelease() {
        val mission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "MISSION-2",
                orderId = "order-2",
                deliveryMode = DeliveryMissionRecordMode.RELAY,
                destinationType = DeliveryMissionRecordDestination.RELAY_POINT,
            )
        )
        service.assignCourier(mission.id, "courier-2")
        service.transition(mission.id, "admin-1", DeliveryMissionEvent.OfferToCourier)
        service.transition(mission.id, "courier-2", DeliveryMissionEvent.CourierAccepts)
        service.transition(mission.id, "courier-2", DeliveryMissionEvent.CourierPicksUpFromSeller, proof = "pickup-proof")
        val deposited = service.transition(mission.id, "courier-2", DeliveryMissionEvent.CourierDepositsAtRelay, proof = "relay-proof")
        val blocked = service.transition(mission.id, "relay-1", DeliveryMissionEvent.RelayReleasesToCustomer, relayPickupValidated = true)
        val released = service.transition(mission.id, "relay-1", DeliveryMissionEvent.RelayReleasesToCustomer, relayPickupValidated = true, identityValidated = true, proof = "release-proof")
        val releasedSuccess = released as DeliveryMissionServiceResult.Success

        assertTrue(deposited is DeliveryMissionServiceResult.Success)
        assertTrue(blocked is DeliveryMissionServiceResult.Rejected)
        assertEquals(DeliveryMissionRecordStatus.RELEASED_BY_RELAY, releasedSuccess.mission.status)
        assertEquals("relay-1", repository.findById(mission.id).orElseThrow().dropoffProofActorId)
    }

    @Test
    fun courierScopedActionsRejectAnotherCourier() {
        val mission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "MISSION-3",
                orderId = "order-3",
                deliveryMode = DeliveryMissionRecordMode.EXPRESS,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            )
        )
        service.assignCourier(mission.id, "courier-3")
        service.transition(mission.id, "admin-1", DeliveryMissionEvent.OfferToCourier)
        service.transition(mission.id, "courier-3", DeliveryMissionEvent.CourierAccepts)

        val pickup = service.transition(mission.id, "other-courier", DeliveryMissionEvent.CourierPicksUpFromSeller, proof = "pickup-proof")
        val problem = service.transition(mission.id, "other-courier", DeliveryMissionEvent.ReportProblem, problemReason = "Not assigned")

        assertTrue(pickup is DeliveryMissionServiceResult.Rejected)
        assertTrue(problem is DeliveryMissionServiceResult.Rejected)
        assertEquals("courier_scope_mismatch", pickup.code)
        assertEquals("courier_scope_mismatch", problem.code)
    }

    @Test
    fun idempotentTransitionReplaysSuccessfulOperationAndRejectsKeyConflicts() {
        val mission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "MISSION-IDEMPOTENT-1",
                orderId = "order-idempotent-1",
                deliveryMode = DeliveryMissionRecordMode.EXPRESS,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            )
        )
        service.assignCourier(mission.id, "courier-idempotent")
        service.transition(mission.id, "admin-1", DeliveryMissionEvent.OfferToCourier)
        service.transition(mission.id, "courier-idempotent", DeliveryMissionEvent.CourierAccepts)

        val first = service.transitionIdempotent(
            missionId = mission.id,
            actorId = "courier-idempotent",
            event = DeliveryMissionEvent.CourierPicksUpFromSeller,
            idempotencyKey = "pickup-idempotent-1",
            proof = "pickup-proof",
        )
        val replay = service.transitionIdempotent(
            missionId = mission.id,
            actorId = "courier-idempotent",
            event = DeliveryMissionEvent.CourierPicksUpFromSeller,
            idempotencyKey = "pickup-idempotent-1",
            proof = "different-proof",
        )
        val conflict = service.transitionIdempotent(
            missionId = mission.id,
            actorId = "courier-idempotent",
            event = DeliveryMissionEvent.CourierDeliversToCustomer,
            idempotencyKey = "pickup-idempotent-1",
            proof = "dropoff-proof",
        )

        assertTrue(first is DeliveryMissionServiceResult.Success)
        assertTrue(replay is DeliveryMissionServiceResult.Success)
        assertEquals(DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER, replay.mission.status)
        assertEquals("pickup-proof", repository.findById(mission.id).orElseThrow().pickupProofMetadata)
        assertTrue(conflict is DeliveryMissionServiceResult.Rejected)
        assertEquals("idempotency_key_conflict", conflict.code)
    }

    @Test
    fun staleOfferedAndAcceptedMissionsExpireToSupportProblemState() {
        val evaluatedAt = Instant.parse("2026-09-09T10:00:00Z")
        val staleAt = evaluatedAt.minusSeconds(60 * 60)
        val freshAt = evaluatedAt.minusSeconds(5 * 60)

        val staleOffer = createOfferedMission("MISSION-EXPIRY-OFFER", "courier-expiry-offer", staleAt)
        val staleAccepted = createOfferedMission("MISSION-EXPIRY-ACCEPTED", "courier-expiry-accepted", staleAt)
        service.transition(
            staleAccepted.id,
            "courier-expiry-accepted",
            DeliveryMissionEvent.CourierAccepts,
            at = staleAt,
        )
        val freshOffer = createOfferedMission("MISSION-EXPIRY-FRESH", "courier-expiry-fresh", freshAt)

        val expired = service.expireStaleMissions(
            evaluatedAt = evaluatedAt,
            offerTimeout = Duration.ofMinutes(20),
            pickupTimeout = Duration.ofMinutes(45),
            limit = 10,
        )

        assertEquals(setOf(staleOffer.id, staleAccepted.id), expired.map { it.id }.toSet())
        repository.findById(staleOffer.id).orElseThrow().also {
            assertEquals(DeliveryMissionRecordStatus.PROBLEM_REPORTED, it.status)
            assertEquals(
                "auto_no_show at 2026-09-09T10:00:00Z: Courier offer expired before acceptance.",
                it.problemMetadata,
            )
        }
        repository.findById(staleAccepted.id).orElseThrow().also {
            assertEquals(DeliveryMissionRecordStatus.PROBLEM_REPORTED, it.status)
            assertEquals(
                "auto_no_show at 2026-09-09T10:00:00Z: Courier accepted mission but did not pick up before deadline.",
                it.problemMetadata,
            )
        }
        assertEquals(DeliveryMissionRecordStatus.OFFERED_TO_COURIER, repository.findById(freshOffer.id).orElseThrow().status)
    }

    @Test
    fun expiryScanRejectsUnsafeBounds() {
        val invalidLimit = kotlin.runCatching {
            service.expireStaleMissions(limit = 0)
        }
        val invalidTimeout = kotlin.runCatching {
            service.expireStaleMissions(offerTimeout = Duration.ZERO)
        }

        assertTrue(invalidLimit.exceptionOrNull() is IllegalArgumentException)
        assertTrue(invalidTimeout.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun supportCanRequeueProblemMissionBeforePickupWithAuditTrail() {
        val mission = createOfferedMission("MISSION-RESOLVE-REQUEUE", "courier-resolve-old", Instant.parse("2026-09-09T09:00:00Z"))
        service.forceProblem(mission.id, "support-resolve", "Courier did not answer.")

        val resolved = service.resolveProblem(
            missionId = mission.id,
            actorId = "support-resolve",
            action = DeliveryProblemResolutionAction.REQUEUE_FOR_DISPATCH,
            reason = "Assign to another courier.",
            replacementCourierId = "courier-resolve-new",
            at = Instant.parse("2026-09-09T10:00:00Z"),
        )

        assertTrue(resolved is DeliveryMissionServiceResult.Success)
        assertEquals(DeliveryMissionRecordStatus.CREATED, resolved.mission.status)
        assertEquals("courier-resolve-new", resolved.mission.courierId)
        assertEquals(Instant.parse("2026-09-09T10:00:00Z"), resolved.mission.assignedAt)
        assertEquals("resolved_requeued by support-resolve: Assign to another courier.", resolved.mission.problemMetadata)
        service.listProblemResolutions(mission.id).single().also {
            assertEquals(DeliveryProblemResolutionAction.REQUEUE_FOR_DISPATCH, it.action)
            assertEquals("courier-resolve-new", it.replacementCourierId)
            assertEquals("Assign to another courier.", it.reason)
        }
    }

    @Test
    fun supportCannotRequeuePickedUpProblemMissionWithoutInvestigation() {
        val mission = createOfferedMission("MISSION-RESOLVE-PICKED", "courier-resolve-picked", Instant.parse("2026-09-09T09:00:00Z"))
        service.transition(mission.id, "courier-resolve-picked", DeliveryMissionEvent.CourierAccepts)
        service.transition(mission.id, "courier-resolve-picked", DeliveryMissionEvent.CourierPicksUpFromSeller, proof = "pickup-proof")
        service.transition(mission.id, "courier-resolve-picked", DeliveryMissionEvent.ReportProblem, problemReason = "Customer unavailable.")

        val resolved = service.resolveProblem(
            missionId = mission.id,
            actorId = "support-resolve",
            action = DeliveryProblemResolutionAction.REQUEUE_FOR_DISPATCH,
            reason = "Try again with another courier.",
            replacementCourierId = "courier-resolve-new",
        )

        assertTrue(resolved is DeliveryMissionServiceResult.Rejected)
        assertEquals("mission_already_in_custody", resolved.code)
        assertEquals(DeliveryMissionRecordStatus.PROBLEM_REPORTED, requireNotNull(service.get(mission.id)).status)
        assertTrue(service.listProblemResolutions(mission.id).isEmpty())
    }

    @Test
    fun supportCanCancelProblemMissionWithAuditTrail() {
        val mission = createOfferedMission("MISSION-RESOLVE-CANCEL", "courier-resolve-cancel", Instant.parse("2026-09-09T09:00:00Z"))
        service.forceProblem(mission.id, "support-resolve", "Seller cancelled after dispatch.")

        val resolved = service.resolveProblem(
            missionId = mission.id,
            actorId = "support-resolve",
            action = DeliveryProblemResolutionAction.CANCEL_MISSION,
            reason = "Order cancelled by support.",
            at = Instant.parse("2026-09-09T11:00:00Z"),
        )

        assertTrue(resolved is DeliveryMissionServiceResult.Success)
        assertEquals(DeliveryMissionRecordStatus.CANCELLED, resolved.mission.status)
        assertEquals("resolved_cancelled by support-resolve: Order cancelled by support.", resolved.mission.problemMetadata)
        service.listProblemResolutions(mission.id).single().also {
            assertEquals(DeliveryProblemResolutionAction.CANCEL_MISSION, it.action)
            assertEquals("support-resolve", it.actorUserId)
            assertEquals(Instant.parse("2026-09-09T11:00:00Z"), it.resolvedAt)
        }
    }

    @Test
    fun pausedCourierCannotReceiveAssignmentOrReassignment() {
        val pausedAt = Instant.parse("2026-09-09T09:00:00Z")
        courierAvailability.pause(
            courierId = "courier-paused-assignment",
            actorUserId = "admin-pause",
            reason = "Temporary support pause.",
            at = pausedAt,
        )
        val mission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "MISSION-PAUSED-ASSIGN",
                orderId = "order-paused-assign",
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            ),
            at = pausedAt,
        )
        val activeMission = createOfferedMission("MISSION-PAUSED-REASSIGN", "courier-active-before-reassign", pausedAt)

        val assignment = service.assignCourier(mission.id, "courier-paused-assignment", pausedAt)
        val reassignment = service.reassignCourier(activeMission.id, "courier-paused-assignment", pausedAt)

        assertTrue(assignment is DeliveryMissionServiceResult.Rejected)
        assertEquals("courier_paused", assignment.code)
        assertTrue(reassignment is DeliveryMissionServiceResult.Rejected)
        assertEquals("courier_paused", reassignment.code)
        assertEquals(DeliveryMissionRecordStatus.CREATED, requireNotNull(service.get(mission.id)).status)
        assertEquals("courier-active-before-reassign", requireNotNull(service.get(activeMission.id)).courierId)
    }

    private fun createOfferedMission(
        deliveryCode: String,
        courierId: String,
        at: Instant,
    ): DeliveryMissionSnapshot {
        val mission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = deliveryCode,
                orderId = "order-$deliveryCode",
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            ),
            at = at,
        )
        service.assignCourier(mission.id, courierId, at)
        service.transition(mission.id, "admin-expiry", DeliveryMissionEvent.OfferToCourier, at = at)
        return requireNotNull(service.get(mission.id))
    }
}
