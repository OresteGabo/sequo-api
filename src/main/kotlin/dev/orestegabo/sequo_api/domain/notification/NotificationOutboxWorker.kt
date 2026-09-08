package dev.orestegabo.sequo_api.domain.notification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import org.springframework.stereotype.Service

data class NotificationOutboxWorkerRunResult(
    val scannedEvents: Int,
    val claimedEvents: Int,
    val dispatchedMessages: Int,
    val sentEventIds: List<String>,
    val failedEventIds: List<String>,
)

private data class NotificationDispatchPayload(
    val customerUserId: String?,
    val merchantUserIds: Set<String>,
    val relayUserIds: Set<String>,
    val riderUserIds: Set<String>,
    val supportUserIds: Set<String>,
    val adminUserIds: Set<String>,
    val superAdminUserIds: Set<String>,
    val title: String?,
    val body: String?,
    val actionUrl: String?,
    val severity: NotificationSeverity?,
    val messagePayload: String?,
    val activeWebSocketSessionsByUserId: Map<String, Int>,
    val activeFcmTokenCountByUserId: Map<String, Int>,
    val smsFallbackAllowed: Boolean,
    val defaultSmsBudgetRemaining: Int,
    val smsBudgetRemainingByUserId: Map<String, Int>,
    val userSmsEnabledByUserId: Map<String, Boolean>,
) {
    fun routingContext(): NotificationRoutingContext =
        NotificationRoutingContext(
            customerUserId = customerUserId,
            merchantUserIds = merchantUserIds,
            relayUserIds = relayUserIds,
            riderUserIds = riderUserIds,
            supportUserIds = supportUserIds,
            adminUserIds = adminUserIds,
            superAdminUserIds = superAdminUserIds,
        )

    fun deliveryContextFor(userId: String): NotificationDeliveryContext =
        NotificationDeliveryContext(
            activeWebSocketSessions = activeWebSocketSessionsByUserId[userId] ?: 0,
            activeFcmTokenCount = activeFcmTokenCountByUserId[userId] ?: 0,
            smsFallbackAllowed = smsFallbackAllowed,
            userSmsEnabled = userSmsEnabledByUserId[userId] ?: true,
            smsBudgetRemaining = smsBudgetRemainingByUserId[userId] ?: defaultSmsBudgetRemaining,
        )

    companion object {
        val empty = NotificationDispatchPayload(
            customerUserId = null,
            merchantUserIds = emptySet(),
            relayUserIds = emptySet(),
            riderUserIds = emptySet(),
            supportUserIds = emptySet(),
            adminUserIds = emptySet(),
            superAdminUserIds = emptySet(),
            title = null,
            body = null,
            actionUrl = null,
            severity = null,
            messagePayload = null,
            activeWebSocketSessionsByUserId = emptyMap(),
            activeFcmTokenCountByUserId = emptyMap(),
            smsFallbackAllowed = false,
            defaultSmsBudgetRemaining = 0,
            smsBudgetRemainingByUserId = emptyMap(),
            userSmsEnabledByUserId = emptyMap(),
        )
    }
}

@Service
class NotificationOutboxWorker(
    private val outboxService: NotificationOutboxService,
    private val routingService: NotificationRoutingService,
    private val dispatchService: NotificationDispatchService,
) {
    private val objectMapper = ObjectMapper()

    fun dispatchReady(
        workerId: String,
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): NotificationOutboxWorkerRunResult {
        require(workerId.isNotBlank()) { "workerId cannot be blank." }
        val readyEvents = outboxService.listReady(limit, now)
        var claimedEvents = 0
        var dispatchedMessages = 0
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()

        readyEvents.forEach { ready ->
            val claimed = outboxService.claim(ready.eventId, workerId, now) ?: return@forEach
            claimedEvents += 1
            try {
                dispatchedMessages += dispatch(claimed, now)
                outboxService.markSent(claimed.eventId, workerId, now)
                sent += claimed.eventId
            } catch (e: Exception) {
                outboxService.markFailed(claimed.eventId, workerId, now)
                failed += claimed.eventId
            }
        }

        return NotificationOutboxWorkerRunResult(
            scannedEvents = readyEvents.size,
            claimedEvents = claimedEvents,
            dispatchedMessages = dispatchedMessages,
            sentEventIds = sent,
            failedEventIds = failed,
        )
    }

    private fun dispatch(event: NotificationOutboxSnapshot, occurredAt: Instant): Int {
        val payload = decode(event.payload)
        val recipients = routingService.route(event.eventType, payload.routingContext())
        require(recipients.isNotEmpty()) { "No notification recipients resolved for event ${event.eventId}." }

        recipients.forEach { recipient ->
            dispatchService.createMessageAndPlanDeliveries(
                command = CreateNotificationCommand(
                    eventId = event.eventId,
                    recipientUserId = recipient.userId,
                    appFamily = recipient.appFamily,
                    eventType = event.eventType,
                    severity = payload.severity ?: event.eventType.defaultSeverity(),
                    title = payload.title ?: event.eventType.defaultTitle(),
                    body = payload.body ?: event.eventType.defaultBody(),
                    actionUrl = payload.actionUrl,
                    payload = payload.messagePayload ?: event.payload,
                ),
                context = payload.deliveryContextFor(recipient.userId),
                occurredAt = occurredAt,
            )
        }

        return recipients.size
    }

    private fun decode(rawPayload: String?): NotificationDispatchPayload {
        if (rawPayload.isNullOrBlank()) return NotificationDispatchPayload.empty
        val root = objectMapper.readTree(rawPayload)
        return NotificationDispatchPayload(
            customerUserId = root.textOrNull("customerUserId"),
            merchantUserIds = root.stringSet("merchantUserIds", "merchantUserId"),
            relayUserIds = root.stringSet("relayUserIds", "relayUserId"),
            riderUserIds = root.stringSet("riderUserIds", "riderUserId", "courierUserIds", "courierUserId"),
            supportUserIds = root.stringSet("supportUserIds", "supportUserId"),
            adminUserIds = root.stringSet("adminUserIds", "adminUserId"),
            superAdminUserIds = root.stringSet("superAdminUserIds", "superAdminUserId"),
            title = root.textOrNull("title"),
            body = root.textOrNull("body"),
            actionUrl = root.textOrNull("actionUrl"),
            severity = root.textOrNull("severity")?.let { raw ->
                NotificationSeverity.entries.firstOrNull { it.name == raw }
                    ?: throw IllegalArgumentException("Unknown notification severity: $raw.")
            },
            messagePayload = root.rawObjectOrText("messagePayload") ?: root.rawObjectOrText("notificationPayload"),
            activeWebSocketSessionsByUserId = root.intMap("activeWebSocketSessionsByUserId"),
            activeFcmTokenCountByUserId = root.intMap("activeFcmTokenCountByUserId"),
            smsFallbackAllowed = root.booleanOrFalse("smsFallbackAllowed"),
            defaultSmsBudgetRemaining = root.nonNegativeIntOrZero("defaultSmsBudgetRemaining"),
            smsBudgetRemainingByUserId = root.intMap("smsBudgetRemainingByUserId"),
            userSmsEnabledByUserId = root.booleanMap("userSmsEnabledByUserId"),
        )
    }
}

private fun NotificationEventType.defaultSeverity(): NotificationSeverity =
    when (this) {
        NotificationEventType.RELAY_PICKUP_CODE_CREATED,
        NotificationEventType.RETURN_PIN_CREATED,
        NotificationEventType.ORDER_READY_FOR_PICKUP -> NotificationSeverity.ACTION_REQUIRED
        NotificationEventType.DELIVERY_PROBLEM_REPORTED,
        NotificationEventType.MISSING_DEPOT_BLOCKED_TOUR,
        NotificationEventType.RELAY_PARCEL_DELAYED -> NotificationSeverity.URGENT
        NotificationEventType.MERCHANT_PAYOUT_ELIGIBLE,
        NotificationEventType.PAYOUT_SENT,
        NotificationEventType.REFUND_TRIGGERED,
        NotificationEventType.PAYMENT_CONFIRMED -> NotificationSeverity.FINANCIAL
        else -> NotificationSeverity.INFO
    }

private fun NotificationEventType.defaultTitle(): String =
    when (this) {
        NotificationEventType.ORDER_CREATED -> "Order created"
        NotificationEventType.PAYMENT_CONFIRMED -> "Payment confirmed"
        NotificationEventType.BARGAINING_PROPOSAL_CREATED -> "New bargaining proposal"
        NotificationEventType.BARGAINING_COUNTERED -> "Bargaining counter received"
        NotificationEventType.BARGAINING_ACCEPTED -> "Bargaining accepted"
        NotificationEventType.MERCHANT_ACCEPTED_ORDER -> "Seller accepted the order"
        NotificationEventType.MERCHANT_REJECTED_ORDER -> "Seller rejected the order"
        NotificationEventType.ORDER_PREPARING -> "Order preparation started"
        NotificationEventType.ORDER_READY_FOR_PICKUP -> "Order ready for pickup"
        NotificationEventType.RIDER_MISSION_OFFERED -> "New delivery mission"
        NotificationEventType.RIDER_ACCEPTED_MISSION -> "Courier accepted the mission"
        NotificationEventType.RIDER_PICKED_UP -> "Package picked up"
        NotificationEventType.RIDER_ARRIVED -> "Courier arrived"
        NotificationEventType.DIRECT_DELIVERED -> "Order delivered"
        NotificationEventType.RELAY_PARCEL_DEPOSITED -> "Parcel deposited at relay"
        NotificationEventType.RELAY_PICKUP_CODE_CREATED -> "Relay pickup code ready"
        NotificationEventType.RELAY_PARCEL_DELAYED -> "Relay parcel delay"
        NotificationEventType.RETURN_REQUESTED -> "Return requested"
        NotificationEventType.RETURN_PIN_CREATED -> "Return PIN ready"
        NotificationEventType.RETURN_RECEIVED_BY_SEQUO -> "Return received by Sequo"
        NotificationEventType.REFUND_TRIGGERED -> "Refund triggered"
        NotificationEventType.MERCHANT_PAYOUT_ELIGIBLE -> "Merchant payout eligible"
        NotificationEventType.PAYOUT_SENT -> "Payout sent"
        NotificationEventType.DELIVERY_PROBLEM_REPORTED -> "Delivery problem reported"
        NotificationEventType.MISSING_DEPOT_BLOCKED_TOUR -> "Depot issue blocking tour"
    }

private fun NotificationEventType.defaultBody(): String =
    when (this) {
        NotificationEventType.ORDER_CREATED -> "Your order has been created."
        NotificationEventType.PAYMENT_CONFIRMED -> "Payment was confirmed and fulfillment can continue."
        NotificationEventType.BARGAINING_PROPOSAL_CREATED -> "A customer sent a new price proposal."
        NotificationEventType.BARGAINING_COUNTERED -> "The seller sent a counter offer."
        NotificationEventType.BARGAINING_ACCEPTED -> "The negotiated price was accepted."
        NotificationEventType.MERCHANT_ACCEPTED_ORDER -> "The seller accepted your paid order."
        NotificationEventType.MERCHANT_REJECTED_ORDER -> "The seller rejected the order and support follow-up may be needed."
        NotificationEventType.ORDER_PREPARING -> "The seller started preparing your order."
        NotificationEventType.ORDER_READY_FOR_PICKUP -> "The package is ready for Sequo pickup."
        NotificationEventType.RIDER_MISSION_OFFERED -> "A delivery mission is available."
        NotificationEventType.RIDER_ACCEPTED_MISSION -> "A courier accepted the delivery mission."
        NotificationEventType.RIDER_PICKED_UP -> "The courier picked up the package."
        NotificationEventType.RIDER_ARRIVED -> "The courier has arrived at the delivery point."
        NotificationEventType.DIRECT_DELIVERED -> "The package was delivered to the customer."
        NotificationEventType.RELAY_PARCEL_DEPOSITED -> "The parcel has been deposited at the relay point."
        NotificationEventType.RELAY_PICKUP_CODE_CREATED -> "A pickup code is available for relay collection."
        NotificationEventType.RELAY_PARCEL_DELAYED -> "A relay parcel has exceeded the expected pickup delay."
        NotificationEventType.RETURN_REQUESTED -> "A customer requested a return."
        NotificationEventType.RETURN_PIN_CREATED -> "A return PIN is ready for relay drop-off."
        NotificationEventType.RETURN_RECEIVED_BY_SEQUO -> "Sequo has received the returned item."
        NotificationEventType.REFUND_TRIGGERED -> "The refund process has started."
        NotificationEventType.MERCHANT_PAYOUT_ELIGIBLE -> "A merchant payout is eligible for processing."
        NotificationEventType.PAYOUT_SENT -> "A payout has been sent."
        NotificationEventType.DELIVERY_PROBLEM_REPORTED -> "A delivery problem needs attention."
        NotificationEventType.MISSING_DEPOT_BLOCKED_TOUR -> "A missing depot action is blocking a delivery tour."
    }

private fun JsonNode.textOrNull(fieldName: String): String? =
    path(fieldName)
        .takeIf { !it.isMissingNode && !it.isNull && it.isTextual }
        ?.asText()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun JsonNode.stringSet(vararg fieldNames: String): Set<String> =
    fieldNames.flatMap { fieldName ->
        val node = path(fieldName)
        when {
            node.isMissingNode || node.isNull -> emptyList()
            node.isArray -> node.mapNotNull { it.asCleanText() }
            node.isTextual -> listOfNotNull(node.asCleanText())
            else -> emptyList()
        }
    }.toSet()

private fun JsonNode.asCleanText(): String? =
    takeIf { it.isTextual }
        ?.asText()
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }

private fun JsonNode.rawObjectOrText(fieldName: String): String? {
    val node = path(fieldName)
    return when {
        node.isMissingNode || node.isNull -> null
        node.isTextual -> node.asText().trim().takeIf { it.isNotBlank() }
        node.isObject || node.isArray -> node.toString()
        else -> null
    }
}

private fun JsonNode.booleanOrFalse(fieldName: String): Boolean =
    path(fieldName).takeIf { it.isBoolean }?.asBoolean() ?: false

private fun JsonNode.nonNegativeIntOrZero(fieldName: String): Int =
    path(fieldName).takeIf { it.canConvertToInt() }?.asInt()?.coerceAtLeast(0) ?: 0

private fun JsonNode.intMap(fieldName: String): Map<String, Int> {
    val node = path(fieldName)
    if (!node.isObject) return emptyMap()
    return node.properties().asSequence()
        .mapNotNull { (key, value) ->
            value.takeIf { it.canConvertToInt() }?.asInt()?.coerceAtLeast(0)?.let { key to it }
        }
        .toMap()
}

private fun JsonNode.booleanMap(fieldName: String): Map<String, Boolean> {
    val node = path(fieldName)
    if (!node.isObject) return emptyMap()
    return node.properties().asSequence()
        .mapNotNull { (key, value) ->
            value.takeIf { it.isBoolean }?.asBoolean()?.let { key to it }
        }
        .toMap()
}
