package dev.orestegabo.sequo_api.domain.auth

enum class ProtectedResource {
    CUSTOMER_ORDER,
    MERCHANT_ORDER,
    MERCHANT_CATALOG,
    MERCHANT_PAYOUT,
    DELIVERY_MISSION,
    RELAY_PARCEL,
    RETURN_REQUEST,
    USER_NOTIFICATION,
    ADMIN_OPERATION,
}

enum class AuthorizationAction {
    READ,
    CREATE,
    UPDATE,
    DELETE,
    FINANCIAL,
}

data class AuthorizationSubject(
    val actorUserId: String,
    val roles: Set<RoleCode>,
    val customerUserId: String? = null,
    val merchantId: String? = null,
    val merchantScopeIds: Set<String> = emptySet(),
    val courierUserId: String? = null,
    val relayUserId: String? = null,
) {
    init {
        require(actorUserId.isNotBlank()) { "actorUserId cannot be blank." }
        require(roles.isNotEmpty()) { "At least one role is required." }
        require(listOf(customerUserId, merchantId, courierUserId, relayUserId).filterNotNull().all { it.isNotBlank() }) {
            "Resource ownership IDs cannot be blank."
        }
        require(merchantScopeIds.all { it.isNotBlank() }) { "merchantScopeIds cannot contain blank IDs." }
    }
}

data class AuthorizationDecision(
    val allowed: Boolean,
    val reason: String,
)

class AuthorizationPolicy {
    fun decide(
        resource: ProtectedResource,
        action: AuthorizationAction,
        subject: AuthorizationSubject,
    ): AuthorizationDecision {
        if (RoleCode.SUPER_ADMIN in subject.roles) return allow("Super admin access.")
        if (RoleCode.ADMIN in subject.roles && action != AuthorizationAction.DELETE) {
            return allow("Admin operational access.")
        }
        if (resource == ProtectedResource.ADMIN_OPERATION) {
            return if (action == AuthorizationAction.READ && RoleCode.SUPPORT_AGENT in subject.roles) {
                allow("Support agent can read administrative monitoring.")
            } else {
                deny("Only support, admin, or super admin can access administrative operations.")
            }
        }
        if (action == AuthorizationAction.FINANCIAL && subject.roles.none { it in FINANCIAL_ROLES }) {
            return deny("Financial access requires an authorized operational role.")
        }

        return when (resource) {
            ProtectedResource.CUSTOMER_ORDER,
            ProtectedResource.RETURN_REQUEST,
            ProtectedResource.USER_NOTIFICATION -> if (RoleCode.CUSTOMER in subject.roles && subject.customerUserId == subject.actorUserId) {
                allow("Customer owns the resource.")
            } else deny("Customer resource ownership is required.")
            ProtectedResource.MERCHANT_ORDER,
            ProtectedResource.MERCHANT_CATALOG,
            ProtectedResource.MERCHANT_PAYOUT -> if (hasMerchantScope(subject)) {
                allow("Merchant scope owns the resource.")
            } else deny("Merchant ownership or granted merchant scope is required.")
            ProtectedResource.DELIVERY_MISSION -> if (RoleCode.COURIER in subject.roles && subject.courierUserId == subject.actorUserId) {
                allow("Courier is assigned to the mission.")
            } else deny("Only the assigned courier or an authorized operator can access the mission.")
            ProtectedResource.RELAY_PARCEL -> if (RoleCode.RELAY_PARTNER in subject.roles && subject.relayUserId == subject.actorUserId) {
                allow("Relay partner owns the parcel operation.")
            } else deny("Relay ownership is required.")
            ProtectedResource.ADMIN_OPERATION -> deny("Administrative access is restricted.")
        }
    }

    private fun hasMerchantScope(subject: AuthorizationSubject): Boolean =
        (RoleCode.MERCHANT_OWNER in subject.roles || RoleCode.MERCHANT_STAFF in subject.roles) &&
            subject.merchantId != null && subject.merchantId in subject.merchantScopeIds

    private fun allow(reason: String) = AuthorizationDecision(true, reason)

    private fun deny(reason: String) = AuthorizationDecision(false, reason)

    private companion object {
        val FINANCIAL_ROLES = setOf(RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    }
}
