package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NotificationRoutingServiceTest {
    private val service = NotificationRoutingService()
    private val context = NotificationRoutingContext(
        customerUserId = "customer-1",
        merchantUserIds = setOf("merchant-1", "merchant-2"),
        relayUserIds = setOf("relay-1"),
        riderUserIds = setOf("rider-1"),
        supportUserIds = setOf("support-1"),
        adminUserIds = setOf("admin-1"),
        superAdminUserIds = setOf("super-admin-1"),
    )

    @Test
    fun routesOrderCreatedToCustomerAndOwningMerchantsOnly() {
        val recipients = service.route(NotificationEventType.ORDER_CREATED, context)

        assertEquals(
            setOf("customer-1", "merchant-1", "merchant-2"),
            recipients.map { it.userId }.toSet(),
        )
        assertTrue(recipients.filter { it.role == RoleCode.MERCHANT_OWNER }.all { it.appFamily == NotificationAppFamily.SEQUO_MERCHANT })
    }

    @Test
    fun routesRelayAndOperationsForDelayedParcel() {
        val recipients = service.route(NotificationEventType.RELAY_PARCEL_DELAYED, context)

        assertEquals(
            setOf("customer-1", "relay-1", "admin-1", "super-admin-1"),
            recipients.map { it.userId }.toSet(),
        )
        assertTrue(recipients.none { it.userId == "rider-1" || it.userId == "support-1" })
    }

    @Test
    fun routesDeliveryProblemToCustomerRiderSupportAndOperations() {
        val recipients = service.route(NotificationEventType.DELIVERY_PROBLEM_REPORTED, context)

        assertEquals(
            setOf("customer-1", "rider-1", "support-1", "admin-1", "super-admin-1"),
            recipients.map { it.userId }.toSet(),
        )
        assertEquals(1, recipients.count { it.role == RoleCode.COURIER })
        assertEquals(1, recipients.count { it.role == RoleCode.SUPPORT_AGENT })
    }

    @Test
    fun routesMerchantSlaWarningToCustomerMerchantSupportAndOperations() {
        val recipients = service.route(NotificationEventType.MERCHANT_SLA_WARNING, context)

        assertEquals(
            setOf("customer-1", "merchant-1", "merchant-2", "support-1", "admin-1", "super-admin-1"),
            recipients.map { it.userId }.toSet(),
        )
        assertEquals(2, recipients.count { it.role == RoleCode.MERCHANT_OWNER })
    }

    @Test
    fun doesNotCreateDuplicatesWhenSameUserAppearsInTwoOperationalLists() {
        val recipients = service.route(
            NotificationEventType.PAYMENT_CONFIRMED,
            context.copy(adminUserIds = setOf("ops-1"), superAdminUserIds = setOf("ops-1")),
        )

        assertEquals(1, recipients.count { it.userId == "ops-1" })
    }

    @Test
    fun rejectsBlankRecipientIds() {
        assertFailsWith<IllegalArgumentException> {
            NotificationRoutingContext(merchantUserIds = setOf(" "))
        }
    }
}
