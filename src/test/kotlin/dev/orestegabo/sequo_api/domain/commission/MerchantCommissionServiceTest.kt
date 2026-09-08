package dev.orestegabo.sequo_api.domain.commission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MerchantCommissionServiceTest {

    private val service = MerchantCommissionService()

    @Test
    fun defaultCommissionRateIsFifteenPercent() {
        val snapshot = service.calculate(
            MerchantCommissionInput(
                merchantId = "merchant-1",
                baseAmountCfa = 10_000,
                platformMarginCfa = 800,
            )
        )

        assertEquals(1500, snapshot.commissionRateBps)
        assertEquals(1_500, snapshot.commissionCfa)
        assertEquals(8_500, snapshot.merchantNetCfa)
        assertEquals(2_300, snapshot.sequoItemRevenueCfa)
    }

    @Test
    fun merchantOverrideCanUseLowerBoundFivePercent() {
        val snapshot = service.calculate(
            MerchantCommissionInput(
                merchantId = "merchant-1",
                baseAmountCfa = 10_000,
                platformMarginCfa = 800,
                merchantOverrideRateBps = 500,
            )
        )

        assertEquals(500, snapshot.commissionRateBps)
        assertEquals(500, snapshot.commissionCfa)
        assertEquals(9_500, snapshot.merchantNetCfa)
    }

    @Test
    fun merchantOverrideCanUseUpperBoundFifteenPercent() {
        val snapshot = service.calculate(
            MerchantCommissionInput(
                merchantId = "merchant-1",
                baseAmountCfa = 10_000,
                platformMarginCfa = 0,
                merchantOverrideRateBps = 1500,
            )
        )

        assertEquals(1500, snapshot.commissionRateBps)
        assertEquals(1_500, snapshot.commissionCfa)
    }

    @Test
    fun invalidCommissionOverrideIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            service.calculate(
                MerchantCommissionInput(
                    merchantId = "merchant-1",
                    baseAmountCfa = 10_000,
                    platformMarginCfa = 0,
                    merchantOverrideRateBps = 499,
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.calculate(
                MerchantCommissionInput(
                    merchantId = "merchant-1",
                    baseAmountCfa = 10_000,
                    platformMarginCfa = 0,
                    merchantOverrideRateBps = 1501,
                )
            )
        }
    }

    @Test
    fun platformMarginIsSeparateFromMerchantCommission() {
        val snapshot = service.calculate(
            MerchantCommissionInput(
                merchantId = "merchant-1",
                baseAmountCfa = 5_000,
                platformMarginCfa = 750,
                merchantOverrideRateBps = 1000,
            )
        )

        assertEquals(500, snapshot.commissionCfa)
        assertEquals(750, snapshot.platformMarginCfa)
        assertEquals(1_250, snapshot.sequoItemRevenueCfa)
        assertEquals(4_500, snapshot.merchantNetCfa)
    }

    @Test
    fun commissionSnapshotKeepsRateUsedForFutureOrderAccounting() {
        val serviceWithOldPolicy = MerchantCommissionService()
        val paidOrderSnapshot = serviceWithOldPolicy.calculate(
            MerchantCommissionInput(
                merchantId = "merchant-1",
                baseAmountCfa = 10_000,
                platformMarginCfa = 0,
                merchantOverrideRateBps = 1200,
            )
        )
        val serviceWithNewPolicy = MerchantCommissionService(
            MerchantCommissionPolicy(defaultRateBps = 1000)
        )
        val futureOrderSnapshot = serviceWithNewPolicy.calculate(
            MerchantCommissionInput(
                merchantId = "merchant-1",
                baseAmountCfa = 10_000,
                platformMarginCfa = 0,
            )
        )

        assertEquals(1200, paidOrderSnapshot.commissionRateBps)
        assertEquals(1_200, paidOrderSnapshot.commissionCfa)
        assertEquals(1000, futureOrderSnapshot.commissionRateBps)
        assertEquals(1_000, futureOrderSnapshot.commissionCfa)
    }
}
