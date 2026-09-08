package dev.orestegabo.sequo_api.domain.bargaining

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BargainingServiceTest {

    private val service = BargainingService()
    private val now = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun customerCanProposeLowerProductPrice() {
        val session = openSession()

        val result = service.submitCustomerOffer(
            CustomerOfferCommand(
                session = session,
                offerId = "offer-1",
                customerId = "customer-1",
                amountCfa = 8_500,
                offeredAt = now.plusSeconds(60),
            )
        )

        assertTrue(result is BargainingResult.Accepted)
        assertEquals(BargainingStatus.CustomerOffered, result.session.status)
        assertEquals(1, result.session.customerAttemptsUsed)
        assertEquals(8_500, result.session.offers.single().amountCfa)
        assertEquals(BargainingOfferStatus.Pending, result.session.offers.single().status)
    }

    @Test
    fun offerMustBeLowerThanCatalogPrice() {
        val session = openSession()

        val result = service.submitCustomerOffer(
            CustomerOfferCommand(
                session = session,
                offerId = "offer-1",
                customerId = "customer-1",
                amountCfa = 10_000,
                offeredAt = now.plusSeconds(60),
            )
        )

        assertTrue(result is BargainingResult.Rejected)
        assertEquals("offer_not_lower_than_catalog_price", result.rejection.code)
    }

    @Test
    fun merchantCanDisableBargainingPerProduct() {
        val result = service.openSession(
            sessionId = "session-1",
            scope = scope(bargainingEnabled = false),
            openedAt = now,
        )

        assertTrue(result is BargainingResult.Rejected)
        assertEquals("bargaining_disabled", result.rejection.code)
    }

    @Test
    fun customerIsLimitedToThreeAttemptsPerCustomerMerchantProductContext() {
        val session = openSession()
        val firstRejected = rejectCustomerOffer(submitOffer(session, "offer-1", 9_000), now.plusSeconds(2))
        val secondRejected = rejectCustomerOffer(submitOffer(firstRejected, "offer-2", 8_500), now.plusSeconds(4))
        val thirdOffered = submitOffer(secondRejected, "offer-3", 8_000)

        val thirdRejected = service.rejectCustomerOffer(
            MerchantOfferDecisionCommand(
                session = thirdOffered,
                merchantId = "merchant-1",
                decidedAt = now.plusSeconds(6),
                acceptedLockId = "unused-lock-id",
            )
        )

        assertTrue(thirdRejected is BargainingResult.Accepted)
        assertEquals(BargainingStatus.LockedAttemptsExhausted, thirdRejected.session.status)

        val fourth = service.submitCustomerOffer(
            CustomerOfferCommand(
                session = thirdRejected.session.copy(status = BargainingStatus.Rejected),
                offerId = "offer-4",
                customerId = "customer-1",
                amountCfa = 7_500,
                offeredAt = now.plusSeconds(8),
            )
        )

        assertTrue(fourth is BargainingResult.Rejected)
        assertEquals("bargaining_attempts_exhausted", fourth.rejection.code)
    }

    @Test
    fun merchantAcceptanceLocksPriceForTwentyFourHours() {
        val session = submitOffer(openSession(), "offer-1", 8_500)

        val result = service.acceptCustomerOffer(
            MerchantOfferDecisionCommand(
                session = session,
                merchantId = "merchant-1",
                decidedAt = now.plusSeconds(120),
                acceptedLockId = "lock-1",
            )
        )

        assertTrue(result is BargainingResult.Accepted)
        assertEquals(BargainingStatus.Accepted, result.session.status)
        val lock = assertNotNull(result.session.acceptedPriceLock)
        assertEquals(8_500, lock.acceptedAmountCfa)
        assertEquals(now.plusSeconds(120).plusSeconds(24 * 60 * 60), lock.expiresAt)
        assertTrue(lock.isValidFor(scope(), now.plusSeconds(3600)))
        assertFalse(lock.isValidFor(scope(), now.plusSeconds(25 * 60 * 60)))
    }

    @Test
    fun customerAcceptanceOfMerchantCounterAlsoLocksPrice() {
        val offered = submitOffer(openSession(), "offer-1", 8_000)
        val countered = service.counterCustomerOffer(
            MerchantCounterCommand(
                session = offered,
                counterOfferId = "counter-1",
                merchantId = "merchant-1",
                amountCfa = 8_750,
                offeredAt = now.plusSeconds(120),
            )
        ) as BargainingResult.Accepted

        val accepted = service.acceptMerchantCounter(
            CustomerCounterAcceptanceCommand(
                session = countered.session,
                customerId = "customer-1",
                acceptedAt = now.plusSeconds(180),
                acceptedLockId = "lock-1",
            )
        )

        assertTrue(accepted is BargainingResult.Accepted)
        assertEquals(BargainingStatus.Accepted, accepted.session.status)
        assertEquals(8_750, accepted.session.acceptedPriceLock?.acceptedAmountCfa)
    }

    @Test
    fun historicalMinimumPriceIsKeptAfterLockExpires() {
        val accepted = service.acceptCustomerOffer(
            MerchantOfferDecisionCommand(
                session = submitOffer(openSession(), "offer-1", 8_500),
                merchantId = "merchant-1",
                decidedAt = now.plusSeconds(120),
                acceptedLockId = "lock-1",
            )
        ) as BargainingResult.Accepted

        val expired = service.expireIfNeeded(accepted.session, now.plusSeconds(25 * 60 * 60))

        assertEquals(BargainingStatus.Expired, expired.status)
        assertEquals(1, expired.historicalMinimumPrices.size)
        assertEquals(8_500, expired.historicalMinimumPrices.single().acceptedAmountCfa)
    }

    @Test
    fun historicalMinimumRecordsOnlyLowerAcceptedAmountsForSameScope() {
        val firstAccepted = service.acceptCustomerOffer(
            MerchantOfferDecisionCommand(
                session = submitOffer(openSession(), "offer-1", 8_500),
                merchantId = "merchant-1",
                decidedAt = now.plusSeconds(120),
                acceptedLockId = "lock-1",
            )
        ) as BargainingResult.Accepted
        val secondSession = openSession().copy(historicalMinimumPrices = firstAccepted.session.historicalMinimumPrices)
        val secondAccepted = service.acceptCustomerOffer(
            MerchantOfferDecisionCommand(
                session = submitOffer(secondSession, "offer-2", 8_900),
                merchantId = "merchant-1",
                decidedAt = now.plusSeconds(240),
                acceptedLockId = "lock-2",
            )
        ) as BargainingResult.Accepted
        val thirdSession = openSession().copy(historicalMinimumPrices = secondAccepted.session.historicalMinimumPrices)
        val thirdAccepted = service.acceptCustomerOffer(
            MerchantOfferDecisionCommand(
                session = submitOffer(thirdSession, "offer-3", 8_000),
                merchantId = "merchant-1",
                decidedAt = now.plusSeconds(360),
                acceptedLockId = "lock-3",
            )
        ) as BargainingResult.Accepted

        assertEquals(1, secondAccepted.session.historicalMinimumPrices.size)
        assertEquals(2, thirdAccepted.session.historicalMinimumPrices.size)
        assertEquals(8_000, thirdAccepted.session.historicalMinimumPrices.last().acceptedAmountCfa)
    }

    private fun openSession(): BargainingSession =
        (service.openSession("session-1", scope(), now) as BargainingResult.Accepted).session

    private fun submitOffer(
        session: BargainingSession,
        offerId: String,
        amountCfa: Int = 8_500,
    ): BargainingSession =
        (
            service.submitCustomerOffer(
                CustomerOfferCommand(
                    session = session,
                    offerId = offerId,
                    customerId = "customer-1",
                    amountCfa = amountCfa,
                    offeredAt = now.plusSeconds(1),
                )
            ) as BargainingResult.Accepted
            ).session

    private fun rejectCustomerOffer(session: BargainingSession, decidedAt: Instant): BargainingSession =
        (
            service.rejectCustomerOffer(
                MerchantOfferDecisionCommand(
                    session = session,
                    merchantId = "merchant-1",
                    decidedAt = decidedAt,
                    acceptedLockId = "unused-lock-id",
                )
            ) as BargainingResult.Accepted
            ).session

    private fun scope(bargainingEnabled: Boolean = true): BargainingSessionScope =
        BargainingSessionScope(
            customerId = "customer-1",
            merchantId = "merchant-1",
            productId = "product-1",
            variantId = "variant-1",
            catalogPriceCfa = 10_000,
            bargainingEnabled = bargainingEnabled,
        )
}
