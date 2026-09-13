package dev.orestegabo.sequo_api.domain.hub

import dev.orestegabo.sequo_api.domain.notification.NotificationAppFamily
import java.time.DayOfWeek
import java.time.LocalDate
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface RelayLockerRepository : JpaRepository<RelayLockerRecord, String> {
    fun findByRelayPointIdAndLockerCode(relayPointId: String, lockerCode: String): RelayLockerRecord?

    fun findByRelayPointId(relayPointId: String): List<RelayLockerRecord>

    fun countByRelayPointIdAndStatus(relayPointId: String, status: HubLockerStatus): Long
}

interface HubOpeningHourRepository : JpaRepository<HubOpeningHourRecord, String> {
    fun findByRelayPointIdAndDayOfWeek(relayPointId: String, dayOfWeek: DayOfWeek): HubOpeningHourRecord?

    fun findByRelayPointIdOrderByDayOfWeekAsc(relayPointId: String): List<HubOpeningHourRecord>

    fun deleteByRelayPointId(relayPointId: String)
}

interface HubOpeningHourExceptionRepository : JpaRepository<HubOpeningHourExceptionRecord, String> {
    fun findByRelayPointIdAndExceptionDate(relayPointId: String, exceptionDate: LocalDate): HubOpeningHourExceptionRecord?

    fun findByRelayPointIdOrderByExceptionDateAsc(relayPointId: String): List<HubOpeningHourExceptionRecord>

    fun deleteByRelayPointId(relayPointId: String)
}

interface AccountDeletionRequestRepository : JpaRepository<AccountDeletionRequestRecord, String> {
    fun findFirstByUserIdAndStatusInOrderByRequestedAtDesc(
        userId: String,
        statuses: Collection<AccountDeletionRequestStatus>,
    ): AccountDeletionRequestRecord?
}

interface HubControlDecisionRepository : JpaRepository<HubControlDecisionRecord, String> {
    fun findByRelayPointIdAndIdempotencyKey(
        relayPointId: String,
        idempotencyKey: String,
    ): HubControlDecisionRecord?

    fun findByRelayPointIdOrderByCreatedAtDesc(
        relayPointId: String,
        pageable: Pageable,
    ): List<HubControlDecisionRecord>

    fun findByRelayPointIdAndTargetOrderByCreatedAtDesc(
        relayPointId: String,
        target: HubControlTarget,
        pageable: Pageable,
    ): List<HubControlDecisionRecord>
}

interface UserAppPreferenceRepository : JpaRepository<UserAppPreferenceRecord, String> {
    fun findByUserIdAndAppFamily(userId: String, appFamily: NotificationAppFamily): UserAppPreferenceRecord?
}
