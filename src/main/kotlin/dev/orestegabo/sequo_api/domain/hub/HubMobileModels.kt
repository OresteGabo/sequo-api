package dev.orestegabo.sequo_api.domain.hub

import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.notification.NotificationAppFamily
import dev.orestegabo.sequo_api.domain.party.RelayPointRecord
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class HubScanCredentialType {
    QR_TOKEN,
    PICKUP_CODE,
    PACKAGE_CODE,
    RETURN_ID,
    COLLECTION_BATCH_CODE,
    AUTO,
}

enum class HubWorkflowType {
    CUSTOMER_PICKUP,
    PACKAGE_INTAKE,
    CUSTOMER_RETURN_DROPOFF,
    SEQUO_COLLECTION,
    UNKNOWN,
}

enum class HubLockerStatus {
    AVAILABLE,
    OCCUPIED,
    MAINTENANCE,
}

enum class HubLockerAvailabilityReason {
    BROKEN_DOOR,
    JAMMED_LOCK,
    DIRTY,
    WRONG_CONTENTS,
    OTHER,
    OPERATOR_CONFIRMED_AVAILABLE,
    SYSTEM_OCCUPIED,
}

enum class UserPreferenceTheme {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class AccountDeletionRequestStatus {
    REQUESTED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
    CANCELLED,
    COMPLETED,
}

enum class HubControlTarget {
    HUB,
    LOCKER_INTAKE,
    CUSTOMER_PICKUP,
    CUSTOMER_RETURNS,
    SEQUO_COLLECTION,
    PLAN_B_DROP_OFF,
}

enum class HubControlStatus {
    ACTIVE,
    PAUSED,
    DISABLED,
}

enum class HubControlActorType {
    SEQUO_OPERATOR,
    SEQUO_AI,
    SYSTEM_POLICY,
}

enum class HubControlReasonCode {
    RISK_REVIEW,
    PARTNER_SUSPENSION,
    CAPACITY_LOCK,
    FRAUD_SIGNAL,
    MAINTENANCE,
    COMPLIANCE_REVIEW,
    EMERGENCY,
    OTHER,
}

enum class HubEffectiveMode {
    NORMAL,
    HUB_PAUSED,
    HUB_DISABLED,
    INTAKE_PAUSED_PICKUP_ALLOWED,
    SERVICE_RESTRICTED,
}

@Entity
@Table(name = "relay_lockers")
class RelayLockerRecord(
    @Id
    @Column(name = "id")
    val id: String,

    @Column(name = "relay_point_id", nullable = false)
    val relayPointId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_point_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_lockers_relay_point"))
    val relayPoint: RelayPointRecord? = null,

    @Column(name = "relay_locker_grid_id", nullable = false)
    val relayLockerGridId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_locker_grid_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_lockers_grid"))
    val relayLockerGrid: RelayLockerGridRecord? = null,

    @Column(name = "locker_code", nullable = false, length = 64)
    val lockerCode: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    var status: HubLockerStatus = HubLockerStatus.AVAILABLE,

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_reason", length = 64)
    var availabilityReason: HubLockerAvailabilityReason? = null,

    @Column(name = "expected_available_at")
    var expectedAvailableAt: Instant? = null,

    @Column(name = "updated_by_user_id")
    var updatedByUserId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_lockers_updated_by"))
    val updatedByUser: User? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "relay_locker_grids")
class RelayLockerGridRecord(
    @Id
    @Column(name = "id")
    val id: String,

    @Column(name = "relay_point_id", nullable = false)
    val relayPointId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_point_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_relay_locker_grids_relay_point"))
    val relayPoint: RelayPointRecord? = null,

    @Column(name = "grid_code", nullable = false, length = 64)
    var gridCode: String,

    @Column(name = "label", nullable = false)
    var label: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "hub_opening_hours")
class HubOpeningHourRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "relay_point_id", nullable = false)
    val relayPointId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_point_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_hub_opening_hours_relay_point"))
    val relayPoint: RelayPointRecord? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    val dayOfWeek: DayOfWeek,

    @Column(name = "timezone", nullable = false, length = 128)
    var timezone: String,

    @Column(name = "is_open", nullable = false)
    var isOpen: Boolean,

    @Column(name = "opens_at")
    var opensAt: LocalTime? = null,

    @Column(name = "closes_at")
    var closesAt: LocalTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "hub_opening_hour_exceptions")
class HubOpeningHourExceptionRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "relay_point_id", nullable = false)
    val relayPointId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_point_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_hub_opening_hour_exceptions_relay_point"))
    val relayPoint: RelayPointRecord? = null,

    @Column(name = "exception_date", nullable = false)
    val exceptionDate: LocalDate,

    @Column(name = "is_closed", nullable = false)
    var isClosed: Boolean,

    @Column(name = "opens_at")
    var opensAt: LocalTime? = null,

    @Column(name = "closes_at")
    var closesAt: LocalTime? = null,

    @Column(name = "reason", length = 255)
    var reason: String? = null,

    @Column(name = "effective_until")
    var effectiveUntil: LocalDate? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "account_deletion_requests")
class AccountDeletionRequestRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_account_deletion_requests_user"))
    val user: User? = null,

    @Column(name = "reason", length = 500)
    val reason: String? = null,

    @Column(name = "confirmation", nullable = false, length = 128)
    val confirmation: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    var status: AccountDeletionRequestStatus = AccountDeletionRequestStatus.REQUESTED,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant = Instant.now(),

    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "hub_control_decisions")
class HubControlDecisionRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "relay_point_id", nullable = false)
    val relayPointId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_point_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_hub_control_decisions_relay_point"))
    val relayPoint: RelayPointRecord? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false, length = 64)
    val target: HubControlTarget,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    val status: HubControlStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 64)
    val reasonCode: HubControlReasonCode,

    @Column(name = "staff_message", nullable = false, length = 500)
    val staffMessage: String,

    @Column(name = "customer_message", length = 500)
    val customerMessage: String? = null,

    @Column(name = "effective_until")
    val effectiveUntil: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 64)
    val actorType: HubControlActorType,

    @Column(name = "actor_id", nullable = false)
    val actorId: String,

    @Column(name = "source", nullable = false, length = 128)
    val source: String,

    @Column(name = "incident_reference_id")
    val incidentReferenceId: String? = null,

    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "user_app_preferences")
class UserAppPreferenceRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false, foreignKey = ForeignKey(name = "fk_user_app_preferences_user"))
    val user: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "app_family", nullable = false, length = 64)
    val appFamily: NotificationAppFamily,

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 64)
    var theme: UserPreferenceTheme = UserPreferenceTheme.SYSTEM,

    @Column(name = "language", nullable = false, length = 16)
    var language: String = "fr",

    @Column(name = "quick_scan_on_open", nullable = false)
    var quickScanOnOpen: Boolean = false,

    @Column(name = "sound_feedback", nullable = false)
    var soundFeedback: Boolean = true,

    @Column(name = "large_locker_labels", nullable = false)
    var largeLockerLabels: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
