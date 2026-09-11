package dev.orestegabo.sequo_api.domain.auth

import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority

val RoleCode.authority: String
    get() = "ROLE_$name"

fun RoleCode.toGrantedAuthority(): SimpleGrantedAuthority = SimpleGrantedAuthority(authority)

fun Authentication.hasRole(role: RoleCode): Boolean =
    authorities.any { it.authority == role.authority }

fun Authentication.hasAnyRole(roles: Set<RoleCode>): Boolean =
    roles.any(::hasRole)

fun Authentication.merchantScopeIds(): Set<String> =
    (details as? JwtAuthenticationDetails)?.merchantScopeIds ?: emptySet()

fun Authentication.hasMerchantScope(merchantId: String): Boolean =
    merchantId.isNotBlank() && (name == merchantId || merchantId in merchantScopeIds())

object RoleGroups {
    val AdminOnly = setOf(RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    val AdminOperations = setOf(RoleCode.SUPPORT_AGENT, RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    val MerchantOperators = setOf(RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF, RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    val DeliveryMissionReaders = setOf(RoleCode.COURIER, RoleCode.SUPPORT_AGENT, RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    val CustomerDeliveryTrackingReaders = setOf(RoleCode.CUSTOMER, RoleCode.SUPPORT_AGENT, RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    val RelayParcelCreators = setOf(RoleCode.RELAY_PARTNER, RoleCode.COURIER, RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    val RelayOperators = setOf(RoleCode.RELAY_PARTNER, RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    val DeliveryProblemReporters = setOf(
        RoleCode.COURIER,
        RoleCode.RELAY_PARTNER,
        RoleCode.SUPPORT_AGENT,
        RoleCode.ADMIN,
        RoleCode.SUPER_ADMIN,
    )
}
