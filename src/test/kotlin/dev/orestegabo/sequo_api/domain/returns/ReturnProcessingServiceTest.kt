package dev.orestegabo.sequo_api.domain.returns

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReturnProcessingServiceTest {

    private val service = ReturnProcessingService()
    private val deliveredAt = Instant.parse("2026-09-01T10:00:00Z")

    @Test
    fun returnRequestIsAllowedAtExactlySeventyTwoHours() {
        val result = service.requestReturn(
            requestCommand(requestedAt = Instant.parse("2026-09-04T10:00:00Z"))
        )

        assertTrue(result is ReturnProcessingResult.Accepted)
        assertEquals(ReturnStatus.AwaitingRelayDropoff, result.returnRequest.status)
        assertNotEquals("123456", result.returnRequest.returnPinHash)
        assertTrue(result.returnRequest.returnPinHash.startsWith("sha256:"))
    }

    @Test
    fun returnRequestIsRejectedAfterSeventyTwoHours() {
        val result = service.requestReturn(
            requestCommand(requestedAt = Instant.parse("2026-09-04T10:00:01Z"))
        )

        assertTrue(result is ReturnProcessingResult.Rejected)
        assertEquals("return_window_expired", result.rejection.code)
    }

    @Test
    fun relayDropoffRequiresValidReturnPin() {
        val acceptedReturn = acceptedReturn()

        val rejected = service.recordRelayDropoff(
            RelayDropoffCommand(
                returnRequest = acceptedReturn,
                relayPointId = "relay-lome-1",
                rawReturnPin = "000000",
                droppedAt = Instant.parse("2026-09-02T11:00:00Z"),
            )
        )
        val accepted = service.recordRelayDropoff(
            RelayDropoffCommand(
                returnRequest = acceptedReturn,
                relayPointId = "relay-lome-1",
                rawReturnPin = "123456",
                droppedAt = Instant.parse("2026-09-02T11:00:00Z"),
            )
        )

        assertTrue(rejected is ReturnProcessingResult.Rejected)
        assertEquals("invalid_return_pin", rejected.rejection.code)
        assertTrue(accepted is ReturnProcessingResult.Accepted)
        assertEquals(ReturnStatus.DroppedAtRelay, accepted.returnRequest.status)
    }

    @Test
    fun refundCannotStartBeforeSequoPhysicalReceipt() {
        val droppedAtRelay = droppedAtRelay()

        val result = service.triggerRefund(
            RefundTriggerCommand(
                returnRequest = droppedAtRelay,
                amountCfa = 2_500,
                idempotencyKey = "refund-return-1-attempt-1",
            )
        )

        assertTrue(result is ReturnProcessingResult.Rejected)
        assertEquals("physical_receipt_required", result.rejection.code)
    }

    @Test
    fun duplicatePhysicalReceiptWithSameIdempotencyKeyReturnsExistingReceipt() {
        val received = receivedBySequo()

        val duplicate = service.recordPhysicalReceipt(
            PhysicalReceiptCommand(
                returnRequest = received,
                operatorId = "operator-2",
                receivedAt = Instant.parse("2026-09-02T13:00:00Z"),
                conditionAssessment = "Different duplicate payload ignored.",
                responsibility = RefundResponsibility.Sequo,
                idempotencyKey = "receipt-return-1",
            )
        )

        assertTrue(duplicate is ReturnProcessingResult.Accepted)
        assertEquals(received, duplicate.returnRequest)
    }

    @Test
    fun refundStartsAfterPhysicalReceiptAndIsIdempotent() {
        val received = receivedBySequo()

        val first = service.triggerRefund(
            RefundTriggerCommand(
                returnRequest = received,
                amountCfa = 2_500,
                idempotencyKey = "refund-return-1-attempt-1",
            )
        )
        assertTrue(first is ReturnProcessingResult.Accepted)
        assertEquals(ReturnStatus.RefundPending, first.returnRequest.status)

        val duplicate = service.triggerRefund(
            RefundTriggerCommand(
                returnRequest = first.returnRequest,
                amountCfa = 2_500,
                idempotencyKey = "refund-return-1-attempt-1",
            )
        )

        assertTrue(duplicate is ReturnProcessingResult.Accepted)
        assertEquals(first.returnRequest, duplicate.returnRequest)
    }

    @Test
    fun nonReturnableFoodRequiresAdminOverride() {
        val rejected = service.requestReturn(
            requestCommand(category = ReturnItemCategory.Food)
        )
        val acceptedByOverride = service.requestReturn(
            requestCommand(
                category = ReturnItemCategory.Food,
                adminOverride = true,
            )
        )

        assertTrue(rejected is ReturnProcessingResult.Rejected)
        assertEquals("item_not_returnable", rejected.rejection.code)
        assertTrue(acceptedByOverride is ReturnProcessingResult.Accepted)
    }

    private fun receivedBySequo(): ReturnRequest {
        val dropped = droppedAtRelay()
        val result = service.recordPhysicalReceipt(
            PhysicalReceiptCommand(
                returnRequest = dropped,
                operatorId = "operator-1",
                receivedAt = Instant.parse("2026-09-02T12:00:00Z"),
                conditionAssessment = "Sealed product received in good condition.",
                responsibility = RefundResponsibility.Merchant,
                idempotencyKey = "receipt-return-1",
            )
        )

        return (result as ReturnProcessingResult.Accepted).returnRequest
    }

    private fun droppedAtRelay(): ReturnRequest {
        val result = service.recordRelayDropoff(
            RelayDropoffCommand(
                returnRequest = acceptedReturn(),
                relayPointId = "relay-lome-1",
                rawReturnPin = "123456",
                droppedAt = Instant.parse("2026-09-02T11:00:00Z"),
            )
        )

        return (result as ReturnProcessingResult.Accepted).returnRequest
    }

    private fun acceptedReturn(): ReturnRequest =
        (service.requestReturn(requestCommand()) as ReturnProcessingResult.Accepted).returnRequest

    private fun requestCommand(
        requestedAt: Instant = Instant.parse("2026-09-02T10:00:00Z"),
        category: ReturnItemCategory = ReturnItemCategory.ReturnableGoods,
        adminOverride: Boolean = false,
    ): ReturnRequestCommand =
        ReturnRequestCommand(
            returnId = "return-1",
            orderId = "order-1",
            customerId = "customer-1",
            reason = "Wrong size delivered.",
            requestedRefundCfa = 2_500,
            rawReturnPin = "123456",
            eligibility = ReturnEligibilityInput(
                orderId = "order-1",
                orderOwnerId = "customer-1",
                requesterCustomerId = "customer-1",
                itemCategory = category,
                deliveredAt = deliveredAt,
                requestedAt = requestedAt,
                adminOverride = adminOverride,
            ),
        )
}
