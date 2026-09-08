package dev.orestegabo.sequo_api.domain.delivery

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
    }
}
