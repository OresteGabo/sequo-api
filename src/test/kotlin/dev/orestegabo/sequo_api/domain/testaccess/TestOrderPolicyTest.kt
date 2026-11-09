package dev.orestegabo.sequo_api.domain.testaccess
import dev.orestegabo.sequo_api.domain.routing.GeoPoint
import kotlin.test.*
class TestOrderPolicyTest {
    @Test fun fictionalOriginIsAvailableOnlyForAnEnabledTestBasket() {
        val enabled=TestOrderPolicy(true);val off=TestOrderPolicy(false)
        assertNull(enabled.quoteOrigin(null,false));assertNull(off.quoteOrigin(null,true))
        assertEquals(GeoPoint(6.1725,1.2314),enabled.quoteOrigin(null,true))
        val configured=GeoPoint(1.0,2.0)
        assertEquals(configured,enabled.quoteOrigin(configured,true));assertEquals(configured,off.quoteOrigin(configured,false))
    }
    @Test fun onlyExactSeededProductAndShopPairsQualify() {
        val policy=TestOrderPolicy(true)
        assertTrue(policy.testProduct("demo-v1-mode-01","demo-v1-shop-mode"))
        assertFalse(policy.testProduct("demo-v1-mode-01","ordinary-shop"))
        assertFalse(policy.testProduct("ordinary-product","demo-v1-shop-mode"))
        assertFalse(policy.testProduct("demo-v1-invented-01","demo-v1-shop-invented"))
    }
}
