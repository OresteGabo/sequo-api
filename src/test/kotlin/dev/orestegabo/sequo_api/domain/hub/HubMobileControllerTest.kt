package dev.orestegabo.sequo_api.domain.hub

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import dev.orestegabo.sequo_api.domain.notification.NotificationAppFamily
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class HubMobileControllerTest @Autowired constructor(
    private val hubController: HubMobileController,
    private val accountController: AccountController,
    private val preferenceController: UserPreferenceController,
    private val userRepository: UserRepository,
) {
    @Test
    fun hubSummaryRequiresAuthenticationAndRelayScope() {
        val unauthenticated = hubController.summary(null, "hub-controller")
        val otherHub = hubController.summary(auth("other-hub", RoleCode.RELAY_PARTNER), "hub-controller")
        val ownHub = hubController.summary(auth("hub-controller", RoleCode.RELAY_PARTNER), "hub-controller")
        val admin = hubController.summary(auth("admin-hub", RoleCode.ADMIN), "hub-controller")

        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, otherHub.statusCode)
        assertEquals(HttpStatus.OK, ownHub.statusCode)
        assertEquals(HttpStatus.OK, admin.statusCode)
    }

    @Test
    fun relayPartnerCanUpdateOwnLockerAvailability() {
        val response = hubController.updateLockerAvailability(
            authentication = auth("hub-locker", RoleCode.RELAY_PARTNER),
            lockerId = "locker-controller-1",
            request = HubMobileController.LockerAvailabilityRequest(
                relayPointId = "hub-locker",
                status = HubLockerStatus.MAINTENANCE,
                reason = HubLockerAvailabilityReason.JAMMED_LOCK,
                expectedAvailableAt = Instant.parse("2026-09-13T10:00:00Z"),
            ),
        )

        val body = response.body as LockerAvailabilitySnapshot
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(HubLockerStatus.MAINTENANCE, body.status)
        assertEquals(HubLockerAvailabilityReason.JAMMED_LOCK, body.reason)
    }

    @Test
    fun relayPartnerCanReadButCannotWriteHubControlState() {
        val request = HubMobileController.ApplyControlStateRequest(
            relayPointId = "hub-control-controller",
            target = HubControlTarget.HUB,
            status = HubControlStatus.DISABLED,
            reasonCode = HubControlReasonCode.RISK_REVIEW,
            staffMessage = "Hub disabled while the risk review is active.",
            customerMessage = "This pickup point is temporarily unavailable.",
            actorType = HubControlActorType.SEQUO_OPERATOR,
            source = "ops-console",
            incidentReferenceId = "risk-456",
            idempotencyKey = "hub-control-controller-1",
        )

        val readOwn = hubController.controlState(
            auth("hub-control-controller", RoleCode.RELAY_PARTNER),
            "hub-control-controller",
        )
        val supportRead = hubController.controlState(
            auth("support-control-controller", RoleCode.SUPPORT_AGENT),
            "hub-control-controller",
        )
        val relayWrite = hubController.applyControlState(
            auth("hub-control-controller", RoleCode.RELAY_PARTNER),
            request,
        )
        val adminWrite = hubController.applyControlState(
            auth("admin-control-controller", RoleCode.ADMIN),
            request,
        )
        val readHistory = hubController.controlHistory(
            auth("hub-control-controller", RoleCode.RELAY_PARTNER),
            relayPointId = "hub-control-controller",
            target = null,
            limit = null,
        )

        assertEquals(HttpStatus.OK, readOwn.statusCode)
        assertEquals(HttpStatus.OK, supportRead.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, relayWrite.statusCode)
        assertEquals(HttpStatus.OK, adminWrite.statusCode)
        val decision = adminWrite.body as HubControlDecisionSnapshot
        assertEquals("admin-control-controller", decision.actorId)
        assertEquals("risk-456", decision.incidentReferenceId)
        val history = readHistory.body as HubControlHistorySnapshot
        assertEquals(1, history.decisions.size)
    }

    @Test
    fun supportCannotAssertAiControlActorType() {
        val request = HubMobileController.ApplyControlStateRequest(
            relayPointId = "hub-control-ai-controller",
            target = HubControlTarget.LOCKER_INTAKE,
            status = HubControlStatus.PAUSED,
            reasonCode = HubControlReasonCode.FRAUD_SIGNAL,
            staffMessage = "Locker intake paused by automated review.",
            actorType = HubControlActorType.SEQUO_AI,
            source = "risk-automation",
            idempotencyKey = "hub-control-ai-controller-1",
        )

        val supportWrite = hubController.applyControlState(
            auth("support-control-controller", RoleCode.SUPPORT_AGENT),
            request,
        )
        val adminWrite = hubController.applyControlState(
            auth("admin-ai-control-controller", RoleCode.ADMIN),
            request.copy(idempotencyKey = "hub-control-ai-controller-2"),
        )

        assertEquals(HttpStatus.FORBIDDEN, supportWrite.statusCode)
        assertEquals(HttpStatus.OK, adminWrite.statusCode)
    }

    @Test
    fun accountDeletionUsesAuthenticatedUserId() {
        val userId = createUser("controller-delete@sequo.test")

        val response = accountController.requestDeletion(
            userId = userId,
            request = AccountController.DeletionRequest(
                reason = "Please delete my account.",
                confirmation = "DELETE_MY_ACCOUNT",
            ),
        )

        val body = response.body as AccountDeletionRequestSnapshot
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(userId, body.userId)
        assertEquals(AccountDeletionRequestStatus.REQUESTED, body.status)
    }

    @Test
    fun userPreferencesDefaultToHubAppFamily() {
        val userId = createUser("controller-preferences@sequo.test")

        val patched = preferenceController.patchPreferences(
            userId = userId,
            appFamily = null,
            request = UserPreferenceController.PatchPreferenceRequest(
                theme = UserPreferenceTheme.DARK,
                quickScanOnOpen = true,
            ),
        )
        val read = preferenceController.getPreferences(userId, appFamily = null)

        assertEquals(HttpStatus.OK, patched.statusCode)
        val body = read.body as UserAppPreferenceSnapshot
        assertEquals(NotificationAppFamily.SEQUO_HUB, body.appFamily)
        assertEquals(UserPreferenceTheme.DARK, body.theme)
        assertEquals(true, body.quickScanOnOpen)
    }

    private fun auth(userId: String, role: RoleCode): Authentication =
        UsernamePasswordAuthenticationToken(userId, null, listOf(role.toGrantedAuthority()))

    private fun createUser(email: String): String =
        requireNotNull(
            userRepository.save(
                User(
                    email = email,
                    passwordHash = "hash",
                    name = "Hub Controller User",
                    provider = AuthProvider.EMAIL,
                )
            ).id
        )
}
