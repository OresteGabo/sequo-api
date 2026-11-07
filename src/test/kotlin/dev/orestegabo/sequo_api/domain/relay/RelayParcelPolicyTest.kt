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
        assertFalse(policy.relayPickupAllowed(RelayParcelCategory.Sensitive))
        assertFalse(policy.relayPickupAllowed(RelayParcelCategory.Food))
        assertFalse(policy.relayPickupAllowed(RelayParcelCategory.Perishable))
        assertTrue(policy.relayPickupAllowed(RelayParcelCategory.GeneralGoods))
    }

    @Test
    fun switchesFeeEligibilityStrictlyAfterDayEight() {
        val threshold = depositedAt.plus(Duration.ofDays(8))
        assertEquals(RelayParcelCustodyAction.Active, decisionAt(threshold.minusNanos(1)).action)
        assertFalse(decisionAt(threshold).storageFeeEligible)
        assertEquals(RelayParcelCustodyAction.StorageFeeEligible, decisionAt(threshold.plusNanos(1)).action)
        assertTrue(decisionAt(threshold.plusNanos(1)).storageFeeEligible)
    }

    @Test
    fun startsReviewAtDayThirty() {
        val threshold = depositedAt.plus(Duration.ofDays(30))
        assertFalse(decisionAt(threshold.minusNanos(1)).returnToSellerReviewDue)
        assertEquals(RelayParcelCustodyAction.ReturnToSellerReviewDue, decisionAt(threshold).action)
        assertTrue(decisionAt(threshold.plusNanos(1)).returnToSellerReviewDue)
    }

    private fun decisionAt(evaluatedAt: Instant) = policy.evaluateCustody(
        RelayParcelCustodyInput(RelayParcelCategory.GeneralGoods, depositedAt, evaluatedAt)
    )

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
