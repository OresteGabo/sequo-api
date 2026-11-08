package dev.orestegabo.sequo_api.domain.relay

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import dev.orestegabo.sequo_api.domain.auth.hasRole
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service

interface RelayPointRepository : JpaRepository<RelayPointRecord, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from RelayPointRecord p where p.id = :id")
    fun lockForAdministration(id: String): RelayPointRecord?

    fun findByOperatorUserId(operatorUserId: String): List<RelayPointRecord>
}

@Service
class RelayOperatorAccess(private val points: RelayPointRepository) {
    fun permits(authentication: Authentication, relayPointId: String): Boolean =
        authentication.hasAnyRole(RoleGroups.AdminOnly) ||
            (authentication.hasRole(RoleCode.RELAY_PARTNER) &&
                points.findById(relayPointId).orElse(null)?.let {
                    it.operatorUserId == authentication.name && it.status == "ACTIVE"
                } == true)

    fun assignedPoints(authentication: Authentication): List<RelayPointRecord> =
        if (authentication.hasAnyRole(RoleGroups.AdminOnly)) points.findAll()
        else points.findByOperatorUserId(authentication.name).filter { it.status == "ACTIVE" }
}
