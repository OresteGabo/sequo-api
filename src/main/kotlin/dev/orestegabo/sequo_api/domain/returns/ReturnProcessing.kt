package dev.orestegabo.sequo_api.domain.returns

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

enum class ReturnItemCategory {
    ReturnableGoods,
    Food,
    Perishable,
}

enum class ReturnStatus {
    Requested,
    AwaitingRelayDropoff,
    DroppedAtRelay,
    ReceivedBySequo,
    RefundPending,
    Rejected,
}

enum class RefundResponsibility {
    Merchant,
    Sequo,
    Courier,
}

data class ReturnEligibilityInput(
    val orderId: String,
    val orderOwnerId: String,
    val requesterCustomerId: String,
    val itemCategory: ReturnItemCategory,
    val deliveredAt: Instant,
    val requestedAt: Instant,
    val productReturnable: Boolean = true,
    val merchantAllowsReturn: Boolean = true,
    val adminOverride: Boolean = false,
    val alreadyRefunded: Boolean = false,
    val fraudLocked: Boolean = false,
)

data class ReturnRequestCommand(
    val returnId: String,
    val orderId: String,
    val customerId: String,
    val reason: String,
    val requestedRefundCfa: Int,
    val eligibility: ReturnEligibilityInput,
    val rawReturnPin: String,
)

data class RelayDropoffCommand(
    val returnRequest: ReturnRequest,
    val relayPointId: String,
    val rawReturnPin: String,
    val droppedAt: Instant,
)

data class PhysicalReceiptCommand(
    val returnRequest: ReturnRequest,
    val operatorId: String,
    val receivedAt: Instant,
    val conditionAssessment: String,
    val responsibility: RefundResponsibility,
    val idempotencyKey: String,
)

data class RefundTriggerCommand(
    val returnRequest: ReturnRequest,
    val amountCfa: Int,
    val idempotencyKey: String,
)

data class ReturnRequest(
    val returnId: String,
    val orderId: String,
    val customerId: String,
    val status: ReturnStatus,
    val requestedAt: Instant,
    val requestedRefundCfa: Int,
    val returnPinHash: String,
    val relayPointId: String? = null,
    val droppedAt: Instant? = null,
    val receivedBySequoAt: Instant? = null,
    val receivingOperatorId: String? = null,
    val conditionAssessment: String? = null,
    val responsibility: RefundResponsibility? = null,
    val receiptIdempotencyKey: String? = null,
    val refundIdempotencyKey: String? = null,
)

data class ReturnRejection(
    val code: String,
    val message: String,
)

sealed class ReturnProcessingResult {
    data class Accepted(val returnRequest: ReturnRequest) : ReturnProcessingResult()
    data class Rejected(val rejection: ReturnRejection) : ReturnProcessingResult()
}

class ReturnProcessingService(
    private val returnWindow: Duration = Duration.ofHours(72),
    private val pinHasher: ReturnPinHasher = Sha256ReturnPinHasher(),
) {
    fun requestReturn(command: ReturnRequestCommand): ReturnProcessingResult {
        validateReturnCommand(command)?.let { return ReturnProcessingResult.Rejected(it) }
        eligibilityRejection(command.eligibility)?.let { return ReturnProcessingResult.Rejected(it) }

        return ReturnProcessingResult.Accepted(
            ReturnRequest(
                returnId = command.returnId,
                orderId = command.orderId,
                customerId = command.customerId,
                status = ReturnStatus.AwaitingRelayDropoff,
                requestedAt = command.eligibility.requestedAt,
                requestedRefundCfa = command.requestedRefundCfa,
                returnPinHash = pinHasher.hash(command.rawReturnPin),
            )
        )
    }

    fun recordRelayDropoff(command: RelayDropoffCommand): ReturnProcessingResult {
        val request = command.returnRequest
        if (request.status != ReturnStatus.AwaitingRelayDropoff) {
            return rejected("invalid_return_state", "Return must be awaiting relay drop-off.")
        }
        if (command.relayPointId.isBlank()) return rejected("missing_relay_point", "Relay point id is required.")
        if (!pinHasher.matches(command.rawReturnPin, request.returnPinHash)) {
            return rejected("invalid_return_pin", "Return PIN is invalid.")
        }

        return ReturnProcessingResult.Accepted(
            request.copy(
                status = ReturnStatus.DroppedAtRelay,
                relayPointId = command.relayPointId,
                droppedAt = command.droppedAt,
            )
        )
    }

    fun recordPhysicalReceipt(command: PhysicalReceiptCommand): ReturnProcessingResult {
        val request = command.returnRequest
        if (command.idempotencyKey.isBlank()) return rejected("missing_idempotency_key", "Idempotency key is required.")
        if (request.receiptIdempotencyKey == command.idempotencyKey && request.status == ReturnStatus.ReceivedBySequo) {
            return ReturnProcessingResult.Accepted(request)
        }
        if (request.status != ReturnStatus.DroppedAtRelay) {
            return rejected("invalid_return_state", "Sequo physical receipt requires a relay drop-off first.")
        }
        if (command.operatorId.isBlank()) return rejected("missing_operator", "Receiving operator id is required.")
        if (command.conditionAssessment.isBlank()) {
            return rejected("missing_condition_assessment", "Condition assessment is required.")
        }

        return ReturnProcessingResult.Accepted(
            request.copy(
                status = ReturnStatus.ReceivedBySequo,
                receivedBySequoAt = command.receivedAt,
                receivingOperatorId = command.operatorId,
                conditionAssessment = command.conditionAssessment,
                responsibility = command.responsibility,
                receiptIdempotencyKey = command.idempotencyKey,
            )
        )
    }

    fun triggerRefund(command: RefundTriggerCommand): ReturnProcessingResult {
        val request = command.returnRequest
        if (command.idempotencyKey.isBlank()) return rejected("missing_idempotency_key", "Idempotency key is required.")
        if (request.refundIdempotencyKey == command.idempotencyKey && request.status == ReturnStatus.RefundPending) {
            return ReturnProcessingResult.Accepted(request)
        }
        if (request.status != ReturnStatus.ReceivedBySequo || request.receivedBySequoAt == null) {
            return rejected("physical_receipt_required", "Refund can start only after Sequo physically receives the returned item.")
        }
        if (command.amountCfa <= 0) return rejected("invalid_refund_amount", "Refund amount must be positive.")
        if (command.amountCfa > request.requestedRefundCfa) {
            return rejected("refund_amount_too_high", "Refund amount cannot exceed the requested refund amount.")
        }

        return ReturnProcessingResult.Accepted(
            request.copy(
                status = ReturnStatus.RefundPending,
                refundIdempotencyKey = command.idempotencyKey,
            )
        )
    }

    private fun eligibilityRejection(input: ReturnEligibilityInput): ReturnRejection? {
        if (input.orderId.isBlank()) return ReturnRejection("missing_order_id", "Order id is required.")
        if (input.orderOwnerId != input.requesterCustomerId) {
            return ReturnRejection("customer_not_order_owner", "Only the order owner can request a return.")
        }
        if (input.requestedAt.isBefore(input.deliveredAt)) {
            return ReturnRejection("invalid_return_time", "Return request cannot be before delivery.")
        }
        if (Duration.between(input.deliveredAt, input.requestedAt) > returnWindow && !input.adminOverride) {
            return ReturnRejection("return_window_expired", "Return request is outside the 72-hour return window.")
        }
        if (input.itemCategory != ReturnItemCategory.ReturnableGoods && !input.adminOverride) {
            return ReturnRejection("item_not_returnable", "Food and perishable items are not returnable by default.")
        }
        if ((!input.productReturnable || !input.merchantAllowsReturn) && !input.adminOverride) {
            return ReturnRejection("return_policy_blocks_item", "Product or merchant policy blocks this return.")
        }
        if (input.alreadyRefunded) return ReturnRejection("already_refunded", "Item was already refunded.")
        if (input.fraudLocked && !input.adminOverride) {
            return ReturnRejection("fraud_review_required", "Return is blocked pending fraud review.")
        }

        return null
    }

    private fun validateReturnCommand(command: ReturnRequestCommand): ReturnRejection? {
        if (command.returnId.isBlank()) return ReturnRejection("missing_return_id", "Return id is required.")
        if (command.orderId != command.eligibility.orderId) {
            return ReturnRejection("order_mismatch", "Return command order does not match eligibility input.")
        }
        if (command.customerId != command.eligibility.requesterCustomerId) {
            return ReturnRejection("customer_mismatch", "Return command customer does not match eligibility input.")
        }
        if (command.reason.isBlank()) return ReturnRejection("missing_reason", "Return reason is required.")
        if (command.requestedRefundCfa <= 0) {
            return ReturnRejection("invalid_refund_amount", "Requested refund amount must be positive.")
        }
        if (!command.rawReturnPin.matches(Regex("\\d{6}"))) {
            return ReturnRejection("invalid_return_pin", "Return PIN must contain exactly 6 digits.")
        }

        return null
    }

    private fun rejected(code: String, message: String): ReturnProcessingResult.Rejected =
        ReturnProcessingResult.Rejected(ReturnRejection(code, message))
}

interface ReturnPinHasher {
    fun hash(rawPin: String): String
    fun matches(rawPin: String, hash: String): Boolean = hash(rawPin) == hash
}

class Sha256ReturnPinHasher : ReturnPinHasher {
    override fun hash(rawPin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawPin.encodeToByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
