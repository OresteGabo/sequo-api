package dev.orestegabo.sequo_api.domain.hub

import dev.orestegabo.sequo_api.domain.notification.NotificationAppFamily
import dev.orestegabo.sequo_api.domain.relay.RelayCredentialHasher
import dev.orestegabo.sequo_api.domain.relay.RelayParcelRecord
import dev.orestegabo.sequo_api.domain.relay.RelayParcelRecordRepository
import dev.orestegabo.sequo_api.domain.relay.RelayParcelStatus
import dev.orestegabo.sequo_api.domain.relay.RelayPickupCodeRecord
import dev.orestegabo.sequo_api.domain.relay.RelayPickupCodeRecordRepository
import dev.orestegabo.sequo_api.domain.relay.RelayStorageFeeAssessmentRecordRepository
import dev.orestegabo.sequo_api.domain.relay.Sha256RelayCredentialHasher
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class HubScanResolveCommand(
    val hubId: String,
    val credential: String,
    val credentialType: HubScanCredentialType = HubScanCredentialType.AUTO,
    val idempotencyKey: String? = null,
) {
    init {
        require(hubId.isNotBlank()) { "hubId is required." }
        require(credential.isNotBlank()) { "credential is required." }
        require(credential.length <= 128) { "credential cannot exceed 128 characters." }
        require(idempotencyKey == null || idempotencyKey.length <= 128) { "idempotencyKey cannot exceed 128 characters." }
    }
}

data class HubScanResolution(
    val workflowType: HubWorkflowType,
    val displayReference: String?,
    val parcelId: String?,
    val returnId: String?,
    val collectionBatchId: String?,
    val lockerId: String?,
    val identityVerificationRequired: Boolean,
    val feeDueCfa: Int,
    val blockingReason: String?,
)

data class HubSummarySnapshot(
    val hubId: String,
    val freeLockerCount: Long,
    val occupiedLockerCount: Long,
    val feeDuePackageCount: Long,
    val pendingSequoCollectionCount: Long,
    val openIncidentCount: Long,
    val lastSuccessfulSyncTimestamp: Instant?,
)

data class LockerAvailabilityCommand(
    val lockerId: String,
    val relayPointId: String,
    val status: HubLockerStatus,
    val reason: HubLockerAvailabilityReason?,
    val expectedAvailableAt: Instant? = null,
    val updatedByUserId: String,
) {
    init {
        require(lockerId.isNotBlank()) { "lockerId is required." }
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        require(updatedByUserId.isNotBlank()) { "updatedByUserId is required." }
        if (status == HubLockerStatus.MAINTENANCE) {
            require(reason != null) { "reason is required when a locker is placed in maintenance." }
        }
    }
}

data class LockerAvailabilitySnapshot(
    val lockerId: String,
    val relayPointId: String,
    val lockerCode: String,
    val status: HubLockerStatus,
    val reason: HubLockerAvailabilityReason?,
    val expectedAvailableAt: Instant?,
    val updatedAt: Instant,
)

data class WeeklyOpeningHourCommand(
    val dayOfWeek: DayOfWeek,
    val isOpen: Boolean,
    val opensAt: LocalTime? = null,
    val closesAt: LocalTime? = null,
)

data class OpeningHourExceptionCommand(
    val date: LocalDate,
    val isClosed: Boolean,
    val opensAt: LocalTime? = null,
    val closesAt: LocalTime? = null,
    val reason: String? = null,
    val effectiveUntil: LocalDate? = null,
)

data class SaveHubOpeningHoursCommand(
    val relayPointId: String,
    val timezone: String,
    val weeklyHours: List<WeeklyOpeningHourCommand>,
    val exceptions: List<OpeningHourExceptionCommand> = emptyList(),
    val idempotencyKey: String? = null,
) {
    init {
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        require(timezone.isNotBlank()) { "timezone is required." }
        require(weeklyHours.map { it.dayOfWeek }.toSet().size == weeklyHours.size) {
            "weeklyHours cannot contain duplicate days."
        }
        require(weeklyHours.isNotEmpty()) { "weeklyHours cannot be empty." }
        require(idempotencyKey == null || idempotencyKey.length <= 128) { "idempotencyKey cannot exceed 128 characters." }
    }
}

data class WeeklyOpeningHourSnapshot(
    val dayOfWeek: DayOfWeek,
    val isOpen: Boolean,
    val opensAt: LocalTime?,
    val closesAt: LocalTime?,
)

data class OpeningHourExceptionSnapshot(
    val date: LocalDate,
    val isClosed: Boolean,
    val opensAt: LocalTime?,
    val closesAt: LocalTime?,
    val reason: String?,
    val effectiveUntil: LocalDate?,
)

data class HubOpeningHoursSnapshot(
    val relayPointId: String,
    val timezone: String?,
    val weeklyHours: List<WeeklyOpeningHourSnapshot>,
    val exceptions: List<OpeningHourExceptionSnapshot>,
)

data class HubControlDecisionCommand(
    val relayPointId: String,
    val target: HubControlTarget,
    val status: HubControlStatus,
    val reasonCode: HubControlReasonCode,
    val staffMessage: String,
    val customerMessage: String? = null,
    val effectiveUntil: Instant? = null,
    val actorType: HubControlActorType,
    val actorId: String,
    val source: String = "hub-control-api",
    val incidentReferenceId: String? = null,
    val idempotencyKey: String,
) {
    init {
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        require(staffMessage.isNotBlank()) { "staffMessage is required." }
        require(staffMessage.length <= 500) { "staffMessage cannot exceed 500 characters." }
        require(customerMessage == null || customerMessage.isNotBlank()) { "customerMessage cannot be blank." }
        require(customerMessage == null || customerMessage.length <= 500) { "customerMessage cannot exceed 500 characters." }
        require(actorId.isNotBlank()) { "actorId is required." }
        require(source.isNotBlank()) { "source is required." }
        require(source.length <= 128) { "source cannot exceed 128 characters." }
        require(incidentReferenceId == null || incidentReferenceId.length <= 255) {
            "incidentReferenceId cannot exceed 255 characters."
        }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey is required." }
        require(idempotencyKey.length <= 128) { "idempotencyKey cannot exceed 128 characters." }
    }
}

data class HubControlTargetStateSnapshot(
    val target: HubControlTarget,
    val status: HubControlStatus,
    val reasonCode: HubControlReasonCode?,
    val staffMessage: String?,
    val customerMessage: String?,
    val effectiveUntil: Instant?,
    val source: String?,
    val actorType: HubControlActorType?,
    val decidedAt: Instant?,
)

data class HubControlStateSnapshot(
    val relayPointId: String,
    val effectiveMode: HubEffectiveMode,
    val hub: HubControlTargetStateSnapshot,
    val services: List<HubControlTargetStateSnapshot>,
    val generatedAt: Instant,
)

data class HubControlDecisionSnapshot(
    val id: String,
    val relayPointId: String,
    val target: HubControlTarget,
    val status: HubControlStatus,
    val reasonCode: HubControlReasonCode,
    val staffMessage: String,
    val customerMessage: String?,
    val effectiveUntil: Instant?,
    val actorType: HubControlActorType,
    val actorId: String,
    val source: String,
    val incidentReferenceId: String?,
    val idempotencyKey: String,
    val createdAt: Instant,
)

data class HubControlHistorySnapshot(
    val relayPointId: String,
    val decisions: List<HubControlDecisionSnapshot>,
)

data class AccountDeletionRequestCommand(
    val userId: String,
    val confirmation: String,
    val reason: String? = null,
    val idempotencyKey: String? = null,
) {
    init {
        require(userId.isNotBlank()) { "userId is required." }
        require(confirmation.isNotBlank()) { "confirmation is required." }
        require(reason == null || reason.length <= 500) { "reason cannot exceed 500 characters." }
        require(idempotencyKey == null || idempotencyKey.length <= 128) { "idempotencyKey cannot exceed 128 characters." }
    }
}

data class AccountDeletionRequestSnapshot(
    val id: String,
    val userId: String,
    val status: AccountDeletionRequestStatus,
    val requestedAt: Instant,
)

data class UserAppPreferencePatch(
    val userId: String,
    val appFamily: NotificationAppFamily,
    val theme: UserPreferenceTheme? = null,
    val language: String? = null,
    val quickScanOnOpen: Boolean? = null,
    val soundFeedback: Boolean? = null,
    val largeLockerLabels: Boolean? = null,
) {
    init {
        require(userId.isNotBlank()) { "userId is required." }
        require(language == null || language.matches(LANGUAGE_PATTERN)) { "language must be a short BCP-47-style tag." }
    }

    private companion object {
        val LANGUAGE_PATTERN = Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?")
    }
}

data class UserAppPreferenceSnapshot(
    val id: String?,
    val userId: String,
    val appFamily: NotificationAppFamily,
    val theme: UserPreferenceTheme,
    val language: String,
    val quickScanOnOpen: Boolean,
    val soundFeedback: Boolean,
    val largeLockerLabels: Boolean,
    val updatedAt: Instant?,
)

@Service
class HubMobileService(
    private val parcels: RelayParcelRecordRepository,
    private val pickupCodes: RelayPickupCodeRecordRepository,
    private val storageFees: RelayStorageFeeAssessmentRecordRepository,
    private val lockers: RelayLockerRepository,
    private val openingHours: HubOpeningHourRepository,
    private val openingHourExceptions: HubOpeningHourExceptionRepository,
    private val accountDeletionRequests: AccountDeletionRequestRepository,
    private val controlDecisions: HubControlDecisionRepository,
    private val userPreferences: UserAppPreferenceRepository,
    private val credentialHasher: RelayCredentialHasher = Sha256RelayCredentialHasher(),
) {
    @Transactional(readOnly = true)
    fun resolveScan(command: HubScanResolveCommand, now: Instant = Instant.now()): HubScanResolution =
        when (command.credentialType) {
            HubScanCredentialType.PICKUP_CODE,
            HubScanCredentialType.QR_TOKEN -> resolvePickupCredential(command, now)
            HubScanCredentialType.PACKAGE_CODE -> resolvePackageCredential(command)
            HubScanCredentialType.RETURN_ID -> resolveReturnCredential(command)
            HubScanCredentialType.COLLECTION_BATCH_CODE -> collectionNotFound(command.credential)
            HubScanCredentialType.AUTO -> resolveAuto(command, now)
        }

    @Transactional(readOnly = true)
    fun summary(hubId: String): HubSummarySnapshot {
        require(hubId.isNotBlank()) { "hubId is required." }
        return HubSummarySnapshot(
            hubId = hubId,
            freeLockerCount = lockers.countByRelayPointIdAndStatus(hubId, HubLockerStatus.AVAILABLE),
            occupiedLockerCount = lockers.countByRelayPointIdAndStatus(hubId, HubLockerStatus.OCCUPIED),
            feeDuePackageCount = storageFees.countByRelayPointIdAndTotalFeeCfaGreaterThan(hubId, 0),
            pendingSequoCollectionCount = parcels.countByRelayPointIdAndStatusIn(hubId, PENDING_SEQUO_COLLECTION_STATUSES),
            openIncidentCount = parcels.countByRelayPointIdAndStatus(hubId, RelayParcelStatus.Problem),
            lastSuccessfulSyncTimestamp = parcels.findTopByRelayPointIdOrderByUpdatedAtDesc(hubId)?.updatedAt,
        )
    }

    @Transactional
    fun updateLockerAvailability(
        command: LockerAvailabilityCommand,
        occurredAt: Instant = Instant.now(),
    ): LockerAvailabilitySnapshot {
        val existing = lockers.findById(command.lockerId).orElse(null)
        if (existing != null && existing.relayPointId != command.relayPointId) {
            throw IllegalArgumentException("Locker does not belong to the requested relay point.")
        }
        val locker = existing ?: RelayLockerRecord(
            id = command.lockerId,
            relayPointId = command.relayPointId,
            relayLockerGridId = command.relayPointId,
            lockerCode = command.lockerId,
            status = command.status,
            availabilityReason = command.reason ?: command.status.defaultReason(),
            expectedAvailableAt = command.expectedAvailableAt,
            updatedByUserId = command.updatedByUserId,
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )
        locker.status = command.status
        locker.availabilityReason = command.reason ?: command.status.defaultReason()
        locker.expectedAvailableAt = command.expectedAvailableAt
        locker.updatedByUserId = command.updatedByUserId
        locker.updatedAt = occurredAt
        return lockers.save(locker).toSnapshot()
    }

    @Transactional(readOnly = true)
    fun getOpeningHours(relayPointId: String): HubOpeningHoursSnapshot {
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        val weekly = openingHours.findByRelayPointIdOrderByDayOfWeekAsc(relayPointId)
            .sortedBy { it.dayOfWeek.value }
        val exceptions = openingHourExceptions.findByRelayPointIdOrderByExceptionDateAsc(relayPointId)
        return HubOpeningHoursSnapshot(
            relayPointId = relayPointId,
            timezone = weekly.firstOrNull()?.timezone,
            weeklyHours = weekly.map { it.toSnapshot() },
            exceptions = exceptions.map { it.toSnapshot() },
        )
    }

    @Transactional
    fun saveOpeningHours(
        command: SaveHubOpeningHoursCommand,
        occurredAt: Instant = Instant.now(),
    ): HubOpeningHoursSnapshot {
        command.weeklyHours.forEach(::validateWeeklyHours)
        command.exceptions.forEach(::validateException)

        openingHours.deleteByRelayPointId(command.relayPointId)
        openingHourExceptions.deleteByRelayPointId(command.relayPointId)

        openingHours.saveAll(
            command.weeklyHours.map {
                HubOpeningHourRecord(
                    relayPointId = command.relayPointId,
                    dayOfWeek = it.dayOfWeek,
                    timezone = command.timezone,
                    isOpen = it.isOpen,
                    opensAt = it.opensAt,
                    closesAt = it.closesAt,
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                )
            }
        )
        openingHourExceptions.saveAll(
            command.exceptions.map {
                HubOpeningHourExceptionRecord(
                    relayPointId = command.relayPointId,
                    exceptionDate = it.date,
                    isClosed = it.isClosed,
                    opensAt = it.opensAt,
                    closesAt = it.closesAt,
                    reason = it.reason,
                    effectiveUntil = it.effectiveUntil,
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                )
            }
        )

        return getOpeningHours(command.relayPointId)
    }

    @Transactional(readOnly = true)
    fun getControlState(
        relayPointId: String,
        now: Instant = Instant.now(),
    ): HubControlStateSnapshot {
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        val latestByTarget = controlDecisions.findByRelayPointIdOrderByCreatedAtDesc(
            relayPointId,
            PageRequest.of(0, CONTROL_DECISION_LOOKBACK),
        ).distinctBy { it.target }.associateBy { it.target }

        fun effectiveState(target: HubControlTarget): HubControlTargetStateSnapshot =
            latestByTarget[target]
                ?.takeUnless { it.isExpired(now) }
                ?.toTargetState()
                ?: target.defaultActiveState()

        val hubState = effectiveState(HubControlTarget.HUB)
        val serviceStates = SERVICE_CONTROL_TARGETS.map(::effectiveState)
        return HubControlStateSnapshot(
            relayPointId = relayPointId,
            effectiveMode = deriveEffectiveMode(hubState, serviceStates),
            hub = hubState,
            services = serviceStates,
            generatedAt = now,
        )
    }

    @Transactional
    fun applyControlDecision(
        command: HubControlDecisionCommand,
        occurredAt: Instant = Instant.now(),
    ): HubControlDecisionSnapshot {
        require(command.effectiveUntil == null || command.effectiveUntil.isAfter(occurredAt)) {
            "effectiveUntil must be in the future."
        }
        val existing = controlDecisions.findByRelayPointIdAndIdempotencyKey(
            command.relayPointId,
            command.idempotencyKey,
        )
        if (existing != null) {
            require(existing.matches(command)) {
                "idempotencyKey was already used for a different hub control decision."
            }
            return existing.toSnapshot()
        }

        return controlDecisions.save(
            HubControlDecisionRecord(
                relayPointId = command.relayPointId.trim(),
                target = command.target,
                status = command.status,
                reasonCode = command.reasonCode,
                staffMessage = command.staffMessage.trim(),
                customerMessage = command.customerMessage?.trim(),
                effectiveUntil = command.effectiveUntil,
                actorType = command.actorType,
                actorId = command.actorId.trim(),
                source = command.source.trim(),
                incidentReferenceId = command.incidentReferenceId?.trim(),
                idempotencyKey = command.idempotencyKey,
                createdAt = occurredAt,
            )
        ).toSnapshot()
    }

    @Transactional(readOnly = true)
    fun controlHistory(
        relayPointId: String,
        target: HubControlTarget? = null,
        limit: Int = DEFAULT_CONTROL_HISTORY_LIMIT,
    ): HubControlHistorySnapshot {
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        require(limit in 1..MAX_CONTROL_HISTORY_LIMIT) {
            "limit must be between 1 and $MAX_CONTROL_HISTORY_LIMIT."
        }
        val page = PageRequest.of(0, limit)
        val decisions = if (target == null) {
            controlDecisions.findByRelayPointIdOrderByCreatedAtDesc(relayPointId, page)
        } else {
            controlDecisions.findByRelayPointIdAndTargetOrderByCreatedAtDesc(relayPointId, target, page)
        }
        return HubControlHistorySnapshot(
            relayPointId = relayPointId,
            decisions = decisions.map { it.toSnapshot() },
        )
    }

    @Transactional
    fun requestAccountDeletion(
        command: AccountDeletionRequestCommand,
        occurredAt: Instant = Instant.now(),
    ): AccountDeletionRequestSnapshot {
        if (command.confirmation != REQUIRED_DELETION_CONFIRMATION) {
            throw IllegalArgumentException("confirmation must be $REQUIRED_DELETION_CONFIRMATION.")
        }
        val existing = accountDeletionRequests.findFirstByUserIdAndStatusInOrderByRequestedAtDesc(
            command.userId,
            ACTIVE_DELETION_STATUSES,
        )
        if (existing != null) return existing.toSnapshot()

        return accountDeletionRequests.save(
            AccountDeletionRequestRecord(
                userId = command.userId,
                reason = command.reason,
                confirmation = command.confirmation,
                requestedAt = occurredAt,
            )
        ).toSnapshot()
    }

    @Transactional(readOnly = true)
    fun getUserPreferences(
        userId: String,
        appFamily: NotificationAppFamily,
    ): UserAppPreferenceSnapshot {
        require(userId.isNotBlank()) { "userId is required." }
        return userPreferences.findByUserIdAndAppFamily(userId, appFamily)?.toSnapshot()
            ?: defaultUserPreferences(userId, appFamily)
    }

    @Transactional
    fun patchUserPreferences(
        patch: UserAppPreferencePatch,
        occurredAt: Instant = Instant.now(),
    ): UserAppPreferenceSnapshot {
        val preference = userPreferences.findByUserIdAndAppFamily(patch.userId, patch.appFamily)
            ?: UserAppPreferenceRecord(
                userId = patch.userId,
                appFamily = patch.appFamily,
                createdAt = occurredAt,
                updatedAt = occurredAt,
            )
        patch.theme?.let { preference.theme = it }
        patch.language?.let { preference.language = it }
        patch.quickScanOnOpen?.let { preference.quickScanOnOpen = it }
        patch.soundFeedback?.let { preference.soundFeedback = it }
        patch.largeLockerLabels?.let { preference.largeLockerLabels = it }
        preference.updatedAt = occurredAt
        return userPreferences.save(preference).toSnapshot()
    }

    private fun resolveAuto(command: HubScanResolveCommand, now: Instant): HubScanResolution {
        val pickup = resolvePickupCredential(command.copy(credentialType = HubScanCredentialType.PICKUP_CODE), now)
        if (pickup.workflowType != HubWorkflowType.UNKNOWN) return pickup
        val packageResolution = resolvePackageCredential(command.copy(credentialType = HubScanCredentialType.PACKAGE_CODE))
        if (packageResolution.workflowType != HubWorkflowType.UNKNOWN) return packageResolution
        return unknownCredential()
    }

    private fun resolvePickupCredential(command: HubScanResolveCommand, now: Instant): HubScanResolution {
        val credentialHash = credentialHasher.hash(command.credential)
        val code = pickupCodes.findFirstByCodeHashOrQrNonceHash(credentialHash, credentialHash)
            ?: return unknownCredential()
        val parcel = parcels.findById(code.relayParcelId).orElse(null)
            ?: return unknownCredential()
        if (parcel.relayPointId != command.hubId) return unknownCredential()
        return pickupResolution(parcel, code, now)
    }

    private fun pickupResolution(
        parcel: RelayParcelRecord,
        code: RelayPickupCodeRecord,
        now: Instant,
    ): HubScanResolution =
        HubScanResolution(
            workflowType = HubWorkflowType.CUSTOMER_PICKUP,
            displayReference = parcel.id,
            parcelId = parcel.id,
            returnId = parcel.returnId,
            collectionBatchId = null,
            lockerId = parcel.lockerId,
            identityVerificationRequired = code.identityCheckRequired,
            feeDueCfa = storageFees.findByRelayParcelId(parcel.id)?.totalFeeCfa ?: 0,
            blockingReason = when {
                parcel.status != RelayParcelStatus.Deposited -> "parcel_not_releasable"
                code.usedAt != null -> "credential_already_used"
                !now.isBefore(code.expiresAt) -> "credential_expired"
                else -> null
            },
        )

    private fun resolvePackageCredential(command: HubScanResolveCommand): HubScanResolution {
        val parcel = parcels.findByDepositCode(command.credential)
            ?.takeIf { it.relayPointId == command.hubId }
            ?: return unknownCredential()
        return HubScanResolution(
            workflowType = HubWorkflowType.PACKAGE_INTAKE,
            displayReference = parcel.id,
            parcelId = parcel.id,
            returnId = parcel.returnId,
            collectionBatchId = null,
            lockerId = parcel.lockerId,
            identityVerificationRequired = false,
            feeDueCfa = 0,
            blockingReason = if (parcel.status == RelayParcelStatus.Created) null else "package_already_intaken",
        )
    }

    private fun resolveReturnCredential(command: HubScanResolveCommand): HubScanResolution {
        val parcel = parcels.findFirstByReturnIdAndRelayPointIdOrderByUpdatedAtDesc(command.credential, command.hubId)
        return if (parcel == null) {
            HubScanResolution(
                workflowType = HubWorkflowType.CUSTOMER_RETURN_DROPOFF,
                displayReference = command.credential,
                parcelId = null,
                returnId = command.credential,
                collectionBatchId = null,
                lockerId = null,
                identityVerificationRequired = true,
                feeDueCfa = 0,
                blockingReason = null,
            )
        } else {
            HubScanResolution(
                workflowType = HubWorkflowType.CUSTOMER_RETURN_DROPOFF,
                displayReference = parcel.returnId,
                parcelId = parcel.id,
                returnId = parcel.returnId,
                collectionBatchId = null,
                lockerId = parcel.lockerId,
                identityVerificationRequired = true,
                feeDueCfa = 0,
                blockingReason = if (parcel.status == RelayParcelStatus.Deposited) "return_already_received" else null,
            )
        }
    }

    private fun collectionNotFound(collectionBatchId: String): HubScanResolution =
        HubScanResolution(
            workflowType = HubWorkflowType.SEQUO_COLLECTION,
            displayReference = collectionBatchId,
            parcelId = null,
            returnId = null,
            collectionBatchId = collectionBatchId,
            lockerId = null,
            identityVerificationRequired = true,
            feeDueCfa = 0,
            blockingReason = "collection_batch_not_found",
        )

    private fun unknownCredential(): HubScanResolution =
        HubScanResolution(
            workflowType = HubWorkflowType.UNKNOWN,
            displayReference = null,
            parcelId = null,
            returnId = null,
            collectionBatchId = null,
            lockerId = null,
            identityVerificationRequired = false,
            feeDueCfa = 0,
            blockingReason = "credential_not_found",
        )

    private fun validateWeeklyHours(hours: WeeklyOpeningHourCommand) {
        validateHours(hours.isOpen, hours.opensAt, hours.closesAt)
    }

    private fun validateException(exception: OpeningHourExceptionCommand) {
        validateHours(!exception.isClosed, exception.opensAt, exception.closesAt)
        require(exception.reason == null || exception.reason.length <= 255) { "exception reason cannot exceed 255 characters." }
    }

    private fun validateHours(open: Boolean, opensAt: LocalTime?, closesAt: LocalTime?) {
        if (open) {
            require(opensAt != null && closesAt != null && opensAt.isBefore(closesAt)) {
                "Open hours require opensAt before closesAt."
            }
        } else {
            require(opensAt == null && closesAt == null) {
                "Closed hours cannot include opensAt or closesAt."
            }
        }
    }

    private fun HubLockerStatus.defaultReason(): HubLockerAvailabilityReason? =
        when (this) {
            HubLockerStatus.AVAILABLE -> HubLockerAvailabilityReason.OPERATOR_CONFIRMED_AVAILABLE
            HubLockerStatus.OCCUPIED -> HubLockerAvailabilityReason.SYSTEM_OCCUPIED
            HubLockerStatus.MAINTENANCE -> null
        }

    private fun defaultUserPreferences(
        userId: String,
        appFamily: NotificationAppFamily,
    ): UserAppPreferenceSnapshot =
        UserAppPreferenceSnapshot(
            id = null,
            userId = userId,
            appFamily = appFamily,
            theme = UserPreferenceTheme.SYSTEM,
            language = "fr",
            quickScanOnOpen = false,
            soundFeedback = true,
            largeLockerLabels = false,
            updatedAt = null,
        )

    private fun deriveEffectiveMode(
        hubState: HubControlTargetStateSnapshot,
        serviceStates: List<HubControlTargetStateSnapshot>,
    ): HubEffectiveMode {
        if (hubState.status == HubControlStatus.DISABLED) return HubEffectiveMode.HUB_DISABLED
        if (hubState.status == HubControlStatus.PAUSED) return HubEffectiveMode.HUB_PAUSED

        val serviceByTarget = serviceStates.associateBy { it.target }
        val intakeStatus = serviceByTarget[HubControlTarget.LOCKER_INTAKE]?.status
        val pickupStatus = serviceByTarget[HubControlTarget.CUSTOMER_PICKUP]?.status
        if (
            intakeStatus in setOf(HubControlStatus.PAUSED, HubControlStatus.DISABLED) &&
            pickupStatus == HubControlStatus.ACTIVE
        ) {
            return HubEffectiveMode.INTAKE_PAUSED_PICKUP_ALLOWED
        }
        return if (serviceStates.any { it.status != HubControlStatus.ACTIVE }) {
            HubEffectiveMode.SERVICE_RESTRICTED
        } else {
            HubEffectiveMode.NORMAL
        }
    }

    private companion object {
        const val REQUIRED_DELETION_CONFIRMATION = "DELETE_MY_ACCOUNT"
        const val CONTROL_DECISION_LOOKBACK = 500
        const val DEFAULT_CONTROL_HISTORY_LIMIT = 50
        const val MAX_CONTROL_HISTORY_LIMIT = 100
        val PENDING_SEQUO_COLLECTION_STATUSES = setOf(
            RelayParcelStatus.Deposited,
            RelayParcelStatus.Delayed,
            RelayParcelStatus.ReturnToSellerReview,
        )
        val ACTIVE_DELETION_STATUSES = setOf(
            AccountDeletionRequestStatus.REQUESTED,
            AccountDeletionRequestStatus.UNDER_REVIEW,
            AccountDeletionRequestStatus.APPROVED,
        )
        val SERVICE_CONTROL_TARGETS = listOf(
            HubControlTarget.LOCKER_INTAKE,
            HubControlTarget.CUSTOMER_PICKUP,
            HubControlTarget.CUSTOMER_RETURNS,
            HubControlTarget.SEQUO_COLLECTION,
            HubControlTarget.PLAN_B_DROP_OFF,
        )
    }
}

private fun RelayLockerRecord.toSnapshot(): LockerAvailabilitySnapshot =
    LockerAvailabilitySnapshot(
        lockerId = id,
        relayPointId = relayPointId,
        lockerCode = lockerCode,
        status = status,
        reason = availabilityReason,
        expectedAvailableAt = expectedAvailableAt,
        updatedAt = updatedAt,
    )

private fun HubOpeningHourRecord.toSnapshot(): WeeklyOpeningHourSnapshot =
    WeeklyOpeningHourSnapshot(
        dayOfWeek = dayOfWeek,
        isOpen = isOpen,
        opensAt = opensAt,
        closesAt = closesAt,
    )

private fun HubOpeningHourExceptionRecord.toSnapshot(): OpeningHourExceptionSnapshot =
    OpeningHourExceptionSnapshot(
        date = exceptionDate,
        isClosed = isClosed,
        opensAt = opensAt,
        closesAt = closesAt,
        reason = reason,
        effectiveUntil = effectiveUntil,
    )

private fun HubControlTarget.defaultActiveState(): HubControlTargetStateSnapshot =
    HubControlTargetStateSnapshot(
        target = this,
        status = HubControlStatus.ACTIVE,
        reasonCode = null,
        staffMessage = null,
        customerMessage = null,
        effectiveUntil = null,
        source = null,
        actorType = null,
        decidedAt = null,
    )

private fun HubControlDecisionRecord.isExpired(now: Instant): Boolean =
    effectiveUntil?.let { !it.isAfter(now) } ?: false

private fun HubControlDecisionRecord.toTargetState(): HubControlTargetStateSnapshot =
    HubControlTargetStateSnapshot(
        target = target,
        status = status,
        reasonCode = reasonCode,
        staffMessage = staffMessage,
        customerMessage = customerMessage,
        effectiveUntil = effectiveUntil,
        source = source,
        actorType = actorType,
        decidedAt = createdAt,
    )

private fun HubControlDecisionRecord.toSnapshot(): HubControlDecisionSnapshot =
    HubControlDecisionSnapshot(
        id = requireNotNull(id) { "Persisted hub control decision id is required." },
        relayPointId = relayPointId,
        target = target,
        status = status,
        reasonCode = reasonCode,
        staffMessage = staffMessage,
        customerMessage = customerMessage,
        effectiveUntil = effectiveUntil,
        actorType = actorType,
        actorId = actorId,
        source = source,
        incidentReferenceId = incidentReferenceId,
        idempotencyKey = idempotencyKey,
        createdAt = createdAt,
    )

private fun HubControlDecisionRecord.matches(command: HubControlDecisionCommand): Boolean =
    relayPointId == command.relayPointId.trim() &&
        target == command.target &&
        status == command.status &&
        reasonCode == command.reasonCode &&
        staffMessage == command.staffMessage.trim() &&
        customerMessage == command.customerMessage?.trim() &&
        effectiveUntil == command.effectiveUntil &&
        actorType == command.actorType &&
        actorId == command.actorId.trim() &&
        source == command.source.trim() &&
        incidentReferenceId == command.incidentReferenceId?.trim()

private fun AccountDeletionRequestRecord.toSnapshot(): AccountDeletionRequestSnapshot =
    AccountDeletionRequestSnapshot(
        id = requireNotNull(id) { "Persisted account deletion request id is required." },
        userId = userId,
        status = status,
        requestedAt = requestedAt,
    )

private fun UserAppPreferenceRecord.toSnapshot(): UserAppPreferenceSnapshot =
    UserAppPreferenceSnapshot(
        id = requireNotNull(id) { "Persisted user preference id is required." },
        userId = userId,
        appFamily = appFamily,
        theme = theme,
        language = language,
        quickScanOnOpen = quickScanOnOpen,
        soundFeedback = soundFeedback,
        largeLockerLabels = largeLockerLabels,
        updatedAt = updatedAt,
    )
