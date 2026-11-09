package dev.orestegabo.sequo_api.domain.delivery

import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Duration
import kotlin.test.*

class MerchantResponseDeadlinePolicyTest {
    @Test fun authorizedDefaultsKeepShortAlertSeparateFromTheWholeResponseWindow() {
        val policy = MerchantResponseDeadlinePolicy(MockEnvironment())
        assertTrue(policy.enabled)
        assertEquals(Duration.ofSeconds(45), policy.alertWindow)
        assertEquals(Duration.ofMinutes(10), policy.responseWindow)
    }
    @Test fun configuredWindowsAreValidatedAndMayDisableNewSnapshots() {
        val policy = MerchantResponseDeadlinePolicy(MockEnvironment()
            .withProperty("sequo.merchant-fulfillment.response-deadline.enabled", "false")
            .withProperty("sequo.merchant-fulfillment.response-deadline.response-window", "PT5M")
            .withProperty("sequo.merchant-fulfillment.response-deadline.alert-window", "PT30S"))
        assertFalse(policy.enabled)
        assertEquals(Duration.ofMinutes(5), policy.responseWindow)
        assertEquals(Duration.ofSeconds(30), policy.alertWindow)
    }
    @Test fun invalidOrLongerThanResponseAlertWindowsFailAtConfiguration() {
        // A single nanosecond above the shared native hard cap must fail; exactly 45s remains valid above.
        listOf("PT0S", "PT-1S", "PT11M", "PT45.000000001S").forEach { value ->
            assertFailsWith<IllegalArgumentException> { MerchantResponseDeadlinePolicy(MockEnvironment()
                .withProperty("sequo.merchant-fulfillment.response-deadline.alert-window", value)) }
        }
        assertFailsWith<IllegalArgumentException> { MerchantResponseDeadlinePolicy(MockEnvironment()
            .withProperty("sequo.merchant-fulfillment.response-deadline.response-window", "PT30S")
            .withProperty("sequo.merchant-fulfillment.response-deadline.alert-window", "PT45S")) }
        assertFailsWith<IllegalArgumentException> { MerchantResponseDeadlinePolicy(MockEnvironment()
            .withProperty("sequo.merchant-fulfillment.response-deadline.response-window", "PT0S")) }
    }
}
