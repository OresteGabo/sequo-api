package dev.orestegabo.sequo_api.domain.notification

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/notifications/inbox")
class NotificationController(
    private val readService: NotificationReadService,
) {
    @GetMapping
    fun listInbox(
        @AuthenticationPrincipal userId: String?,
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()
        return try {
            ResponseEntity.ok(readService.listInbox(NotificationInboxQuery(userId, includeArchived, limit)))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ErrorResponse("invalid_inbox_query", e.message ?: "Invalid inbox query."))
        }
    }

    @PatchMapping("/{messageId}/read")
    fun markRead(
        @AuthenticationPrincipal userId: String?,
        @PathVariable messageId: String,
    ): ResponseEntity<Any> = action(userId) { readService.markRead(it, messageId) }

    @PostMapping("/{messageId}/archive")
    fun archive(
        @AuthenticationPrincipal userId: String?,
        @PathVariable messageId: String,
    ): ResponseEntity<Any> = action(userId) { readService.archive(it, messageId) }

    @DeleteMapping("/{messageId}/archive")
    fun unarchive(
        @AuthenticationPrincipal userId: String?,
        @PathVariable messageId: String,
    ): ResponseEntity<Any> = action(userId) { readService.unarchive(it, messageId) }

    private fun action(
        userId: String?,
        operation: (String) -> NotificationMessageSnapshot?,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(401).build()
        return try {
            operation(userId)?.let { ResponseEntity.ok(it) }
                ?: ResponseEntity.notFound().build()
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ErrorResponse("invalid_notification_request", e.message ?: "Invalid notification request."))
        }
    }

    data class ErrorResponse(val code: String, val message: String)
}
