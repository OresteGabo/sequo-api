package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.admin.AdminPartyChangeDto
import dev.orestegabo.sequo_api.domain.admin.AdminPartyChangeRecord
import dev.orestegabo.sequo_api.domain.admin.AdminPartyChangeRepository
import dev.orestegabo.sequo_api.domain.admin.AdminPartyConflict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class DeliveryAdminReasonRequest(val reason: String? = null, val idempotencyKey: String? = null)

@Service
class DeliveryAdminAudit(private val changes: AdminPartyChangeRepository) {
    data class Intent(val domain: String, val target: String, val action: String, val actor: String,
        val reason: String, val key: String, val hash: String)

    fun intent(domain: String, target: String, action: String, actor: String, desired: String?, reason: String?, key: String?): Intent {
        val safeReason = ApiInputPolicy.optionalShortText(reason, "reason", 1000).orEmpty()
        val retry = ApiInputPolicy.optionalIdempotencyKey(key) ?: UUID.randomUUID().toString()
        val value = listOf(domain, target, action, actor, desired.orEmpty(), safeReason).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        return Intent(domain, target, action, actor, safeReason, retry, hash)
    }

    fun replay(intent: Intent): Boolean = changes.findByIdempotencyKey(intent.key)?.let {
        if (it.actorUserId != intent.actor || it.intentHash != intent.hash) {
            throw AdminPartyConflict("delivery_admin_retry_conflict", "This retry key belongs to another actor, target, action or intent.")
        }
        true
    } ?: false

    fun record(intent: Intent, before: String?, after: String?) {
        changes.save(AdminPartyChangeRecord(UUID.randomUUID().toString(), intent.domain, intent.target, intent.action,
            intent.actor, before, after, intent.reason, intent.key, intent.hash, Instant.now().truncatedTo(ChronoUnit.MICROS)))
    }

    @Transactional(readOnly = true)
    fun history(domain: String, id: String): List<AdminPartyChangeDto> = changes.findTop50ByDomainAndEntityIdOrderByCreatedAtDesc(domain, id)
        .map { AdminPartyChangeDto(it.id, it.action, it.actorUserId, it.beforeValue, it.afterValue, it.reason, it.idempotencyKey, it.createdAt) }
}
