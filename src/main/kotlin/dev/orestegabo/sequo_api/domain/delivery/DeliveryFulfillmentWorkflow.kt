package dev.orestegabo.sequo_api.domain.delivery

enum class MerchantFulfillmentStatus {
    AwaitingSellerAcceptance,
    AcceptedBySeller,
    Preparing,
    PackedReadyForPickup,
    HandedToCourier,
    RejectedBySeller,
    Cancelled,
}

enum class MerchantFulfillmentEvent {
    SellerAccepts,
    SellerStartsPreparing,
    SellerMarksPacked,
    CourierCollectsPackage,
    SellerRejects,
    CustomerCancels,
}

enum class DeliveryDestinationType {
    CustomerAddress,
    RelayPoint,
    SequoConsolidation,
}

enum class DeliveryMissionStatus {
    Created,
    OfferedToCourier,
    AcceptedByCourier,
    PickedUpFromSeller,
    DepositedAtRelay,
    DeliveredToCustomer,
    ReleasedByRelay,
    ProblemReported,
    Cancelled,
}

enum class DeliveryMissionEvent {
    OfferToCourier,
    CourierAccepts,
    CourierPicksUpFromSeller,
    CourierDeliversToCustomer,
    CourierDepositsAtRelay,
    RelayReleasesToCustomer,
    ReportProblem,
    Cancel,
}

data class FulfillmentTransition<T>(
    val accepted: Boolean,
    val nextStatus: T,
    val reason: String,
)

data class MerchantFulfillmentTransitionRequest(
    val currentStatus: MerchantFulfillmentStatus,
    val event: MerchantFulfillmentEvent,
    val packageCount: Int = 0,
) {
    init {
        require(packageCount >= 0) { "packageCount must be non-negative." }
    }
}

data class DeliveryMissionTransitionRequest(
    val currentStatus: DeliveryMissionStatus,
    val event: DeliveryMissionEvent,
    val destinationType: DeliveryDestinationType,
    val proofProvided: Boolean = false,
    val deliveryPinValidated: Boolean = false,
    val relayPickupCodeValidated: Boolean = false,
    val identityValidated: Boolean = false,
    val problemReason: String? = null,
)

class MerchantFulfillmentWorkflow {
    fun transition(
        request: MerchantFulfillmentTransitionRequest,
    ): FulfillmentTransition<MerchantFulfillmentStatus> {
        if (request.currentStatus in terminalStatuses) {
            return rejected(request.currentStatus, "Merchant fulfillment is already terminal.")
        }

        return when (request.currentStatus) {
            MerchantFulfillmentStatus.AwaitingSellerAcceptance ->
                when (request.event) {
                    MerchantFulfillmentEvent.SellerAccepts ->
                        accepted(MerchantFulfillmentStatus.AcceptedBySeller, "Seller accepted the paid order.")
                    MerchantFulfillmentEvent.SellerRejects ->
                        accepted(MerchantFulfillmentStatus.RejectedBySeller, "Seller rejected the order.")
                    MerchantFulfillmentEvent.CustomerCancels ->
                        accepted(MerchantFulfillmentStatus.Cancelled, "Customer cancelled before seller acceptance.")
                    else -> rejected(request.currentStatus, "Seller must accept before preparation or pickup.")
                }
            MerchantFulfillmentStatus.AcceptedBySeller ->
                when (request.event) {
                    MerchantFulfillmentEvent.SellerStartsPreparing ->
                        accepted(MerchantFulfillmentStatus.Preparing, "Seller started preparing the package.")
                    MerchantFulfillmentEvent.SellerMarksPacked ->
                        markPacked(request)
                    MerchantFulfillmentEvent.CustomerCancels ->
                        accepted(MerchantFulfillmentStatus.Cancelled, "Customer cancelled before package pickup.")
                    else -> rejected(request.currentStatus, "Accepted order must be prepared or packed next.")
                }
            MerchantFulfillmentStatus.Preparing ->
                when (request.event) {
                    MerchantFulfillmentEvent.SellerMarksPacked -> markPacked(request)
                    MerchantFulfillmentEvent.CustomerCancels ->
                        accepted(MerchantFulfillmentStatus.Cancelled, "Customer cancelled before package pickup.")
                    else -> rejected(request.currentStatus, "Preparing order must be marked packed before pickup.")
                }
            MerchantFulfillmentStatus.PackedReadyForPickup ->
                when (request.event) {
                    MerchantFulfillmentEvent.CourierCollectsPackage ->
                        accepted(MerchantFulfillmentStatus.HandedToCourier, "Courier collected the packed package.")
                    else -> rejected(request.currentStatus, "Packed package is waiting for courier collection.")
                }
            MerchantFulfillmentStatus.HandedToCourier,
            MerchantFulfillmentStatus.RejectedBySeller,
            MerchantFulfillmentStatus.Cancelled -> rejected(request.currentStatus, "Merchant fulfillment is already terminal.")
        }
    }

    private fun markPacked(
        request: MerchantFulfillmentTransitionRequest,
    ): FulfillmentTransition<MerchantFulfillmentStatus> =
        if (request.packageCount > 0) {
            accepted(MerchantFulfillmentStatus.PackedReadyForPickup, "Seller marked package ready for pickup.")
        } else {
            rejected(request.currentStatus, "At least one package is required before pickup.")
        }

    private fun accepted(
        nextStatus: MerchantFulfillmentStatus,
        reason: String,
    ): FulfillmentTransition<MerchantFulfillmentStatus> =
        FulfillmentTransition(true, nextStatus, reason)

    private fun rejected(
        currentStatus: MerchantFulfillmentStatus,
        reason: String,
    ): FulfillmentTransition<MerchantFulfillmentStatus> =
        FulfillmentTransition(false, currentStatus, reason)

    private companion object {
        val terminalStatuses = setOf(
            MerchantFulfillmentStatus.HandedToCourier,
            MerchantFulfillmentStatus.RejectedBySeller,
            MerchantFulfillmentStatus.Cancelled,
        )
    }
}

class DeliveryMissionWorkflow {
    fun transition(
        request: DeliveryMissionTransitionRequest,
    ): FulfillmentTransition<DeliveryMissionStatus> {
        if (request.currentStatus in terminalStatuses) {
            return rejected(request.currentStatus, "Delivery mission is already terminal.")
        }
        if (request.event == DeliveryMissionEvent.ReportProblem) {
            return if (request.problemReason.isNullOrBlank()) {
                rejected(request.currentStatus, "Problem reason is required.")
            } else {
                accepted(DeliveryMissionStatus.ProblemReported, "Delivery problem reported.")
            }
        }
        if (request.event == DeliveryMissionEvent.Cancel) {
            return accepted(DeliveryMissionStatus.Cancelled, "Delivery mission cancelled.")
        }

        return when (request.currentStatus) {
            DeliveryMissionStatus.Created ->
                when (request.event) {
                    DeliveryMissionEvent.OfferToCourier ->
                        accepted(DeliveryMissionStatus.OfferedToCourier, "Delivery mission offered to courier pool.")
                    else -> rejected(request.currentStatus, "Created mission must be offered before courier acceptance.")
                }
            DeliveryMissionStatus.OfferedToCourier ->
                when (request.event) {
                    DeliveryMissionEvent.CourierAccepts ->
                        accepted(DeliveryMissionStatus.AcceptedByCourier, "Courier accepted the delivery mission.")
                    else -> rejected(request.currentStatus, "Offered mission must be accepted before pickup.")
                }
            DeliveryMissionStatus.AcceptedByCourier ->
                when (request.event) {
                    DeliveryMissionEvent.CourierPicksUpFromSeller ->
                        requireProof(
                            request = request,
                            nextStatus = DeliveryMissionStatus.PickedUpFromSeller,
                            acceptedReason = "Courier picked up the package from seller.",
                            rejectedReason = "Pickup proof is required before leaving seller.",
                        )
                    else -> rejected(request.currentStatus, "Accepted mission must be picked up from seller next.")
                }
            DeliveryMissionStatus.PickedUpFromSeller ->
                when (request.event) {
                    DeliveryMissionEvent.CourierDeliversToCustomer -> deliverToCustomer(request)
                    DeliveryMissionEvent.CourierDepositsAtRelay -> depositAtRelay(request)
                    else -> rejected(request.currentStatus, "Picked-up package must be delivered, deposited, or reported.")
                }
            DeliveryMissionStatus.DepositedAtRelay ->
                when (request.event) {
                    DeliveryMissionEvent.RelayReleasesToCustomer -> releaseFromRelay(request)
                    else -> rejected(request.currentStatus, "Relay parcel must be released by relay partner.")
                }
            DeliveryMissionStatus.DeliveredToCustomer,
            DeliveryMissionStatus.ReleasedByRelay,
            DeliveryMissionStatus.ProblemReported,
            DeliveryMissionStatus.Cancelled -> rejected(request.currentStatus, "Delivery mission is already terminal.")
        }
    }

    private fun deliverToCustomer(
        request: DeliveryMissionTransitionRequest,
    ): FulfillmentTransition<DeliveryMissionStatus> {
        if (request.destinationType != DeliveryDestinationType.CustomerAddress) {
            return rejected(request.currentStatus, "Only customer-address missions can be delivered directly.")
        }
        return requireProof(
            request = request,
            nextStatus = DeliveryMissionStatus.DeliveredToCustomer,
            acceptedReason = "Courier delivered package to customer.",
            rejectedReason = "Delivery proof or PIN is required.",
        )
    }

    private fun depositAtRelay(
        request: DeliveryMissionTransitionRequest,
    ): FulfillmentTransition<DeliveryMissionStatus> {
        if (request.destinationType != DeliveryDestinationType.RelayPoint) {
            return rejected(request.currentStatus, "Only relay missions can be deposited at Point de Relai.")
        }
        return requireProof(
            request = request,
            nextStatus = DeliveryMissionStatus.DepositedAtRelay,
            acceptedReason = "Courier deposited package at Point de Relai.",
            rejectedReason = "Relay deposit proof is required.",
        )
    }

    private fun releaseFromRelay(
        request: DeliveryMissionTransitionRequest,
    ): FulfillmentTransition<DeliveryMissionStatus> =
        if (request.relayPickupCodeValidated && request.identityValidated) {
            accepted(DeliveryMissionStatus.ReleasedByRelay, "Relay released package to customer.")
        } else {
            rejected(request.currentStatus, "Relay release requires pickup code and identity validation.")
        }

    private fun requireProof(
        request: DeliveryMissionTransitionRequest,
        nextStatus: DeliveryMissionStatus,
        acceptedReason: String,
        rejectedReason: String,
    ): FulfillmentTransition<DeliveryMissionStatus> =
        if (request.proofProvided || request.deliveryPinValidated) {
            accepted(nextStatus, acceptedReason)
        } else {
            rejected(request.currentStatus, rejectedReason)
        }

    private fun accepted(
        nextStatus: DeliveryMissionStatus,
        reason: String,
    ): FulfillmentTransition<DeliveryMissionStatus> =
        FulfillmentTransition(true, nextStatus, reason)

    private fun rejected(
        currentStatus: DeliveryMissionStatus,
        reason: String,
    ): FulfillmentTransition<DeliveryMissionStatus> =
        FulfillmentTransition(false, currentStatus, reason)

    private companion object {
        val terminalStatuses = setOf(
            DeliveryMissionStatus.DeliveredToCustomer,
            DeliveryMissionStatus.ReleasedByRelay,
            DeliveryMissionStatus.ProblemReported,
            DeliveryMissionStatus.Cancelled,
        )
    }
}
