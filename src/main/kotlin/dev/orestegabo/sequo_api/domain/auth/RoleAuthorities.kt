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

object RoleGroups {
    val AdminOnly = setOf(RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
    val AdminOperations = setOf(RoleCode.SUPPORT_AGENT, RoleCode.ADMIN, RoleCode.SUPER_ADMIN)
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
