package dev.orestegabo.sequo_api.domain.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveryFulfillmentWorkflowTest {
    private val merchantWorkflow = MerchantFulfillmentWorkflow()
    private val deliveryWorkflow = DeliveryMissionWorkflow()

    @Test
    fun sellerAcceptsPreparesPacksAndHandsPackageToCourier() {
        val accepted = merchantWorkflow.transition(
            MerchantFulfillmentTransitionRequest(
                currentStatus = MerchantFulfillmentStatus.AwaitingSellerAcceptance,
                event = MerchantFulfillmentEvent.SellerAccepts,
            )
        )
        val preparing = merchantWorkflow.transition(
            MerchantFulfillmentTransitionRequest(
                currentStatus = accepted.nextStatus,
                event = MerchantFulfillmentEvent.SellerStartsPreparing,
            )
        )
        val packed = merchantWorkflow.transition(
            MerchantFulfillmentTransitionRequest(
                currentStatus = preparing.nextStatus,
                event = MerchantFulfillmentEvent.SellerMarksPacked,
                packageCount = 1,
            )
        )
        val handedToCourier = merchantWorkflow.transition(
            MerchantFulfillmentTransitionRequest(
                currentStatus = packed.nextStatus,
                event = MerchantFulfillmentEvent.CourierCollectsPackage,
            )
        )

        assertTrue(handedToCourier.accepted)
        assertEquals(MerchantFulfillmentStatus.HandedToCourier, handedToCourier.nextStatus)
    }

    @Test
    fun rejectsCourierCollectionBeforeSellerPackedTheOrder() {
        val result = merchantWorkflow.transition(
            MerchantFulfillmentTransitionRequest(
                currentStatus = MerchantFulfillmentStatus.AcceptedBySeller,
                event = MerchantFulfillmentEvent.CourierCollectsPackage,
            )
        )

        assertFalse(result.accepted)
        assertEquals(MerchantFulfillmentStatus.AcceptedBySeller, result.nextStatus)
    }

    @Test
    fun directDeliveryRequiresPickupAndDeliveryProof() {
        val offered = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = DeliveryMissionStatus.Created,
                event = DeliveryMissionEvent.OfferToCourier,
                destinationType = DeliveryDestinationType.CustomerAddress,
            )
        )
        val accepted = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = offered.nextStatus,
                event = DeliveryMissionEvent.CourierAccepts,
                destinationType = DeliveryDestinationType.CustomerAddress,
            )
        )
        val pickedUp = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = accepted.nextStatus,
                event = DeliveryMissionEvent.CourierPicksUpFromSeller,
                destinationType = DeliveryDestinationType.CustomerAddress,
                proofProvided = true,
            )
        )
        val deliveredWithoutProof = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = pickedUp.nextStatus,
                event = DeliveryMissionEvent.CourierDeliversToCustomer,
                destinationType = DeliveryDestinationType.CustomerAddress,
                proofProvided = false,
            )
        )
        val delivered = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = pickedUp.nextStatus,
                event = DeliveryMissionEvent.CourierDeliversToCustomer,
                destinationType = DeliveryDestinationType.CustomerAddress,
                proofProvided = true,
            )
        )
        val deliveredWithPin = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = pickedUp.nextStatus,
                event = DeliveryMissionEvent.CourierDeliversToCustomer,
                destinationType = DeliveryDestinationType.CustomerAddress,
                deliveryPinValidated = true,
            )
        )

        assertFalse(deliveredWithoutProof.accepted)
        assertTrue(delivered.accepted)
        assertTrue(deliveredWithPin.accepted)
        assertEquals(DeliveryMissionStatus.DeliveredToCustomer, delivered.nextStatus)
    }

    @Test
    fun relayDeliveryRequiresRelayDepositThenPickupCodeAndIdentityValidation() {
        val deposited = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = DeliveryMissionStatus.PickedUpFromSeller,
                event = DeliveryMissionEvent.CourierDepositsAtRelay,
                destinationType = DeliveryDestinationType.RelayPoint,
                proofProvided = true,
            )
        )
        val releaseWithoutValidation = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = deposited.nextStatus,
                event = DeliveryMissionEvent.RelayReleasesToCustomer,
                destinationType = DeliveryDestinationType.RelayPoint,
                relayPickupCodeValidated = true,
                identityValidated = false,
            )
        )
        val released = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = deposited.nextStatus,
                event = DeliveryMissionEvent.RelayReleasesToCustomer,
                destinationType = DeliveryDestinationType.RelayPoint,
                relayPickupCodeValidated = true,
                identityValidated = true,
            )
        )

        assertEquals(DeliveryMissionStatus.DepositedAtRelay, deposited.nextStatus)
        assertFalse(releaseWithoutValidation.accepted)
        assertTrue(released.accepted)
        assertEquals(DeliveryMissionStatus.ReleasedByRelay, released.nextStatus)
    }

    @Test
    fun blocksDirectCustomerDeliveryForRelayMission() {
        val result = deliveryWorkflow.transition(
            DeliveryMissionTransitionRequest(
                currentStatus = DeliveryMissionStatus.PickedUpFromSeller,
                event = DeliveryMissionEvent.CourierDeliversToCustomer,
                destinationType = DeliveryDestinationType.RelayPoint,
                proofProvided = true,
            )
        )

        assertFalse(result.accepted)
        assertEquals(DeliveryMissionStatus.PickedUpFromSeller, result.nextStatus)
    }
}
