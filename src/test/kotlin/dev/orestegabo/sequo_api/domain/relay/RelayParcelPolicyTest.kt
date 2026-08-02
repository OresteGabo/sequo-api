package dev.orestegabo.sequo_api.domain.relay

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayParcelPolicyTest {
    private val policy = RelayParcelPolicy()
    private val depositedAt = Instant.parse("2026-08-01T10:00:00Z")

    @Test
    fun rejectsRelayPickupForFoodAndPerishableProducts() {
        assertFalse(policy.relayPickupAllowed(RelayParcelCategory.Food))
        assertFalse(policy.relayPickupAllowed(RelayParcelCategory.Perishable))
        assertTrue(policy.relayPickupAllowed(RelayParcelCategory.GeneralGoods))
    }

    @Test
    fun keepsParcelActiveBeforeStorageFeeThreshold() {
        val decision = policy.evaluateCustody(
            RelayParcelCustodyInput(
                category = RelayParcelCategory.GeneralGoods,
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(13)),
            )
        )

        assertEquals(RelayParcelCustodyAction.Active, decision.action)
        assertFalse(decision.storageFeeEligible)
        assertFalse(decision.returnToSellerReviewDue)
    }

    @Test
    fun marksParcelStorageFeeEligibleAfterTwoWeeks() {
        val decision = policy.evaluateCustody(
            RelayParcelCustodyInput(
                category = RelayParcelCategory.GeneralGoods,
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(14)),
            )
        )

        assertEquals(RelayParcelCustodyAction.StorageFeeEligible, decision.action)
        assertTrue(decision.storageFeeEligible)
        assertFalse(decision.returnToSellerReviewDue)
    }

    @Test
    fun marksParcelForReturnToSellerReviewAfterFourWeeks() {
        val decision = policy.evaluateCustody(
            RelayParcelCustodyInput(
                category = RelayParcelCategory.GeneralGoods,
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(28)),
            )
        )

        assertEquals(RelayParcelCustodyAction.ReturnToSellerReviewDue, decision.action)
        assertTrue(decision.storageFeeEligible)
        assertTrue(decision.returnToSellerReviewDue)
    }

    @Test
    fun resolvedParcelDoesNotCreateDelayedActions() {
        val decision = policy.evaluateCustody(
            RelayParcelCustodyInput(
                category = RelayParcelCategory.GeneralGoods,
                depositedAt = depositedAt,
                evaluatedAt = depositedAt.plus(Duration.ofDays(30)),
                pickedUpAt = depositedAt.plus(Duration.ofDays(2)),
            )
        )

        assertEquals(RelayParcelCustodyAction.AlreadyResolved, decision.action)
        assertFalse(decision.storageFeeEligible)
        assertFalse(decision.returnToSellerReviewDue)
    }

    @Test
    fun rejectsInvalidThresholdConfiguration() {
        assertFailsWith<IllegalArgumentException> {
            RelayParcelPolicyConfig(
                storageFeeAfter = Duration.ofDays(14),
                returnToSellerReviewAfter = Duration.ofDays(7),
            )
        }
    }
}
