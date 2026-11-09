package dev.orestegabo.sequo_api.domain.auth

import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority

val RoleCode.authority: String
    get() = "ROLE_$name"

fun RoleCode.toGrantedAuthority(): SimpleGrantedAuthority = SimpleGrantedAuthority(authority)

fun Authentication.hasRole(role: RoleCode): Boolean =
    authorities.any { it.authority == role.authority ||
        (role == RoleCode.ADMIN && it.authority == RoleCode.STAFF_MANAGER.authority) }

fun Authentication.hasAnyRole(roles: Set<RoleCode>): Boolean =
    roles.any(::hasRole)

fun Authentication.merchantScopeIds(): Set<String> =
    (details as? JwtAuthenticationDetails)?.merchantScopeIds ?: emptySet()

fun Authentication.hasMerchantScope(merchantId: String): Boolean =
    merchantId.isNotBlank() && (name == merchantId || merchantId in merchantScopeIds())

object RoleGroups {
    val AdminOnly = setOf(RoleCode.ADMIN, RoleCode.STAFF_MANAGER, RoleCode.SUPER_ADMIN)
    val StaffManagers = setOf(RoleCode.STAFF_MANAGER, RoleCode.SUPER_ADMIN)
    val PersonnelRoles = setOf(RoleCode.COURIER, RoleCode.RELAY_PARTNER, RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF)
    val ProtectedAdministrators = AdminOnly + RoleCode.SUPPORT_AGENT
    val AdminOperations = AdminOnly + RoleCode.SUPPORT_AGENT
    val MerchantOperators = AdminOnly + setOf(RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF)
    val DeliveryMissionReaders = AdminOperations + RoleCode.COURIER
    val CustomerDeliveryTrackingReaders = AdminOperations + RoleCode.CUSTOMER
    val RelayParcelCreators = AdminOnly + setOf(RoleCode.RELAY_PARTNER, RoleCode.COURIER)
    val RelayOperators = AdminOnly + RoleCode.RELAY_PARTNER
    val DeliveryProblemReporters = setOf(
        RoleCode.COURIER,
        RoleCode.RELAY_PARTNER,
        RoleCode.SUPPORT_AGENT,
        RoleCode.ADMIN,
        RoleCode.STAFF_MANAGER,
        RoleCode.SUPER_ADMIN,
    )
}
