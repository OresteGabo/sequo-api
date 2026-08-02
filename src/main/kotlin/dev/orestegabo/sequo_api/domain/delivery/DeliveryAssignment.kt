package dev.orestegabo.sequo_api.domain.delivery

enum class DeliveryWorkforceType {
    SequoSalaried,
    Freelancer,
}

enum class DeliveryVehicleType {
    Moto,
    Bicycle,
    Car,
    Van,
}

enum class DeliveryAssignmentChannel {
    Express,
    StandardLocal,
    ProgrammedConsolidation,
}

data class AvailableCourier(
    val courierId: String,
    val workforceType: DeliveryWorkforceType,
    val vehicleType: DeliveryVehicleType,
    val availableCapacity: Int,
) {
    init {
        require(courierId.isNotBlank()) { "courierId cannot be blank." }
        require(availableCapacity >= 0) { "availableCapacity must be non-negative." }
    }
}

data class DeliveryAssignmentRequest(
    val channel: DeliveryAssignmentChannel,
    val subscriberOrder: Boolean,
    val customerDeliveryFeeCfa: Int,
    val freelancerCourierFeeCfa: Int,
    val availableCouriers: List<AvailableCourier>,
) {
    init {
        require(customerDeliveryFeeCfa >= 0) { "customerDeliveryFeeCfa must be non-negative." }
        require(freelancerCourierFeeCfa >= 0) { "freelancerCourierFeeCfa must be non-negative." }
    }
}

data class DeliveryAssignment(
    val courierId: String,
    val workforceType: DeliveryWorkforceType,
    val vehicleType: DeliveryVehicleType,
    val freelancerPayableCfa: Int,
    val customerDeliveryFeeCfa: Int,
    val sequoShortfallCfa: Int,
    val reason: String,
)

sealed class DeliveryAssignmentResult {
    data class Assigned(val assignment: DeliveryAssignment) : DeliveryAssignmentResult()

    data class Unassigned(val reason: String) : DeliveryAssignmentResult()
}

class DeliveryAssignmentPolicy {
    fun assign(request: DeliveryAssignmentRequest): DeliveryAssignmentResult {
        val eligibleCouriers = request.availableCouriers.filter { it.availableCapacity > 0 }
        if (eligibleCouriers.isEmpty()) {
            return DeliveryAssignmentResult.Unassigned("No courier capacity is currently available.")
        }

        val selectedCourier = selectCourier(request, eligibleCouriers)
            ?: return DeliveryAssignmentResult.Unassigned("No eligible courier matches the delivery policy.")

        val freelancerPayableCfa = if (selectedCourier.workforceType == DeliveryWorkforceType.Freelancer) {
            request.freelancerCourierFeeCfa
        } else {
            0
        }
        val shortfallCfa = if (selectedCourier.workforceType == DeliveryWorkforceType.Freelancer) {
            (freelancerPayableCfa - request.customerDeliveryFeeCfa).coerceAtLeast(0)
        } else {
            0
        }

        return DeliveryAssignmentResult.Assigned(
            DeliveryAssignment(
                courierId = selectedCourier.courierId,
                workforceType = selectedCourier.workforceType,
                vehicleType = selectedCourier.vehicleType,
                freelancerPayableCfa = freelancerPayableCfa,
                customerDeliveryFeeCfa = request.customerDeliveryFeeCfa,
                sequoShortfallCfa = shortfallCfa,
                reason = assignmentReason(request, selectedCourier),
            )
        )
    }

    private fun selectCourier(
        request: DeliveryAssignmentRequest,
        couriers: List<AvailableCourier>,
    ): AvailableCourier? {
        if (request.subscriberOrder) {
            couriers.firstOrNull { it.workforceType == DeliveryWorkforceType.SequoSalaried }
                ?.let { return it }
        }

        if (request.channel == DeliveryAssignmentChannel.ProgrammedConsolidation) {
            couriers.firstOrNull { it.workforceType == DeliveryWorkforceType.SequoSalaried }
                ?.let { return it }
        }

        if (request.channel == DeliveryAssignmentChannel.Express) {
            couriers.firstOrNull {
                it.workforceType == DeliveryWorkforceType.Freelancer && it.vehicleType == DeliveryVehicleType.Moto
            }?.let { return it }
        }

        return couriers.firstOrNull { it.workforceType == DeliveryWorkforceType.Freelancer }
    }

    private fun assignmentReason(
        request: DeliveryAssignmentRequest,
        courier: AvailableCourier,
    ): String =
        when {
            request.subscriberOrder && courier.workforceType == DeliveryWorkforceType.SequoSalaried ->
                "Subscriber order assigned to salaried Sequo delivery capacity."
            request.channel == DeliveryAssignmentChannel.ProgrammedConsolidation &&
                courier.workforceType == DeliveryWorkforceType.SequoSalaried ->
                "Programmed consolidation assigned to Sequo delivery capacity."
            request.channel == DeliveryAssignmentChannel.Express &&
                courier.workforceType == DeliveryWorkforceType.Freelancer &&
                courier.vehicleType == DeliveryVehicleType.Moto ->
                "Express delivery assigned to a freelance moto courier."
            courier.workforceType == DeliveryWorkforceType.Freelancer ->
                "Assigned to available freelancer capacity."
            else ->
                "Assigned to available Sequo delivery capacity."
        }
}
