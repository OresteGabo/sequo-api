package dev.orestegabo.sequo_api.domain.hub

import dev.orestegabo.sequo_api.domain.notification.NotificationAppFamily
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/account")
class AccountController(
    private val service: HubMobileService,
) {
    data class DeletionRequest(
        val reason: String? = null,
        val confirmation: String,
        val idempotencyKey: String? = null,
    )

    data class ErrorResponse(val code: String, val message: String)

    @PostMapping("/deletion-requests")
    fun requestDeletion(
        @AuthenticationPrincipal userId: String?,
        @RequestBody request: DeletionRequest,
    ): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        return try {
            ResponseEntity.ok(
                service.requestAccountDeletion(
                    AccountDeletionRequestCommand(
                        userId = userId,
                        confirmation = request.confirmation,
                        reason = request.reason,
                        idempotencyKey = request.idempotencyKey,
                    )
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ErrorResponse("invalid_account_deletion_request", e.message ?: "Invalid account deletion request.")
            )
        }
    }
}

@RestController
@RequestMapping("/api/preferences")
class UserPreferenceController(
    private val service: HubMobileService,
) {
    data class PatchPreferenceRequest(
        val theme: UserPreferenceTheme? = null,
        val language: String? = null,
        val quickScanOnOpen: Boolean? = null,
        val soundFeedback: Boolean? = null,
        val largeLockerLabels: Boolean? = null,
    )

    data class ErrorResponse(val code: String, val message: String)

    @GetMapping
    fun getPreferences(
        @AuthenticationPrincipal userId: String?,
        @RequestParam(required = false) appFamily: NotificationAppFamily?,
    ): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(
            service.getUserPreferences(userId, appFamily ?: NotificationAppFamily.SEQUO_HUB)
        )
    }

    @PatchMapping
    fun patchPreferences(
        @AuthenticationPrincipal userId: String?,
        @RequestParam(required = false) appFamily: NotificationAppFamily?,
        @RequestBody request: PatchPreferenceRequest,
    ): ResponseEntity<Any> {
        userId ?: return ResponseEntity.status(401).build()
        return try {
            ResponseEntity.ok(
                service.patchUserPreferences(
                    UserAppPreferencePatch(
                        userId = userId,
                        appFamily = appFamily ?: NotificationAppFamily.SEQUO_HUB,
                        theme = request.theme,
                        language = request.language,
                        quickScanOnOpen = request.quickScanOnOpen,
                        soundFeedback = request.soundFeedback,
                        largeLockerLabels = request.largeLockerLabels,
                    )
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ErrorResponse("invalid_user_preference_request", e.message ?: "Invalid user preference request.")
            )
        }
    }
}
