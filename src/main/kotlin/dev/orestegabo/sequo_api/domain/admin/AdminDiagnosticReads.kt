package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.hub.AccountDeletionRequestRepository
import dev.orestegabo.sequo_api.domain.hub.AccountDeletionRequestStatus
import dev.orestegabo.sequo_api.domain.hub.AccountDeletionRequestRecord
import dev.orestegabo.sequo_api.domain.notification.NotificationOutbox
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxRepository
import dev.orestegabo.sequo_api.domain.notification.NotificationOutboxStatus
import dev.orestegabo.sequo_api.domain.notification.NotificationEventType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class AdminNotificationDiagnosticDto(
    val id: String, val eventId: String, val eventType: NotificationEventType, val aggregateType: String,
    val aggregateId: String, val status: NotificationOutboxStatus, val attemptCount: Int,
    val nextAttemptAt: Instant?, val createdAt: Instant, val updatedAt: Instant, val processedAt: Instant?,
)
data class AdminDeletionRequestDto(val id: String, val userId: String, val status: AccountDeletionRequestStatus,
    val requestedAt: Instant, val reviewedAt: Instant?)

/** Operational metadata only: no payload, provider/device token, private reason or erasure action. */
@RestController
@RequestMapping("/api/admin/web")
class AdminDiagnosticReadController(
    private val outbox: NotificationOutboxRepository,
    private val deletionRequests: AccountDeletionRequestRepository,
) {
    @GetMapping("/notification-outbox")
    fun notifications(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminControlRead(authentication) {
            ok(outbox.findAll(page(limit, "updatedAt")).content.map { it.dto() })
        }

    @GetMapping("/notification-outbox/{id}")
    fun notification(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Any> =
        adminControlRead(authentication) { found(outbox.findById(identifier(id)).orElse(null)?.dto()) }

    @GetMapping("/account-deletion-requests")
    fun deletions(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> =
        adminControlRead(authentication) {
            ok(deletionRequests.findAll(page(limit, "requestedAt")).content.map { it.dto() })
        }

    @GetMapping("/account-deletion-requests/{id}")
    fun deletion(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Any> =
        adminControlRead(authentication) { found(deletionRequests.findById(identifier(id)).orElse(null)?.dto()) }

    private fun page(limit: Int, property: String) = PageRequest.of(0, limit.coerceIn(1, 200), Sort.by(Sort.Direction.DESC, property, "id"))
    private fun identifier(value: String) = ApiInputPolicy.requiredIdentifier(value, "id")
    private fun ok(value: Any): ResponseEntity<Any> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
    private fun found(value: Any?): ResponseEntity<Any> = value?.let(::ok) ?: ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
}

private fun NotificationOutbox.dto() = AdminNotificationDiagnosticDto(requireNotNull(id), eventId, eventType, aggregateType,
    aggregateId, status, attemptCount, nextAttemptAt, createdAt, updatedAt, processedAt)
private fun AccountDeletionRequestRecord.dto() = AdminDeletionRequestDto(requireNotNull(id), userId, status, requestedAt, reviewedAt)
