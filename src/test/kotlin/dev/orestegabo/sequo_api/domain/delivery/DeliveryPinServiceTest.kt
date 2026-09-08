package dev.orestegabo.sequo_api.domain.delivery

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class DeliveryPinServiceTest @Autowired constructor(
    private val service: DeliveryPinService,
    private val repository: DeliveryPinRepository,
    private val missionService: DeliveryMissionService,
) {
    private val createdAt = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun storesHashedPinAndAllowsOneSuccessfulVerification() {
        val missionId = mission("MISSION-PIN-1")
        val created = service.create(CreateDeliveryPinCommand(missionId, "123456", createdAt.plusSeconds(3600), createdAt))
        val verified = service.verify(missionId, "123456", createdAt.plusSeconds(60))
        val reused = service.verify(missionId, "123456", createdAt.plusSeconds(61))

        assertNotEquals("123456", repository.findById(created.id).orElseThrow().pinHash)
        assertTrue(verified is DeliveryPinResult.Accepted)
        assertEquals(createdAt.plusSeconds(60), verified.pin.usedAt)
        assertTrue(reused is DeliveryPinResult.Rejected)
        assertEquals("delivery_pin_not_found", reused.code)
    }

    @Test
    fun invalidAttemptsExpireAndCannotExceedLimit() {
        val missionId = mission("MISSION-PIN-2")
        service.create(CreateDeliveryPinCommand(missionId, "123456", createdAt.plusSeconds(3600), createdAt))
        repeat(5) { service.verify(missionId, "000000", createdAt.plusSeconds(it.toLong())) }
        val exhausted = service.verify(missionId, "123456", createdAt.plusSeconds(10))

        assertTrue(exhausted is DeliveryPinResult.Rejected)
        assertEquals("delivery_pin_attempts_exhausted", exhausted.code)
        assertEquals(5, exhausted.pin?.attemptCount)
    }

    @Test
    fun expiredPinAndInvalidCommandsAreRejected() {
        val missionId = mission("MISSION-PIN-3")
        service.create(CreateDeliveryPinCommand(missionId, "123456", createdAt.plusSeconds(10), createdAt))
        val expired = service.verify(missionId, "123456", createdAt.plusSeconds(10))

        assertTrue(expired is DeliveryPinResult.Rejected)
        assertEquals("delivery_pin_expired", expired.code)
        assertTrue(service.verify("missing", "123456") is DeliveryPinResult.Rejected)
    }

    private fun mission(deliveryCode: String): String = missionService.create(
        CreateDeliveryMissionCommand(
            deliveryCode = deliveryCode,
            orderId = deliveryCode,
            deliveryMode = DeliveryMissionRecordMode.STANDARD,
            destinationType = DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
        )
    ).id
}
