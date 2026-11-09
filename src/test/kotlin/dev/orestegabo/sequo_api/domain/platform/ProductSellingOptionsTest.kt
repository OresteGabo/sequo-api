package dev.orestegabo.sequo_api.domain.platform

import kotlin.test.*

class ProductSellingOptionsTest {
    @Test fun fractionalAmountsUseExactDecimalCfaPricing() {
        assertEquals(500, priceForProductMeasure(1000, "0.5"))
        assertEquals(1250, priceForProductMeasure(1000, "1.25"))
        assertEquals(167, priceForProductMeasure(333, "0.5"))
        assertEquals(1000, priceForProductMeasure(1000, null))
    }
    @Test fun rejectsInvalidAmountsAndUnpayableOrOverflowingPrices() {
        for (amount in listOf(null, "0", "-1", "NaN", "0.0001", "1001", "1e2")) {
            assertFailsWith<IllegalArgumentException> { parseProductMeasureAmount(amount) }
        }
        assertFailsWith<IllegalArgumentException> { priceForProductMeasure(Int.MAX_VALUE, "2") }
        assertFailsWith<IllegalArgumentException> { priceForProductMeasure(1, "0.001") }
    }
}
