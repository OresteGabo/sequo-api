package dev.orestegabo.sequo_api.domain.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationChannelPolicyTest {
    private val policy = NotificationChannelPolicy()

    @Test
    fun foregroundLowPriorityEventUsesInAppAndWebSocketOnly() {
        val plan = policy.plan(
            NotificationChannelRequest(
                eventType = NotificationEventType.ORDER_PREPARING,
                severity = NotificationSeverity.INFO,
                activeWebSocketSessions = 1,
                activeFcmTokenCount = 1,
            )
        )

        assertEquals(
            setOf(NotificationChannel.IN_APP, NotificationChannel.WEBSOCKET),
            plan.channels,
        )
        assertEquals("sms_not_critical", plan.smsSuppressedReason)
    }

    @Test
    fun backgroundActionRequiredEventUsesPushButNoSmsWhenPushIsAvailable() {
        val plan = policy.plan(
            NotificationChannelRequest(
                eventType = NotificationEventType.RIDER_MISSION_OFFERED,
                severity = NotificationSeverity.ACTION_REQUIRED,
                activeWebSocketSessions = 0,
                activeFcmTokenCount = 2,
                smsFallbackAllowed = true,
                smsBudgetRemaining = 10,
            )
        )

        assertTrue(NotificationChannel.FCM in plan.channels)
        assertFalse(NotificationChannel.SMS in plan.channels)
        assertEquals("sms_not_critical", plan.smsSuppressedReason)
    }

    @Test
    fun criticalRelayPickupCodeCanUseSmsWhenPushIsUnavailableAndBudgetExists() {
        val plan = policy.plan(
            NotificationChannelRequest(
                eventType = NotificationEventType.RELAY_PICKUP_CODE_CREATED,
                severity = NotificationSeverity.ACTION_REQUIRED,
                activeWebSocketSessions = 0,
                activeFcmTokenCount = 0,
                smsFallbackAllowed = true,
                smsBudgetRemaining = 1,
            )
        )

        assertEquals(setOf(NotificationChannel.IN_APP, NotificationChannel.SMS), plan.channels)
        assertNull(plan.smsSuppressedReason)
    }

    @Test
    fun criticalSmsFallbackIsSuppressedWhenBudgetIsExhausted() {
        val plan = policy.plan(
            NotificationChannelRequest(
                eventType = NotificationEventType.RELAY_PICKUP_CODE_CREATED,
                severity = NotificationSeverity.ACTION_REQUIRED,
                activeWebSocketSessions = 0,
                activeFcmTokenCount = 0,
                smsFallbackAllowed = true,
                smsBudgetRemaining = 0,
            )
        )

        assertFalse(NotificationChannel.SMS in plan.channels)
        assertEquals("sms_budget_exhausted", plan.smsSuppressedReason)
    }

    @Test
    fun disabledInAppPreferenceAlsoSuppressesWebSocketDelivery() {
        val plan = policy.plan(
            NotificationChannelRequest(
                eventType = NotificationEventType.ORDER_PREPARING,
                severity = NotificationSeverity.INFO,
                activeWebSocketSessions = 1,
                activeFcmTokenCount = 0,
                inAppEnabled = false,
            )
        )

        assertFalse(NotificationChannel.IN_APP in plan.channels)
        assertFalse(NotificationChannel.WEBSOCKET in plan.channels)
    }

    @Test
    fun disabledPushPreferenceSuppressesFcmDelivery() {
        val plan = policy.plan(
            NotificationChannelRequest(
                eventType = NotificationEventType.PAYMENT_CONFIRMED,
                severity = NotificationSeverity.FINANCIAL,
                activeWebSocketSessions = 0,
                activeFcmTokenCount = 1,
                pushEnabled = false,
            )
        )

        assertFalse(NotificationChannel.FCM in plan.channels)
    }

    @Test
    fun disabledSmsPreferenceSuppressesCriticalSmsFallback() {
        val plan = policy.plan(
            NotificationChannelRequest(
                eventType = NotificationEventType.RELAY_PICKUP_CODE_CREATED,
                severity = NotificationSeverity.ACTION_REQUIRED,
                activeWebSocketSessions = 0,
                activeFcmTokenCount = 0,
                smsFallbackAllowed = true,
                smsEnabled = false,
                smsBudgetRemaining = 1,
            )
        )

        assertFalse(NotificationChannel.SMS in plan.channels)
        assertEquals("sms_user_disabled", plan.smsSuppressedReason)
    }

    @Test
    fun quietHoursSuppressOnlyNonUrgentPush() {
        val quietInfo = policy.plan(
            NotificationChannelRequest(
                eventType = NotificationEventType.ORDER_PREPARING,
                severity = NotificationSeverity.INFO,
                activeWebSocketSessions = 0,
                activeFcmTokenCount = 1,
                quietHoursActive = true,
            )
        )
        val quietSecurity = policy.plan(
            NotificationChannelRequest(
                eventType = NotificationEventType.PAYMENT_CONFIRMED,
                severity = NotificationSeverity.FINANCIAL,
                activeWebSocketSessions = 0,
                activeFcmTokenCount = 1,
                quietHoursActive = true,
            )
        )

        assertFalse(NotificationChannel.FCM in quietInfo.channels)
        assertTrue(NotificationChannel.FCM in quietSecurity.channels)
    }
}
