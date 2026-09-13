package dev.orestegabo.sequo_api.domain.hub

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.notification.NotificationAppFamily
import dev.orestegabo.sequo_api.domain.relay.RelayLocker
import dev.orestegabo.sequo_api.domain.relay.RelayParcelApplicationService
import dev.orestegabo.sequo_api.domain.relay.RelayParcelCategory
import dev.orestegabo.sequo_api.domain.relay.RelayParcelCreateCommand
import dev.orestegabo.sequo_api.domain.relay.RelayParcelServiceResult
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class HubMobileServiceTest @Autowired constructor(
    private val hubService: HubMobileService,
    private val relayService: RelayParcelApplicationService,
    private val pickupCodeRepository: dev.orestegabo.sequo_api.domain.relay.RelayPickupCodeRecordRepository,
    private val userRepository: UserRepository,
) {
    private val now = Instant.parse("2026-09-12T10:00:00Z")

    @Test
    fun resolvesPickupCodeWithoutReturningRawSecretOrPrivateCustomerData() {
        val parcel = createRelayParcel("hub-scan-parcel-1", "hub-scan-deposit-1")
        relayService.createPickupCode(
            parcelId = parcel.id,
            codeId = "hub-scan-code-1",
            rawNumericCode = "123456",
            rawQrNonce = "hub-scan-qr-nonce",
            identityCheckRequired = true,
            expiresAt = now.plusSeconds(3600),
            createdAt = now,
        )

        val resolved = hubService.resolveScan(
            HubScanResolveCommand(
                hubId = "hub-1",
                credential = "123456",
                credentialType = HubScanCredentialType.PICKUP_CODE,
            ),
            now = now,
        )

        assertEquals(HubWorkflowType.CUSTOMER_PICKUP, resolved.workflowType)
        assertEquals(parcel.id, resolved.parcelId)
        assertEquals("locker-1", resolved.lockerId)
        assertEquals(true, resolved.identityVerificationRequired)
        assertNull(resolved.blockingReason)
        assertTrue(resolved.toString().contains("123456").not())
    }

    @Test
    fun scanResolutionDoesNotRevealCredentialsFromAnotherHub() {
        val parcel = createRelayParcel(
            parcelId = "hub-cross-scope-parcel",
            depositCode = "hub-cross-scope-deposit",
            relayPointId = "hub-other",
            lockerId = "locker-other",
        )
        relayService.createPickupCode(
            parcelId = parcel.id,
            codeId = "hub-cross-scope-code",
            rawNumericCode = "234567",
            rawQrNonce = null,
            identityCheckRequired = true,
            expiresAt = now.plusSeconds(3600),
            createdAt = now,
        )

        val resolved = hubService.resolveScan(
            HubScanResolveCommand("hub-1", "234567", HubScanCredentialType.PICKUP_CODE),
            now = now,
        )

        assertEquals(HubWorkflowType.UNKNOWN, resolved.workflowType)
        assertEquals("credential_not_found", resolved.blockingReason)
        assertNull(resolved.parcelId)
    }

    @Test
    fun hubSummaryUsesLockerAndRelayStateCounts() {
        hubService.updateLockerAvailability(
            LockerAvailabilityCommand(
                lockerId = "locker-summary-free",
                relayPointId = "hub-summary",
                status = HubLockerStatus.AVAILABLE,
                reason = HubLockerAvailabilityReason.OPERATOR_CONFIRMED_AVAILABLE,
                updatedByUserId = "hub-summary",
            ),
            now,
        )
        hubService.updateLockerAvailability(
            LockerAvailabilityCommand(
                lockerId = "locker-summary-occupied",
                relayPointId = "hub-summary",
                status = HubLockerStatus.OCCUPIED,
                reason = HubLockerAvailabilityReason.SYSTEM_OCCUPIED,
                updatedByUserId = "hub-summary",
            ),
            now,
        )
        createRelayParcel("hub-summary-parcel", "hub-summary-deposit", relayPointId = "hub-summary")

        val summary = hubService.summary("hub-summary")

        assertEquals(1, summary.freeLockerCount)
        assertEquals(1, summary.occupiedLockerCount)
        assertEquals(1, summary.pendingSequoCollectionCount)
        assertNotNull(summary.lastSuccessfulSyncTimestamp)
    }

    @Test
    fun savesOpeningHoursAndExceptions() {
        val saved = hubService.saveOpeningHours(
            SaveHubOpeningHoursCommand(
                relayPointId = "hub-hours",
                timezone = "Africa/Lome",
                weeklyHours = listOf(
                    WeeklyOpeningHourCommand(DayOfWeek.MONDAY, isOpen = true, opensAt = LocalTime.of(8, 0), closesAt = LocalTime.of(18, 0)),
                    WeeklyOpeningHourCommand(DayOfWeek.SUNDAY, isOpen = false),
                ),
                exceptions = listOf(
                    OpeningHourExceptionCommand(
                        date = LocalDate.parse("2026-09-20"),
                        isClosed = true,
                        reason = "Inventory day",
                    )
                ),
            ),
            now,
        )

        assertEquals("Africa/Lome", saved.timezone)
        assertEquals(2, saved.weeklyHours.size)
        assertEquals(1, saved.exceptions.size)
    }

    @Test
    fun controlStateDefaultsToActiveForHubAndServices() {
        val state = hubService.getControlState("hub-control-default", now)

        assertEquals(HubEffectiveMode.NORMAL, state.effectiveMode)
        assertEquals(HubControlStatus.ACTIVE, state.hub.status)
        assertEquals(5, state.services.size)
        assertTrue(state.services.all { it.status == HubControlStatus.ACTIVE })
    }

    @Test
    fun appliesControlDecisionWithIdempotencyAndAuditHistory() {
        val command = HubControlDecisionCommand(
            relayPointId = "hub-control-intake",
            target = HubControlTarget.LOCKER_INTAKE,
            status = HubControlStatus.PAUSED,
            reasonCode = HubControlReasonCode.CAPACITY_LOCK,
            staffMessage = "Locker intake is paused while capacity is reviewed.",
            customerMessage = "Package drop-off is temporarily unavailable at this hub.",
            effectiveUntil = now.plusSeconds(3600),
            actorType = HubControlActorType.SEQUO_OPERATOR,
            actorId = "ops-user-1",
            source = "ops-console",
            incidentReferenceId = "incident-123",
            idempotencyKey = "hub-control-intake-1",
        )

        val first = hubService.applyControlDecision(command, now)
        val replay = hubService.applyControlDecision(command, now.plusSeconds(30))
        val state = hubService.getControlState("hub-control-intake", now.plusSeconds(60))
        val history = hubService.controlHistory(
            relayPointId = "hub-control-intake",
            target = HubControlTarget.LOCKER_INTAKE,
        )

        assertEquals(first.id, replay.id)
        assertEquals(HubEffectiveMode.INTAKE_PAUSED_PICKUP_ALLOWED, state.effectiveMode)
        assertEquals(
            HubControlStatus.PAUSED,
            state.services.first { it.target == HubControlTarget.LOCKER_INTAKE }.status,
        )
        assertEquals(HubControlStatus.ACTIVE, state.services.first { it.target == HubControlTarget.CUSTOMER_PICKUP }.status)
        assertEquals(1, history.decisions.size)
        assertEquals("incident-123", history.decisions.single().incidentReferenceId)

        assertFailsWith<IllegalArgumentException> {
            hubService.applyControlDecision(
                command.copy(status = HubControlStatus.DISABLED),
                now.plusSeconds(90),
            )
        }
    }

    @Test
    fun expiredControlDecisionFallsBackToActiveState() {
        hubService.applyControlDecision(
            HubControlDecisionCommand(
                relayPointId = "hub-control-expired",
                target = HubControlTarget.HUB,
                status = HubControlStatus.DISABLED,
                reasonCode = HubControlReasonCode.MAINTENANCE,
                staffMessage = "Hub disabled during emergency maintenance.",
                effectiveUntil = now.plusSeconds(60),
                actorType = HubControlActorType.SEQUO_OPERATOR,
                actorId = "ops-user-expiry",
                source = "ops-console",
                idempotencyKey = "hub-control-expired-1",
            ),
            now,
        )

        val activeAgain = hubService.getControlState("hub-control-expired", now.plusSeconds(61))

        assertEquals(HubEffectiveMode.NORMAL, activeAgain.effectiveMode)
        assertEquals(HubControlStatus.ACTIVE, activeAgain.hub.status)
    }

    @Test
    fun accountDeletionRequiresExplicitConfirmationAndIsIdempotentWhileActive() {
        val userId = createUser("hub-delete@sequo.test")

        val first = hubService.requestAccountDeletion(
            AccountDeletionRequestCommand(
                userId = userId,
                confirmation = "DELETE_MY_ACCOUNT",
                reason = "No longer using the hub app.",
            ),
            now,
        )
        val second = hubService.requestAccountDeletion(
            AccountDeletionRequestCommand(
                userId = userId,
                confirmation = "DELETE_MY_ACCOUNT",
                reason = "Second tap.",
            ),
            now.plusSeconds(60),
        )

        assertEquals(first.id, second.id)
        assertEquals(AccountDeletionRequestStatus.REQUESTED, first.status)
    }

    @Test
    fun patchesUserPreferencesForHubAppFamily() {
        val userId = createUser("hub-preferences@sequo.test")

        val patched = hubService.patchUserPreferences(
            UserAppPreferencePatch(
                userId = userId,
                appFamily = NotificationAppFamily.SEQUO_HUB,
                theme = UserPreferenceTheme.DARK,
                language = "fr-TG",
                quickScanOnOpen = true,
                soundFeedback = false,
                largeLockerLabels = true,
            ),
            now,
        )

        assertEquals(UserPreferenceTheme.DARK, patched.theme)
        assertEquals("fr-TG", patched.language)
        assertEquals(true, patched.quickScanOnOpen)
        assertEquals(false, patched.soundFeedback)
        assertEquals(true, patched.largeLockerLabels)
    }

    private fun createRelayParcel(
        parcelId: String,
        depositCode: String,
        relayPointId: String = "hub-1",
        lockerId: String = "locker-1",
    ) = (relayService.createParcel(
        RelayParcelCreateCommand(
            parcelId = parcelId,
            relayPointId = relayPointId,
            orderId = "order-$parcelId",
            category = RelayParcelCategory.GeneralGoods,
            depositCode = depositCode,
            availableLockers = listOf(RelayLocker(lockerId, relayPointId, active = true, occupied = false)),
            createdAt = now,
        )
    ) as RelayParcelServiceResult.Accepted).value.parcel

    private fun createUser(email: String): String =
        requireNotNull(
            userRepository.save(
                User(
                    email = email,
                    passwordHash = "hash",
                    name = "Hub User",
                    provider = AuthProvider.EMAIL,
                )
            ).id
        )
}
