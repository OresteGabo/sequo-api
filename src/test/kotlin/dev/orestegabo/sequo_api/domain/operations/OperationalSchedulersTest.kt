package dev.orestegabo.sequo_api.domain.operations

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest
class OperationalSchedulersTest @Autowired constructor(
    private val context: ApplicationContext,
) {
    @Test
    fun `operational schedulers are disabled by default`() {
        assertFalse(context.containsBean("notificationOutboxScheduler"))
        assertFalse(context.containsBean("relayDelayedParcelScheduler"))
        assertFalse(context.containsBean("deliveryMissionExpiryScheduler"))
        assertFalse(context.containsBean("settlementEligibilityScheduler"))
        kotlin.runCatching { context.getBean(NotificationOutboxScheduler::class.java) }
            .onSuccess { error("Notification scheduler should be disabled by default.") }
            .onFailure { assertTrue(it is NoSuchBeanDefinitionException) }
        kotlin.runCatching { context.getBean(DeliveryMissionExpiryScheduler::class.java) }
            .onSuccess { error("Delivery mission expiry scheduler should be disabled by default.") }
            .onFailure { assertTrue(it is NoSuchBeanDefinitionException) }
    }
}
