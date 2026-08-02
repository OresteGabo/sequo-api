package dev.orestegabo.sequo_api.domain.relay

import java.time.Duration
import java.time.Instant

enum class RelayParcelCategory {
    Food,
    Perishable,
    GeneralGoods,
}

enum class RelayParcelCustodyAction {
    Active,
    StorageFeeEligible,
    ReturnToSellerReviewDue,
    AlreadyResolved,
}

data class RelayParcelPolicyConfig(
    val storageFeeAfter: Duration = Duration.ofDays(14),
    val returnToSellerReviewAfter: Duration = Duration.ofDays(28),
) {
    init {
        require(!storageFeeAfter.isNegative && !storageFeeAfter.isZero) {
            "storageFeeAfter must be positive."
        }
        require(!returnToSellerReviewAfter.isNegative && !returnToSellerReviewAfter.isZero) {
            "returnToSellerReviewAfter must be positive."
        }
        require(returnToSellerReviewAfter >= storageFeeAfter) {
            "returnToSellerReviewAfter must not be before storageFeeAfter."
        }
    }
}

data class RelayParcelCustodyInput(
    val category: RelayParcelCategory,
    val depositedAt: Instant,
    val evaluatedAt: Instant,
    val pickedUpAt: Instant? = null,
    val collectedAt: Instant? = null,
) {
    init {
        require(!evaluatedAt.isBefore(depositedAt)) {
            "evaluatedAt cannot be before depositedAt."
        }
    }
}

data class RelayParcelCustodyDecision(
    val relayPickupAllowed: Boolean,
    val action: RelayParcelCustodyAction,
    val storageFeeEligible: Boolean,
    val returnToSellerReviewDue: Boolean,
    val reason: String,
)

class RelayParcelPolicy(
    private val config: RelayParcelPolicyConfig = RelayParcelPolicyConfig(),
) {
    fun relayPickupAllowed(category: RelayParcelCategory): Boolean =
        category != RelayParcelCategory.Food && category != RelayParcelCategory.Perishable

    fun evaluateCustody(input: RelayParcelCustodyInput): RelayParcelCustodyDecision {
        val pickupAllowed = relayPickupAllowed(input.category)
        if (input.pickedUpAt != null || input.collectedAt != null) {
            return RelayParcelCustodyDecision(
                relayPickupAllowed = pickupAllowed,
                action = RelayParcelCustodyAction.AlreadyResolved,
                storageFeeEligible = false,
                returnToSellerReviewDue = false,
                reason = "Parcel custody is already resolved.",
            )
        }

        val age = Duration.between(input.depositedAt, input.evaluatedAt)
        return when {
            age >= config.returnToSellerReviewAfter ->
                RelayParcelCustodyDecision(
                    relayPickupAllowed = pickupAllowed,
                    action = RelayParcelCustodyAction.ReturnToSellerReviewDue,
                    storageFeeEligible = true,
                    returnToSellerReviewDue = true,
                    reason = "Parcel reached the return-to-seller review threshold.",
                )
            age >= config.storageFeeAfter ->
                RelayParcelCustodyDecision(
                    relayPickupAllowed = pickupAllowed,
                    action = RelayParcelCustodyAction.StorageFeeEligible,
                    storageFeeEligible = true,
                    returnToSellerReviewDue = false,
                    reason = "Parcel reached the delayed storage-fee threshold.",
                )
            else ->
                RelayParcelCustodyDecision(
                    relayPickupAllowed = pickupAllowed,
                    action = RelayParcelCustodyAction.Active,
                    storageFeeEligible = false,
                    returnToSellerReviewDue = false,
                    reason = "Parcel is still inside the normal relay custody window.",
                )
        }
    }
}
