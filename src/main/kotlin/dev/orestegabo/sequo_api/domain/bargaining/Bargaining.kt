package dev.orestegabo.sequo_api.domain.bargaining

import java.time.Duration
import java.time.Instant

enum class BargainingStatus {
    Open,
    CustomerOffered,
    MerchantCountered,
    Accepted,
    Rejected,
    LockedAttemptsExhausted,
    Expired,
}

enum class BargainingOfferType {
    CustomerOffer,
    MerchantCounter,
}

enum class BargainingOfferStatus {
    Pending,
    Accepted,
    Rejected,
    Expired,
}

data class BargainingPolicy(
    val maxCustomerAttempts: Int = 3,
    val maxMerchantCounters: Int = 3,
    val acceptedPriceLockTtl: Duration = Duration.ofHours(24),
) {
    init {
        require(maxCustomerAttempts > 0) { "maxCustomerAttempts must be positive." }
        require(maxMerchantCounters > 0) { "maxMerchantCounters must be positive." }
        require(!acceptedPriceLockTtl.isNegative && !acceptedPriceLockTtl.isZero) {
            "acceptedPriceLockTtl must be positive."
        }
    }
}

data class BargainingSessionScope(
    val customerId: String,
    val merchantId: String,
    val productId: String,
    val variantId: String? = null,
    val catalogPriceCfa: Int,
    val bargainingEnabled: Boolean,
) {
    init {
        require(customerId.isNotBlank()) { "customerId is required." }
        require(merchantId.isNotBlank()) { "merchantId is required." }
        require(productId.isNotBlank()) { "productId is required." }
        require(catalogPriceCfa > 0) { "catalogPriceCfa must be positive." }
    }
}

data class BargainingOffer(
    val id: String,
    val offeredByUserId: String,
    val type: BargainingOfferType,
    val amountCfa: Int,
    val status: BargainingOfferStatus,
    val createdAt: Instant,
    val respondedAt: Instant? = null,
)

data class AcceptedPriceLock(
    val id: String,
    val sessionId: String,
    val customerId: String,
    val merchantId: String,
    val productId: String,
    val variantId: String?,
    val acceptedAmountCfa: Int,
    val acceptedAt: Instant,
    val expiresAt: Instant,
    val sourceOfferId: String,
) {
    fun isValidFor(scope: BargainingSessionScope, usedAt: Instant): Boolean =
        customerId == scope.customerId &&
            merchantId == scope.merchantId &&
            productId == scope.productId &&
            variantId == scope.variantId &&
            usedAt.isBefore(expiresAt)
}

data class HistoricalMinimumPrice(
    val merchantId: String,
    val productId: String,
    val variantId: String?,
    val customerId: String,
    val acceptedAmountCfa: Int,
    val acceptedAt: Instant,
)

data class BargainingSession(
    val id: String,
    val scope: BargainingSessionScope,
    val status: BargainingStatus,
    val customerAttemptsUsed: Int = 0,
    val merchantCounterAttemptsUsed: Int = 0,
    val offers: List<BargainingOffer> = emptyList(),
    val acceptedPriceLock: AcceptedPriceLock? = null,
    val historicalMinimumPrices: List<HistoricalMinimumPrice> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CustomerOfferCommand(
    val session: BargainingSession,
    val offerId: String,
    val customerId: String,
    val amountCfa: Int,
    val offeredAt: Instant,
)

data class MerchantOfferDecisionCommand(
    val session: BargainingSession,
    val merchantId: String,
    val decidedAt: Instant,
    val acceptedLockId: String,
)

data class MerchantCounterCommand(
    val session: BargainingSession,
    val counterOfferId: String,
    val merchantId: String,
    val amountCfa: Int,
    val offeredAt: Instant,
)

data class CustomerCounterAcceptanceCommand(
    val session: BargainingSession,
    val customerId: String,
    val acceptedAt: Instant,
    val acceptedLockId: String,
)

data class BargainingRejection(
    val code: String,
    val message: String,
)

sealed class BargainingResult {
    data class Accepted(val session: BargainingSession) : BargainingResult()
    data class Rejected(val rejection: BargainingRejection) : BargainingResult()
}

class BargainingService(
    private val policy: BargainingPolicy = BargainingPolicy(),
) {
    fun openSession(
        sessionId: String,
        scope: BargainingSessionScope,
        openedAt: Instant,
    ): BargainingResult {
        if (sessionId.isBlank()) return rejected("missing_session_id", "Session id is required.")
        if (!scope.bargainingEnabled) {
            return rejected("bargaining_disabled", "Merchant disabled bargaining for this product.")
        }

        return BargainingResult.Accepted(
            BargainingSession(
                id = sessionId,
                scope = scope,
                status = BargainingStatus.Open,
                createdAt = openedAt,
                updatedAt = openedAt,
            )
        )
    }

    fun submitCustomerOffer(command: CustomerOfferCommand): BargainingResult {
        val session = command.session
        if (!session.scope.bargainingEnabled) {
            return rejected("bargaining_disabled", "Merchant disabled bargaining for this product.")
        }
        if (command.customerId != session.scope.customerId) {
            return rejected("customer_mismatch", "Only the session customer can submit an offer.")
        }
        validateOffer(command.offerId, command.amountCfa, session.scope.catalogPriceCfa)?.let { return BargainingResult.Rejected(it) }
        if (session.customerAttemptsUsed >= policy.maxCustomerAttempts) {
            return rejected("bargaining_attempts_exhausted", "Customer already used all bargaining attempts.")
        }
        if (session.status !in setOf(BargainingStatus.Open, BargainingStatus.Rejected)) {
            return rejected("invalid_bargaining_state", "Customer offer requires an open or rejected session.")
        }

        return BargainingResult.Accepted(
            session.copy(
                status = BargainingStatus.CustomerOffered,
                customerAttemptsUsed = session.customerAttemptsUsed + 1,
                offers = session.offers + BargainingOffer(
                    id = command.offerId,
                    offeredByUserId = command.customerId,
                    type = BargainingOfferType.CustomerOffer,
                    amountCfa = command.amountCfa,
                    status = BargainingOfferStatus.Pending,
                    createdAt = command.offeredAt,
                ),
                updatedAt = command.offeredAt,
            )
        )
    }

    fun acceptCustomerOffer(command: MerchantOfferDecisionCommand): BargainingResult {
        val session = command.session
        val pendingOffer = session.latestPendingOffer(BargainingOfferType.CustomerOffer)
            ?: return rejected("missing_pending_offer", "No customer offer is pending merchant acceptance.")
        if (command.merchantId != session.scope.merchantId) {
            return rejected("merchant_mismatch", "Only the product merchant can accept this offer.")
        }
        if (command.acceptedLockId.isBlank()) return rejected("missing_lock_id", "Accepted price lock id is required.")

        return BargainingResult.Accepted(session.acceptOffer(pendingOffer, command.acceptedLockId, command.decidedAt))
    }

    fun rejectCustomerOffer(command: MerchantOfferDecisionCommand): BargainingResult {
        val session = command.session
        val pendingOffer = session.latestPendingOffer(BargainingOfferType.CustomerOffer)
            ?: return rejected("missing_pending_offer", "No customer offer is pending merchant rejection.")
        if (command.merchantId != session.scope.merchantId) {
            return rejected("merchant_mismatch", "Only the product merchant can reject this offer.")
        }

        val nextStatus = if (session.customerAttemptsUsed >= policy.maxCustomerAttempts) {
            BargainingStatus.LockedAttemptsExhausted
        } else {
            BargainingStatus.Rejected
        }

        return BargainingResult.Accepted(
            session.copy(
                status = nextStatus,
                offers = session.offers.markOffer(pendingOffer.id, BargainingOfferStatus.Rejected, command.decidedAt),
                updatedAt = command.decidedAt,
            )
        )
    }

    fun counterCustomerOffer(command: MerchantCounterCommand): BargainingResult {
        val session = command.session
        val pendingOffer = session.latestPendingOffer(BargainingOfferType.CustomerOffer)
            ?: return rejected("missing_pending_offer", "No customer offer is pending merchant counter-offer.")
        if (command.merchantId != session.scope.merchantId) {
            return rejected("merchant_mismatch", "Only the product merchant can counter this offer.")
        }
        validateOffer(command.counterOfferId, command.amountCfa, session.scope.catalogPriceCfa)?.let {
            return BargainingResult.Rejected(it)
        }
        if (session.merchantCounterAttemptsUsed >= policy.maxMerchantCounters) {
            return rejected("merchant_counter_limit_reached", "Merchant already used all counter-offers.")
        }

        return BargainingResult.Accepted(
            session.copy(
                status = BargainingStatus.MerchantCountered,
                merchantCounterAttemptsUsed = session.merchantCounterAttemptsUsed + 1,
                offers = session.offers
                    .markOffer(pendingOffer.id, BargainingOfferStatus.Rejected, command.offeredAt)
                    .plus(
                        BargainingOffer(
                            id = command.counterOfferId,
                            offeredByUserId = command.merchantId,
                            type = BargainingOfferType.MerchantCounter,
                            amountCfa = command.amountCfa,
                            status = BargainingOfferStatus.Pending,
                            createdAt = command.offeredAt,
                        )
                    ),
                updatedAt = command.offeredAt,
            )
        )
    }

    fun acceptMerchantCounter(command: CustomerCounterAcceptanceCommand): BargainingResult {
        val session = command.session
        val pendingCounter = session.latestPendingOffer(BargainingOfferType.MerchantCounter)
            ?: return rejected("missing_pending_counter", "No merchant counter-offer is pending customer acceptance.")
        if (command.customerId != session.scope.customerId) {
            return rejected("customer_mismatch", "Only the session customer can accept the counter-offer.")
        }
        if (command.acceptedLockId.isBlank()) return rejected("missing_lock_id", "Accepted price lock id is required.")

        return BargainingResult.Accepted(session.acceptOffer(pendingCounter, command.acceptedLockId, command.acceptedAt))
    }

    fun expireIfNeeded(session: BargainingSession, evaluatedAt: Instant): BargainingSession {
        val lock = session.acceptedPriceLock
        if (session.status == BargainingStatus.Accepted && lock != null && !evaluatedAt.isBefore(lock.expiresAt)) {
            return session.copy(status = BargainingStatus.Expired, updatedAt = evaluatedAt)
        }

        return session
    }

    private fun BargainingSession.acceptOffer(
        offer: BargainingOffer,
        acceptedLockId: String,
        acceptedAt: Instant,
    ): BargainingSession {
        val lock = AcceptedPriceLock(
            id = acceptedLockId,
            sessionId = id,
            customerId = scope.customerId,
            merchantId = scope.merchantId,
            productId = scope.productId,
            variantId = scope.variantId,
            acceptedAmountCfa = offer.amountCfa,
            acceptedAt = acceptedAt,
            expiresAt = acceptedAt.plus(policy.acceptedPriceLockTtl),
            sourceOfferId = offer.id,
        )
        val historicalMinimum = HistoricalMinimumPrice(
            merchantId = scope.merchantId,
            productId = scope.productId,
            variantId = scope.variantId,
            customerId = scope.customerId,
            acceptedAmountCfa = offer.amountCfa,
            acceptedAt = acceptedAt,
        )

        return copy(
            status = BargainingStatus.Accepted,
            offers = offers.markOffer(offer.id, BargainingOfferStatus.Accepted, acceptedAt),
            acceptedPriceLock = lock,
            historicalMinimumPrices = historicalMinimumPrices.recordMinimum(historicalMinimum),
            updatedAt = acceptedAt,
        )
    }

    private fun BargainingSession.latestPendingOffer(type: BargainingOfferType): BargainingOffer? =
        offers.lastOrNull { it.type == type && it.status == BargainingOfferStatus.Pending }

    private fun List<BargainingOffer>.markOffer(
        offerId: String,
        status: BargainingOfferStatus,
        respondedAt: Instant,
    ): List<BargainingOffer> =
        map { offer ->
            if (offer.id == offerId) offer.copy(status = status, respondedAt = respondedAt) else offer
        }

    private fun List<HistoricalMinimumPrice>.recordMinimum(candidate: HistoricalMinimumPrice): List<HistoricalMinimumPrice> {
        val currentMinimum = filter {
            it.merchantId == candidate.merchantId &&
                it.productId == candidate.productId &&
                it.variantId == candidate.variantId &&
                it.customerId == candidate.customerId
        }.minByOrNull { it.acceptedAmountCfa }

        return if (currentMinimum == null || candidate.acceptedAmountCfa < currentMinimum.acceptedAmountCfa) {
            this + candidate
        } else {
            this
        }
    }

    private fun validateOffer(offerId: String, amountCfa: Int, catalogPriceCfa: Int): BargainingRejection? {
        if (offerId.isBlank()) return BargainingRejection("missing_offer_id", "Offer id is required.")
        if (amountCfa <= 0) return BargainingRejection("invalid_offer_amount", "Offer amount must be positive.")
        if (amountCfa >= catalogPriceCfa) {
            return BargainingRejection("offer_not_lower_than_catalog_price", "Bargaining offer must be lower than catalog price.")
        }

        return null
    }

    private fun rejected(code: String, message: String): BargainingResult.Rejected =
        BargainingResult.Rejected(BargainingRejection(code, message))
}
