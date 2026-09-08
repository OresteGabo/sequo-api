package dev.orestegabo.sequo_api.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthorizationPolicyTest {
    private val policy = AuthorizationPolicy()

    @Test
    fun customerCanReadOnlyOwnOrderAndNotification() {
        val own = subject("customer-1", RoleCode.CUSTOMER, customerUserId = "customer-1")
        val other = subject("customer-1", RoleCode.CUSTOMER, customerUserId = "customer-2")

        assertTrue(policy.decide(ProtectedResource.CUSTOMER_ORDER, AuthorizationAction.READ, own).allowed)
        assertTrue(policy.decide(ProtectedResource.USER_NOTIFICATION, AuthorizationAction.READ, own).allowed)
        assertFalse(policy.decide(ProtectedResource.CUSTOMER_ORDER, AuthorizationAction.READ, other).allowed)
    }

    @Test
    fun merchantStaffNeedsExplicitMerchantScope() {
        val scoped = subject("staff-1", RoleCode.MERCHANT_STAFF, merchantId = "merchant-a", merchantScopeIds = setOf("merchant-a"))
        val unscoped = subject("staff-1", RoleCode.MERCHANT_STAFF, merchantId = "merchant-b", merchantScopeIds = setOf("merchant-a"))

        assertTrue(policy.decide(ProtectedResource.MERCHANT_ORDER, AuthorizationAction.UPDATE, scoped).allowed)
        assertFalse(policy.decide(ProtectedResource.MERCHANT_ORDER, AuthorizationAction.UPDATE, unscoped).allowed)
    }

    @Test
    fun courierAndRelayMustOwnTheirAssignedOperationalResource() {
        val courier = subject("rider-1", RoleCode.COURIER, courierUserId = "rider-1")
        val relay = subject("relay-1", RoleCode.RELAY_PARTNER, relayUserId = "relay-1")

        assertTrue(policy.decide(ProtectedResource.DELIVERY_MISSION, AuthorizationAction.UPDATE, courier).allowed)
        assertTrue(policy.decide(ProtectedResource.RELAY_PARCEL, AuthorizationAction.UPDATE, relay).allowed)
        assertFalse(policy.decide(ProtectedResource.DELIVERY_MISSION, AuthorizationAction.UPDATE, relay).allowed)
    }

    @Test
    fun financialActionsStayRestrictedToAdmins() {
        val merchant = subject("merchant-1", RoleCode.MERCHANT_OWNER, merchantId = "merchant-1", merchantScopeIds = setOf("merchant-1"))
        val admin = subject("admin-1", RoleCode.ADMIN)

        assertFalse(policy.decide(ProtectedResource.MERCHANT_PAYOUT, AuthorizationAction.FINANCIAL, merchant).allowed)
        assertTrue(policy.decide(ProtectedResource.MERCHANT_PAYOUT, AuthorizationAction.FINANCIAL, admin).allowed)
    }

    @Test
    fun invalidSubjectIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AuthorizationSubject(actorUserId = "user-1", roles = emptySet())
        }
        assertEquals("Only admin or super admin can access administrative operations.", policy.decide(ProtectedResource.ADMIN_OPERATION, AuthorizationAction.READ, subject("user-1", RoleCode.CUSTOMER)).reason)
    }

    private fun subject(
        actor: String,
        role: RoleCode,
        customerUserId: String? = null,
        merchantId: String? = null,
        merchantScopeIds: Set<String> = emptySet(),
        courierUserId: String? = null,
        relayUserId: String? = null,
    ) = AuthorizationSubject(actor, setOf(role), customerUserId, merchantId, merchantScopeIds, courierUserId, relayUserId)
}
