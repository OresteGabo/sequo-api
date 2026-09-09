package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import org.springframework.stereotype.Service

data class NotificationRoutingContext(
    val customerUserId: String? = null,
    val merchantUserIds: Set<String> = emptySet(),
    val relayUserIds: Set<String> = emptySet(),
    val riderUserIds: Set<String> = emptySet(),
    val supportUserIds: Set<String> = emptySet(),
    val adminUserIds: Set<String> = emptySet(),
    val superAdminUserIds: Set<String> = emptySet(),
) {
    init {
        require(customerUserId == null || customerUserId.isNotBlank()) { "customerUserId must not be blank." }
        require(allIds().all { it.isNotBlank() }) { "Notification recipient IDs must not be blank." }
    }

    private fun allIds(): Set<String> =
        buildSet {
            customerUserId?.let(::add)
            addAll(merchantUserIds)
            addAll(relayUserIds)
            addAll(riderUserIds)
            addAll(supportUserIds)
            addAll(adminUserIds)
            addAll(superAdminUserIds)
        }
}

data class NotificationRecipient(
    val userId: String,
    val role: RoleCode,
    val appFamily: NotificationAppFamily,
)

@Service
class NotificationRoutingService {
    fun route(
        eventType: NotificationEventType,
        context: NotificationRoutingContext,
    ): List<NotificationRecipient> {
        val recipients = linkedSetOf<NotificationRecipient>()
        when (eventType) {
            NotificationEventType.ORDER_CREATED,
            NotificationEventType.PAYMENT_CONFIRMED -> {
                addCustomer(recipients, context)
                addMerchants(recipients, context)
                if (eventType == NotificationEventType.PAYMENT_CONFIRMED) addOperations(recipients, context)
            }
            NotificationEventType.BARGAINING_PROPOSAL_CREATED -> addMerchants(recipients, context)
            NotificationEventType.BARGAINING_COUNTERED,
            NotificationEventType.BARGAINING_ACCEPTED -> {
                addCustomer(recipients, context)
                if (eventType == NotificationEventType.BARGAINING_ACCEPTED) addMerchants(recipients, context)
            }
            NotificationEventType.MERCHANT_ACCEPTED_ORDER,
            NotificationEventType.MERCHANT_REJECTED_ORDER,
            NotificationEventType.ORDER_PREPARING,
            NotificationEventType.RIDER_PICKED_UP,
            NotificationEventType.RIDER_ARRIVED,
            NotificationEventType.DIRECT_DELIVERED,
            NotificationEventType.RELAY_PICKUP_CODE_CREATED,
            NotificationEventType.RETURN_PIN_CREATED,
            NotificationEventType.REFUND_TRIGGERED -> addCustomer(recipients, context)
            NotificationEventType.ORDER_READY_FOR_PICKUP -> {
                addMerchants(recipients, context)
                addRiders(recipients, context)
                addOperations(recipients, context)
            }
            NotificationEventType.RIDER_MISSION_OFFERED -> addRiders(recipients, context)
            NotificationEventType.RIDER_ACCEPTED_MISSION -> {
                addCustomer(recipients, context)
                addMerchants(recipients, context)
            }
            NotificationEventType.RELAY_PARCEL_DEPOSITED -> {
                addCustomer(recipients, context)
                addRelays(recipients, context)
                addOperations(recipients, context)
            }
            NotificationEventType.RELAY_PARCEL_DELAYED -> {
                addCustomer(recipients, context)
                addRelays(recipients, context)
                addOperations(recipients, context)
            }
            NotificationEventType.RETURN_REQUESTED -> {
                addCustomer(recipients, context)
                addMerchants(recipients, context)
                addSupport(recipients, context)
            }
            NotificationEventType.RETURN_RECEIVED_BY_SEQUO -> {
                addCustomer(recipients, context)
                addMerchants(recipients, context)
            }
            NotificationEventType.MERCHANT_PAYOUT_ELIGIBLE -> {
                addMerchants(recipients, context)
                addOperations(recipients, context)
            }
            NotificationEventType.PAYOUT_SENT -> addMerchants(recipients, context)
            NotificationEventType.DELIVERY_PROBLEM_REPORTED -> {
                addCustomer(recipients, context)
                addRiders(recipients, context)
                addSupport(recipients, context)
                addOperations(recipients, context)
            }
            NotificationEventType.MISSING_DEPOT_BLOCKED_TOUR -> {
                addCustomer(recipients, context)
                addMerchants(recipients, context)
                addOperations(recipients, context)
            }
        }
        return recipients
            .groupBy { it.userId }
            .values
            .map { sameUser -> sameUser.maxBy(::rolePriority) }
            .sortedBy { it.userId }
    }

    private fun addCustomer(target: MutableSet<NotificationRecipient>, context: NotificationRoutingContext) {
        context.customerUserId?.let { target += NotificationRecipient(it, RoleCode.CUSTOMER, NotificationAppFamily.SEQUO_CUSTOMER) }
    }

    private fun addMerchants(target: MutableSet<NotificationRecipient>, context: NotificationRoutingContext) {
        context.merchantUserIds.forEach { target += NotificationRecipient(it, RoleCode.MERCHANT_OWNER, NotificationAppFamily.SEQUO_MERCHANT) }
    }

    private fun addRelays(target: MutableSet<NotificationRecipient>, context: NotificationRoutingContext) {
        context.relayUserIds.forEach { target += NotificationRecipient(it, RoleCode.RELAY_PARTNER, NotificationAppFamily.SEQUO_HUB) }
    }

    private fun addRiders(target: MutableSet<NotificationRecipient>, context: NotificationRoutingContext) {
        context.riderUserIds.forEach { target += NotificationRecipient(it, RoleCode.COURIER, NotificationAppFamily.SEQUO_RIDER) }
    }

    private fun addSupport(target: MutableSet<NotificationRecipient>, context: NotificationRoutingContext) {
        context.supportUserIds.forEach { target += NotificationRecipient(it, RoleCode.SUPPORT_AGENT, NotificationAppFamily.SEQUO_ADMIN) }
    }

    private fun addOperations(target: MutableSet<NotificationRecipient>, context: NotificationRoutingContext) {
        context.adminUserIds.forEach { target += NotificationRecipient(it, RoleCode.ADMIN, NotificationAppFamily.SEQUO_ADMIN) }
        context.superAdminUserIds.forEach { target += NotificationRecipient(it, RoleCode.SUPER_ADMIN, NotificationAppFamily.SEQUO_ADMIN) }
    }

    private fun rolePriority(recipient: NotificationRecipient): Int =
        when (recipient.role) {
            RoleCode.CUSTOMER -> 0
            RoleCode.MERCHANT_OWNER,
            RoleCode.MERCHANT_STAFF,
            RoleCode.COURIER,
            RoleCode.RELAY_PARTNER -> 1
            RoleCode.SUPPORT_AGENT -> 2
            RoleCode.ADMIN -> 3
            RoleCode.SUPER_ADMIN -> 4
        }
}
