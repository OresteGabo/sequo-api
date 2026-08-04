package dev.orestegabo.sequo_api.domain.notification

import org.springframework.stereotype.Component

data class NotificationChannelRequest(
    val eventType: NotificationEventType,
    val severity: NotificationSeverity,
    val activeWebSocketSessions: Int,
    val activeFcmTokenCount: Int,
    val fcmDeliveryFailed: Boolean = false,
    val smsFallbackAllowed: Boolean = false,
    val userSmsEnabled: Boolean = true,
    val smsBudgetRemaining: Int = 0,
) {
    init {
        require(activeWebSocketSessions >= 0) { "activeWebSocketSessions must be non-negative." }
        require(activeFcmTokenCount >= 0) { "activeFcmTokenCount must be non-negative." }
        require(smsBudgetRemaining >= 0) { "smsBudgetRemaining must be non-negative." }
    }
}

data class NotificationChannelPlan(
    val channels: Set<NotificationChannel>,
    val smsSuppressedReason: String?,
)

@Component
class NotificationChannelPolicy {
    fun plan(request: NotificationChannelRequest): NotificationChannelPlan {
        val channels = linkedSetOf(NotificationChannel.IN_APP)

        if (request.activeWebSocketSessions > 0) {
            channels += NotificationChannel.WEBSOCKET
        }

        if (request.shouldSendPush()) {
            channels += NotificationChannel.FCM
        }

        val smsSuppressedReason = smsSuppressedReason(request)
        if (smsSuppressedReason == null) {
            channels += NotificationChannel.SMS
        }

        return NotificationChannelPlan(
            channels = channels,
            smsSuppressedReason = smsSuppressedReason,
        )
    }

    private fun NotificationChannelRequest.shouldSendPush(): Boolean {
        if (activeFcmTokenCount == 0) return false
        if (activeWebSocketSessions == 0) return true
        if (eventType == NotificationEventType.RIDER_MISSION_OFFERED) return true
        return severity in PUSH_EVEN_WHEN_APP_ACTIVE
    }

    private fun smsSuppressedReason(request: NotificationChannelRequest): String? =
        when {
            request.eventType !in SMS_CRITICAL_EVENTS -> "sms_not_critical"
            !request.smsFallbackAllowed -> "sms_fallback_disabled"
            !request.userSmsEnabled -> "sms_user_disabled"
            request.smsBudgetRemaining <= 0 -> "sms_budget_exhausted"
            request.activeFcmTokenCount > 0 && !request.fcmDeliveryFailed -> "sms_push_available"
            else -> null
        }

    private companion object {
        private val PUSH_EVEN_WHEN_APP_ACTIVE = setOf(
            NotificationSeverity.ACTION_REQUIRED,
            NotificationSeverity.URGENT,
            NotificationSeverity.SECURITY,
            NotificationSeverity.FINANCIAL,
        )

        private val SMS_CRITICAL_EVENTS = setOf(
            NotificationEventType.RIDER_ARRIVED,
            NotificationEventType.RELAY_PICKUP_CODE_CREATED,
            NotificationEventType.RETURN_PIN_CREATED,
            NotificationEventType.DELIVERY_PROBLEM_REPORTED,
            NotificationEventType.MISSING_DEPOT_BLOCKED_TOUR,
            NotificationEventType.REFUND_TRIGGERED,
        )
    }
}
