package dev.orestegabo.sequo_api.domain.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeliveryAssignmentPolicyTest {
    private val policy = DeliveryAssignmentPolicy()

    @Test
    fun subscriberOrdersPreferSalariedSequoCouriers() {
        val result = policy.assign(
            DeliveryAssignmentRequest(
                channel = DeliveryAssignmentChannel.Express,
                subscriberOrder = true,
                customerDeliveryFeeCfa = 300,
                freelancerCourierFeeCfa = 700,
                availableCouriers = listOf(
                    freelancer("freelancer-moto", DeliveryVehicleType.Moto),
                    sequoCourier("sequo-1"),
                ),
            )
        )

        assertTrue(result is DeliveryAssignmentResult.Assigned)
        assertEquals("sequo-1", result.assignment.courierId)
        assertEquals(DeliveryWorkforceType.SequoSalaried, result.assignment.workforceType)
        assertEquals(0, result.assignment.freelancerPayableCfa)
        assertEquals(0, result.assignment.sequoShortfallCfa)
    }

    @Test
    fun expressOrdersPreferFreelanceMotoCouriers() {
        val result = policy.assign(
            DeliveryAssignmentRequest(
                channel = DeliveryAssignmentChannel.Express,
                subscriberOrder = false,
                customerDeliveryFeeCfa = 700,
                freelancerCourierFeeCfa = 700,
                availableCouriers = listOf(
                    freelancer("freelancer-bike", DeliveryVehicleType.Bicycle),
                    freelancer("freelancer-moto", DeliveryVehicleType.Moto),
                ),
            )
        )

        assertTrue(result is DeliveryAssignmentResult.Assigned)
        assertEquals("freelancer-moto", result.assignment.courierId)
        assertEquals(DeliveryVehicleType.Moto, result.assignment.vehicleType)
    }

    @Test
    fun freelancerShortfallIsSequoExpense() {
        val result = policy.assign(
            DeliveryAssignmentRequest(
                channel = DeliveryAssignmentChannel.StandardLocal,
                subscriberOrder = false,
                customerDeliveryFeeCfa = 400,
                freelancerCourierFeeCfa = 650,
                availableCouriers = listOf(freelancer("freelancer-bike", DeliveryVehicleType.Bicycle)),
            )
        )

        assertTrue(result is DeliveryAssignmentResult.Assigned)
        assertEquals(650, result.assignment.freelancerPayableCfa)
        assertEquals(250, result.assignment.sequoShortfallCfa)
    }

    @Test
    fun programmedConsolidationPrefersSequoCapacityButFallsBackToFreelancer() {
        val withSequo = policy.assign(
            DeliveryAssignmentRequest(
                channel = DeliveryAssignmentChannel.ProgrammedConsolidation,
                subscriberOrder = false,
                customerDeliveryFeeCfa = 0,
                freelancerCourierFeeCfa = 700,
                availableCouriers = listOf(
                    freelancer("freelancer-bike", DeliveryVehicleType.Bicycle),
                    sequoCourier("sequo-1"),
                ),
            )
        )
        val withoutSequo = policy.assign(
            DeliveryAssignmentRequest(
                channel = DeliveryAssignmentChannel.ProgrammedConsolidation,
                subscriberOrder = false,
                customerDeliveryFeeCfa = 0,
                freelancerCourierFeeCfa = 700,
                availableCouriers = listOf(freelancer("freelancer-bike", DeliveryVehicleType.Bicycle)),
            )
        )

        assertTrue(withSequo is DeliveryAssignmentResult.Assigned)
        assertEquals("sequo-1", withSequo.assignment.courierId)
        assertTrue(withoutSequo is DeliveryAssignmentResult.Assigned)
        assertEquals("freelancer-bike", withoutSequo.assignment.courierId)
        assertEquals(700, withoutSequo.assignment.sequoShortfallCfa)
    }

    @Test
    fun returnsUnassignedWhenNoCapacityExists() {
        val result = policy.assign(
            DeliveryAssignmentRequest(
                channel = DeliveryAssignmentChannel.StandardLocal,
                subscriberOrder = false,
                customerDeliveryFeeCfa = 400,
                freelancerCourierFeeCfa = 650,
                availableCouriers = listOf(freelancer("busy", DeliveryVehicleType.Moto, availableCapacity = 0)),
            )
        )

        assertTrue(result is DeliveryAssignmentResult.Unassigned)
    }

    private fun sequoCourier(id: String): AvailableCourier =
        AvailableCourier(
            courierId = id,
            workforceType = DeliveryWorkforceType.SequoSalaried,
            vehicleType = DeliveryVehicleType.Van,
            availableCapacity = 1,
        )

    private fun freelancer(
        id: String,
        vehicleType: DeliveryVehicleType,
        availableCapacity: Int = 1,
    ): AvailableCourier =
        AvailableCourier(
            courierId = id,
            workforceType = DeliveryWorkforceType.Freelancer,
            vehicleType = vehicleType,
            availableCapacity = availableCapacity,
        )
}
