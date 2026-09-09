package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class RealtimeSubscriptionAuthorizationServiceTest {
    private val service = RealtimeSubscriptionAuthorizationService()

    @Test
    fun authenticatedUsersCanSubscribeToTheirNotificationQueue() {
        val decision = service.authorize(
            auth("customer-1", RoleCode.CUSTOMER),
            "/user/queue/notifications",
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun courierMissionQueueRequiresCourierRole() {
        assertTrue(service.authorize(auth("rider-1", RoleCode.COURIER), "/user/queue/rider-missions").allowed)
        assertFalse(service.authorize(auth("customer-1", RoleCode.CUSTOMER), "/user/queue/rider-missions").allowed)
    }

    @Test
    fun bargainingQueueAllowsCustomersAndMerchantsOnly() {
        assertTrue(service.authorize(auth("customer-1", RoleCode.CUSTOMER), "/user/queue/bargaining").allowed)
        assertTrue(service.authorize(auth("merchant-1", RoleCode.MERCHANT_OWNER), "/user/queue/bargaining").allowed)
        assertFalse(service.authorize(auth("support-1", RoleCode.SUPPORT_AGENT), "/user/queue/bargaining").allowed)
    }

    @Test
    fun adminTopicRequiresOperationalRole() {
        assertTrue(service.authorize(auth("support-1", RoleCode.SUPPORT_AGENT), "/topic/admin/operations").allowed)
        assertTrue(service.authorize(auth("admin-1", RoleCode.ADMIN), "/topic/admin/operations").allowed)
        assertFalse(service.authorize(auth("customer-1", RoleCode.CUSTOMER), "/topic/admin/operations").allowed)
    }

    @Test
    fun unknownTopicsAreDeniedByDefault() {
        assertFalse(service.authorize(auth("customer-1", RoleCode.CUSTOMER), "/topic/orders/all").allowed)
        assertFalse(service.authorize(null, "/user/queue/notifications").allowed)
    }

    private fun auth(userId: String, vararg roles: RoleCode) =
        UsernamePasswordAuthenticationToken(
            userId,
            null,
            roles.map { it.toGrantedAuthority() },
        )
}
