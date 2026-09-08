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
