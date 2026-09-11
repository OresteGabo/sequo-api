package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.order.FulfillmentPriority
import dev.orestegabo.sequo_api.domain.order.OrderRoute
import dev.orestegabo.sequo_api.domain.order.OrderServiceLevel
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class DeliveryMissionControllerTest @Autowired constructor(
    private val controller: DeliveryMissionController,
    private val trackingController: DeliveryTrackingController,
    private val service: DeliveryMissionService,
    private val merchantFulfillment: MerchantFulfillmentService,
    private val dispatchService: DeliveryReadinessDispatchService,
    private val pinRepository: DeliveryPinRepository,
    private val orders: CustomerOrderRecordRepository,
) {
    @Test
    fun courierListAndDetailAreLimitedToAssignedMissions() {
        val admin = auth("admin-ops", RoleCode.ADMIN)
        val courierOne = auth("courier-list-1", RoleCode.COURIER)
        val missionOne = createAssignedAndOfferedMission(
            deliveryCode = "CTRL-LIST-1",
            orderId = "order-list-1",
            courierId = "courier-list-1",
            admin = admin,
        )
        val missionTwo = createAssignedAndOfferedMission(
            deliveryCode = "CTRL-LIST-2",
            orderId = "order-list-2",
            courierId = "courier-list-2",
            admin = admin,
        )

        val ownList = controller.list(courierOne, "courier-list-1", courierId = null, status = null)
        val otherCourierList = controller.list(courierOne, "courier-list-1", courierId = "courier-list-2", status = null)
        val otherCourierDetail = controller.get(courierOne, "courier-list-1", missionTwo.id)
        val adminDetail = controller.get(admin, "admin-ops", missionTwo.id)

        assertEquals(HttpStatus.OK, ownList.statusCode)
        assertEquals(listOf(missionOne.id), ownList.bodyAs<List<DeliveryMissionSnapshot>>().map { it.id })
        assertEquals(HttpStatus.FORBIDDEN, otherCourierList.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, otherCourierDetail.statusCode)
        assertEquals(HttpStatus.OK, adminDetail.statusCode)
    }

    @Test
    fun adminCreatesDirectDeliveryPinWithoutExposingRawSecret() {
        val admin = auth("admin-pin", RoleCode.ADMIN)
        val directMission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "CTRL-PIN-DIRECT",
                orderId = "order-pin-direct",
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            )
        )
        val relayMission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "CTRL-PIN-RELAY",
                orderId = "order-pin-relay",
                deliveryMode = DeliveryMissionRecordMode.RELAY,
                destinationType = DeliveryMissionRecordDestination.RELAY_POINT,
            )
        )

        val created = controller.createDeliveryPin(
            admin,
            directMission.id,
            DeliveryMissionController.CreateDeliveryPinRequest(
                rawPin = "123456",
                expiresAt = Instant.now().plusSeconds(3600),
            )
        )
        val relayRejected = controller.createDeliveryPin(
            admin,
            relayMission.id,
            DeliveryMissionController.CreateDeliveryPinRequest(
                rawPin = "123456",
                expiresAt = Instant.now().plusSeconds(3600),
            )
        )

        assertEquals(HttpStatus.OK, created.statusCode)
        assertFalse(created.body.toString().contains("123456"))
        val pin = created.bodyAs<DeliveryPinSnapshot>()
        assertTrue(pinRepository.findById(pin.id).orElseThrow().pinHash.startsWith("sha256:"))
        assertEquals(HttpStatus.BAD_REQUEST, relayRejected.statusCode)
    }

    @Test
    fun adminCanDispatchPackedMerchantSubOrders() {
        val admin = auth("admin-dispatch-ready", RoleCode.ADMIN)
        val subOrder = merchantFulfillment.create(
            CreateMerchantSubOrderCommand(
                subOrderCode = "sub-order-dispatch-ready",
                orderId = "missing-order-for-controller-dispatch",
                merchantId = "merchant-dispatch-ready",
                itemSubtotalCfa = 2_000,
                commissionCfa = 300,
                merchantNetCfa = 1_700,
            )
        )
        merchantFulfillment.accept(subOrder.id, subOrder.merchantId)
        merchantFulfillment.startPreparation(subOrder.id, subOrder.merchantId)
        merchantFulfillment.markPacked(subOrder.id, subOrder.merchantId, packageCount = 1)

        val dispatched = controller.dispatchReady(admin, limit = 10)

        assertEquals(HttpStatus.OK, dispatched.statusCode)
        dispatched.bodyAs<DeliveryDispatchRunResult>().also {
            assertTrue(it.skippedSubOrders.any { skipped -> skipped.subOrderId == subOrder.id })
        }
        assertEquals(0, dispatchService.dispatchReadySubOrders().createdMissions.size)
    }

    @Test
    fun adminCanExpireStaleCourierMissions() {
        val admin = auth("admin-expire-stale", RoleCode.ADMIN)
        val evaluatedAt = Instant.parse("2026-09-09T12:00:00Z")
        val staleAt = evaluatedAt.minus(Duration.ofHours(2))
        val mission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "CTRL-EXPIRE-STALE",
                orderId = "order-expire-stale",
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            ),
            at = staleAt,
        )
        service.assignCourier(mission.id, "courier-expire-stale", staleAt)
        service.transition(mission.id, "admin-expire-stale", DeliveryMissionEvent.OfferToCourier, at = staleAt)

        val response = controller.expireStale(
            admin,
            DeliveryMissionController.ExpireStaleMissionsRequest(
                evaluatedAt = evaluatedAt,
                offerTimeoutMinutes = 20,
                pickupTimeoutMinutes = 45,
                limit = 20,
            ),
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        response.bodyAs<List<DeliveryMissionSnapshot>>().single().also {
            assertEquals(mission.id, it.id)
            assertEquals(DeliveryMissionRecordStatus.PROBLEM_REPORTED, it.status)
            assertTrue(requireNotNull(it.problemMetadata).contains("auto_no_show"))
        }
    }

    @Test
    fun adminCanReassignCancelAndForceProblemBeforePickup() {
        val admin = auth("admin-dispatch", RoleCode.ADMIN)
        val oldCourier = auth("courier-dispatch-old", RoleCode.COURIER)
        val mission = createAssignedAndOfferedMission(
            deliveryCode = "CTRL-DISPATCH",
            orderId = "order-dispatch",
            courierId = "courier-dispatch-old",
            admin = admin,
        )
        controller.accept(oldCourier, "courier-dispatch-old", mission.id)

        val reassigned = controller.reassign(
            admin,
            mission.id,
            DeliveryMissionController.AssignCourierRequest("courier-dispatch-new"),
        )
        val oldCourierBlocked = controller.accept(oldCourier, "courier-dispatch-old", mission.id)

        assertEquals(HttpStatus.OK, reassigned.statusCode)
        reassigned.bodyAs<DeliveryMissionSnapshot>().also {
            assertEquals("courier-dispatch-new", it.courierId)
            assertEquals(DeliveryMissionRecordStatus.CREATED, it.status)
            assertNull(it.acceptedAt)
        }
        assertEquals(HttpStatus.BAD_REQUEST, oldCourierBlocked.statusCode)

        val cancelledMission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "CTRL-DISPATCH-CANCEL",
                orderId = "order-dispatch-cancel",
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            )
        )
        val cancelled = controller.cancel(
            admin,
            cancelledMission.id,
            DeliveryMissionController.ProblemRequest("Customer requested cancellation through support."),
        )

        assertEquals(HttpStatus.OK, cancelled.statusCode)
        cancelled.bodyAs<DeliveryMissionSnapshot>().also {
            assertEquals(DeliveryMissionRecordStatus.CANCELLED, it.status)
            assertEquals("cancelled by admin-dispatch: Customer requested cancellation through support.", it.problemMetadata)
        }

        val problemMission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "CTRL-DISPATCH-PROBLEM",
                orderId = "order-dispatch-problem",
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            )
        )
        val forcedProblem = controller.forceProblem(
            admin,
            problemMission.id,
            DeliveryMissionController.ProblemRequest("Courier no-show; support intervention required."),
        )

        assertEquals(HttpStatus.OK, forcedProblem.statusCode)
        forcedProblem.bodyAs<DeliveryMissionSnapshot>().also {
            assertEquals(DeliveryMissionRecordStatus.PROBLEM_REPORTED, it.status)
            assertEquals("admin_problem by admin-dispatch: Courier no-show; support intervention required.", it.problemMetadata)
        }
    }

    @Test
    fun adminCanResolveProblemMissionAndReadResolutionHistory() {
        val admin = auth("admin-resolve-problem", RoleCode.ADMIN)
        val mission = createAssignedAndOfferedMission(
            deliveryCode = "CTRL-RESOLVE-PROBLEM",
            orderId = "order-resolve-problem",
            courierId = "courier-resolve-problem-old",
            admin = admin,
        )
        controller.forceProblem(
            admin,
            mission.id,
            DeliveryMissionController.ProblemRequest("Courier no-show."),
        )

        val resolved = controller.resolveProblem(
            admin,
            mission.id,
            DeliveryMissionController.ResolveProblemRequest(
                action = DeliveryProblemResolutionAction.REQUEUE_FOR_DISPATCH,
                reason = "Send to another courier.",
                replacementCourierId = "courier-resolve-problem-new",
            ),
        )
        val history = controller.problemResolutions(admin, mission.id)

        assertEquals(HttpStatus.OK, resolved.statusCode)
        resolved.bodyAs<DeliveryMissionSnapshot>().also {
            assertEquals(DeliveryMissionRecordStatus.CREATED, it.status)
            assertEquals("courier-resolve-problem-new", it.courierId)
        }
        assertEquals(HttpStatus.OK, history.statusCode)
        history.bodyAs<List<DeliveryProblemResolutionSnapshot>>().single().also {
            assertEquals(DeliveryProblemResolutionAction.REQUEUE_FOR_DISPATCH, it.action)
            assertEquals("Send to another courier.", it.reason)
        }
    }

    @Test
    fun adminCanPauseAndUnpauseCourierAssignments() {
        val admin = auth("admin-pause-courier", RoleCode.ADMIN)
        val mission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = "CTRL-PAUSE-COURIER",
                orderId = "order-pause-courier",
                deliveryMode = DeliveryMissionRecordMode.STANDARD,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            )
        )

        val paused = controller.pauseCourier(
            admin,
            DeliveryMissionController.PauseCourierRequest(
                courierId = "courier-paused-controller",
                reason = "Repeated pickup no-show.",
                pausedUntil = Instant.now().plusSeconds(3600),
            ),
        )
        val blockedAssignment = controller.assign(
            admin,
            mission.id,
            DeliveryMissionController.AssignCourierRequest("courier-paused-controller"),
        )
        val availability = controller.getCourierAvailability(admin, "courier-paused-controller")
        val unpaused = controller.unpauseCourier(
            admin,
            DeliveryMissionController.UnpauseCourierRequest("courier-paused-controller"),
        )
        val assignmentAfterUnpause = controller.assign(
            admin,
            mission.id,
            DeliveryMissionController.AssignCourierRequest("courier-paused-controller"),
        )

        assertEquals(HttpStatus.OK, paused.statusCode)
        assertTrue(paused.bodyAs<CourierAvailabilitySnapshot>().paused)
        assertEquals(HttpStatus.BAD_REQUEST, blockedAssignment.statusCode)
        assertEquals("courier_paused", blockedAssignment.bodyAs<DeliveryMissionController.ErrorResponse>().code)
        assertEquals(HttpStatus.OK, availability.statusCode)
        assertTrue(availability.bodyAs<CourierAvailabilitySnapshot>().paused)
        assertEquals(HttpStatus.OK, unpaused.statusCode)
        assertFalse(unpaused.bodyAs<CourierAvailabilitySnapshot>().paused)
        assertEquals(HttpStatus.OK, assignmentAfterUnpause.statusCode)
    }

    @Test
    fun adminCannotReassignAfterPickup() {
        val admin = auth("admin-reassign-late", RoleCode.ADMIN)
        val courier = auth("courier-reassign-late", RoleCode.COURIER)
        val mission = createAssignedAndOfferedMission(
            deliveryCode = "CTRL-REASSIGN-LATE",
            orderId = "order-reassign-late",
            courierId = "courier-reassign-late",
            admin = admin,
        )
        controller.accept(courier, "courier-reassign-late", mission.id)
        controller.pickup(
            courier,
            "courier-reassign-late",
            mission.id,
            DeliveryMissionController.ProofRequest(proofMetadata = "pickup-proof"),
        )

        val reassigned = controller.reassign(
            admin,
            mission.id,
            DeliveryMissionController.AssignCourierRequest("courier-reassign-other"),
        )

        assertEquals(HttpStatus.BAD_REQUEST, reassigned.statusCode)
        assertEquals("courier-reassign-late", requireNotNull(service.get(mission.id)).courierId)
    }

    @Test
    fun courierCanCompleteDirectDeliveryWithValidatedPinInsteadOfProofMetadata() {
        val admin = auth("admin-deliver", RoleCode.ADMIN)
        val courier = auth("courier-deliver", RoleCode.COURIER)
        val mission = createAssignedAndOfferedMission(
            deliveryCode = "CTRL-DELIVER-PIN",
            orderId = "order-deliver-pin",
            courierId = "courier-deliver",
            admin = admin,
        )
        controller.accept(courier, "courier-deliver", mission.id)
        controller.pickup(
            courier,
            "courier-deliver",
            mission.id,
            DeliveryMissionController.ProofRequest(proofMetadata = "pickup-photo-ref"),
        )
        controller.createDeliveryPin(
            admin,
            mission.id,
            DeliveryMissionController.CreateDeliveryPinRequest(
                rawPin = "654321",
                expiresAt = Instant.now().plusSeconds(3600),
            )
        )

        val delivered = controller.deliver(
            courier,
            "courier-deliver",
            mission.id,
            DeliveryMissionController.ProofRequest(deliveryPin = "654321"),
        )

        assertEquals(HttpStatus.OK, delivered.statusCode)
        val deliveredMission = delivered.bodyAs<DeliveryMissionSnapshot>()
        assertEquals(DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, deliveredMission.status)
        assertEquals("courier-deliver", deliveredMission.dropoffProofActorId)
        assertNull(deliveredMission.dropoffProofMetadata)
    }

    @Test
    fun duplicateDeliveryWithSameIdempotencyKeyDoesNotConsumePinAgain() {
        val admin = auth("admin-deliver-idempotent", RoleCode.ADMIN)
        val courier = auth("courier-deliver-idempotent", RoleCode.COURIER)
        val mission = createAssignedAndOfferedMission(
            deliveryCode = "CTRL-DELIVER-IDEMPOTENT",
            orderId = "order-deliver-idempotent",
            courierId = "courier-deliver-idempotent",
            admin = admin,
        )
        controller.accept(courier, "courier-deliver-idempotent", mission.id)
        controller.pickup(
            courier,
            "courier-deliver-idempotent",
            mission.id,
            DeliveryMissionController.ProofRequest(proofMetadata = "pickup-photo-ref"),
        )
        controller.createDeliveryPin(
            admin,
            mission.id,
            DeliveryMissionController.CreateDeliveryPinRequest(
                rawPin = "777888",
                expiresAt = Instant.now().plusSeconds(3600),
            )
        )

        val delivered = controller.deliver(
            courier,
            "courier-deliver-idempotent",
            mission.id,
            DeliveryMissionController.ProofRequest(deliveryPin = "777888", idempotencyKey = "delivery-idempotent-1"),
        )
        val replayed = controller.deliver(
            courier,
            "courier-deliver-idempotent",
            mission.id,
            DeliveryMissionController.ProofRequest(deliveryPin = "777888", idempotencyKey = "delivery-idempotent-1"),
        )
        val usedPins = pinRepository.findAll().filter { it.deliveryMissionId == mission.id && it.usedAt != null }

        assertEquals(HttpStatus.OK, delivered.statusCode)
        assertEquals(HttpStatus.OK, replayed.statusCode)
        assertEquals(DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, replayed.bodyAs<DeliveryMissionSnapshot>().status)
        assertEquals(1, usedPins.size)
    }

    @Test
    fun wrongCourierCannotConsumeValidDeliveryPin() {
        val admin = auth("admin-pin-scope", RoleCode.ADMIN)
        val assignedCourier = auth("courier-pin-owner", RoleCode.COURIER)
        val wrongCourier = auth("courier-pin-other", RoleCode.COURIER)
        val mission = createAssignedAndOfferedMission(
            deliveryCode = "CTRL-PIN-SCOPE",
            orderId = "order-pin-scope",
            courierId = "courier-pin-owner",
            admin = admin,
        )
        controller.accept(assignedCourier, "courier-pin-owner", mission.id)
        controller.pickup(
            assignedCourier,
            "courier-pin-owner",
            mission.id,
            DeliveryMissionController.ProofRequest(proofMetadata = "pickup-proof"),
        )
        controller.createDeliveryPin(
            admin,
            mission.id,
            DeliveryMissionController.CreateDeliveryPinRequest(
                rawPin = "222333",
                expiresAt = Instant.now().plusSeconds(3600),
            )
        )

        val blocked = controller.deliver(
            wrongCourier,
            "courier-pin-other",
            mission.id,
            DeliveryMissionController.ProofRequest(deliveryPin = "222333"),
        )
        val pinAfterBlockedAttempt = pinRepository.findFirstByDeliveryMissionIdAndUsedAtIsNullOrderByCreatedAtDesc(mission.id)
        val attemptsAfterBlockedAttempt = pinAfterBlockedAttempt?.attemptCount
        val usedAtAfterBlockedAttempt = pinAfterBlockedAttempt?.usedAt
        val delivered = controller.deliver(
            assignedCourier,
            "courier-pin-owner",
            mission.id,
            DeliveryMissionController.ProofRequest(deliveryPin = "222333"),
        )

        assertEquals(HttpStatus.BAD_REQUEST, blocked.statusCode)
        assertEquals(0, attemptsAfterBlockedAttempt)
        assertNull(usedAtAfterBlockedAttempt)
        assertEquals(HttpStatus.OK, delivered.statusCode)
        assertEquals(DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, delivered.bodyAs<DeliveryMissionSnapshot>().status)
    }

    @Test
    fun customerTrackingIsRedactedAndRequiresMatchingOrderReference() {
        val admin = auth("admin-track", RoleCode.ADMIN)
        val customer = auth("customer-track", RoleCode.CUSTOMER)
        val otherCustomer = auth("other-customer-track", RoleCode.CUSTOMER)
        val courier = auth("courier-track", RoleCode.COURIER)
        saveCustomerOrder("order-track", "customer-track")
        val mission = createAssignedAndOfferedMission(
            deliveryCode = "CTRL-TRACK",
            orderId = "order-track",
            courierId = "courier-track",
            admin = admin,
        )
        controller.accept(courier, "courier-track", mission.id)
        controller.pickup(
            courier,
            "courier-track",
            mission.id,
            DeliveryMissionController.ProofRequest(proofMetadata = "private-pickup-proof"),
        )

        val tracked = trackingController.track(customer, "CTRL-TRACK", "order-track")
        val otherCustomerRead = trackingController.track(otherCustomer, "CTRL-TRACK", "order-track")
        val wrongOrder = trackingController.track(customer, "CTRL-TRACK", "wrong-order")
        val wrongRole = trackingController.track(courier, "CTRL-TRACK", "order-track")

        assertEquals(HttpStatus.OK, tracked.statusCode)
        val snapshot = tracked.bodyAs<DeliveryTrackingSnapshot>()
        assertEquals(DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER, snapshot.status)
        assertEquals("On the way to customer", snapshot.currentStep)
        assertEquals("Customer delivery", snapshot.nextStep)
        assertFalse(snapshot.toString().contains("private-pickup-proof"))
        assertEquals(HttpStatus.NOT_FOUND, otherCustomerRead.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, wrongOrder.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, wrongRole.statusCode)
    }

    private fun saveCustomerOrder(orderId: String, customerId: String) {
        val createdAt = Instant.parse("2026-09-09T09:00:00Z")
        orders.save(
            CustomerOrderRecord(
                id = orderId,
                checkoutId = "$orderId-checkout",
                customerId = customerId,
                serviceLevel = OrderServiceLevel.Regular,
                route = OrderRoute.FastDelivery,
                fulfillmentPriority = FulfillmentPriority.Standard,
                requiresConsolidation = false,
                customerFacingStatus = "Courier pickup pending.",
                itemSubtotalCfa = 2_000,
                deliveryFeeCfa = 400,
                totalCfa = 2_400,
                paymentProvider = "yas_togo",
                paymentReference = "$orderId-payment",
                providerReference = null,
                paymentStatus = PaymentValidationStatus.Validated,
                orderStatus = CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        )
    }

    private fun createAssignedAndOfferedMission(
        deliveryCode: String,
        orderId: String,
        courierId: String,
        admin: Authentication,
    ): DeliveryMissionSnapshot {
        val mission = service.create(
            CreateDeliveryMissionCommand(
                deliveryCode = deliveryCode,
                orderId = orderId,
                deliveryMode = DeliveryMissionRecordMode.EXPRESS,
                destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            )
        )
        controller.assign(admin, mission.id, DeliveryMissionController.AssignCourierRequest(courierId))
        controller.offer(admin, mission.id)
        return requireNotNull(service.get(mission.id))
    }

    private fun auth(userId: String, role: RoleCode): Authentication =
        UsernamePasswordAuthenticationToken(userId, null, listOf(role.toGrantedAuthority()))

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> org.springframework.http.ResponseEntity<Any>.bodyAs(): T =
        body as T
}
