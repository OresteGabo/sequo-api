package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.domain.auth.RoleGroups
import dev.orestegabo.sequo_api.domain.auth.hasAnyRole
import jakarta.persistence.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant
import java.util.UUID

/** Append-only request evidence. Business before/after values remain in domain audit tables. */
@Entity
@Table(name = "activity_journal")
class ActivityJournalRecord(
    @Id val id: String = UUID.randomUUID().toString(),
    @Column(name = "operation_id", nullable = false) val operationId: String,
    @Column(nullable = false, length = 24) val phase: String,
    @Column(name = "actor_user_id") val actorUserId: String?,
    @Column(name = "actor_roles", nullable = false, length = 500) val actorRoles: String = "",
    @Column(nullable = false, length = 250) val action: String,
    @Column(name = "target_reference", nullable = false, length = 1000) val targetReference: String = "",
    @Column(name = "http_status") val httpStatus: Int? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

interface ActivityJournalRepository : JpaRepository<ActivityJournalRecord, String> {
    @Query("""select a from ActivityJournalRecord a where
        (:actor is null or a.actorUserId = :actor) and
        (:target is null or a.targetReference = :target) and
        (:operation is null or a.operationId = :operation) and
        (:since is null or a.createdAt >= :since) and
        (:until is null or a.createdAt < :until)
        order by a.createdAt desc, a.id desc""")
    fun history(actor: String?, target: String?, operation: String?, since: Instant?, until: Instant?, page: org.springframework.data.domain.Pageable): List<ActivityJournalRecord>
}

@Service
class ActivityJournalWriter(private val records: ActivityJournalRepository) {
    // Independent of failed business transactions: a durable STARTED always precedes execution.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun append(record: ActivityJournalRecord) { records.saveAndFlush(record) }
}

@Configuration
class ActivityJournalConfiguration(private val writer: ActivityJournalWriter) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                if (request.method !in setOf("POST", "PUT", "PATCH", "DELETE")) return true
                val authentication = SecurityContextHolder.getContext().authentication ?: return true
                if (!authentication.isAuthenticated || authentication.name == "anonymousUser") return true
                val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: return true
                @Suppress("UNCHECKED_CAST")
                val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String> ?: emptyMap()
                // Only opaque resource identifiers. Never capture bodies, queries, headers, codes or tokens.
                val target = variables.entries.filter { it.key.endsWith("Id") && it.key !in setOf("sessionId", "requestId", "credentialId", "tokenId") }
                    .sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value.take(128)}" }.take(1000)
                val start = ActivityJournalRecord(operationId = UUID.randomUUID().toString(), phase = "STARTED",
                    actorUserId = authentication.name, actorRoles = authentication.authorities.mapNotNull { it.authority }.sorted().joinToString(",").take(500),
                    action = "${request.method} $route".take(250), targetReference = target)
                writer.append(start) // Fail closed: do not run a mutation without its initial audit record.
                request.setAttribute("sequo.activity.start", start)
                return true
            }

            override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
                val start = request.getAttribute("sequo.activity.start") as? ActivityJournalRecord ?: return
                writer.append(ActivityJournalRecord(operationId = start.operationId,
                    phase = if (ex == null && response.status in 200..299) "HTTP_SUCCEEDED" else "HTTP_FAILED",
                    actorUserId = start.actorUserId, actorRoles = start.actorRoles, action = start.action,
                    targetReference = start.targetReference, httpStatus = if (ex != null) 500 else response.status))
            }
        }).addPathPatterns("/api/**")
    }
}

@RestController
@RequestMapping("/api/admin/web/activity")
class ActivityJournalController(private val records: ActivityJournalRepository) {
    @GetMapping
    fun history(authentication: Authentication?, @RequestParam(required = false) actorUserId: String?,
                @RequestParam(required = false) targetReference: String?, @RequestParam(required = false) operationId: String?,
                @RequestParam(required = false) since: Instant?, @RequestParam(required = false) until: Instant?,
                @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") limit: Int): ResponseEntity<Any> {
        if (authentication == null) return ResponseEntity.status(401).build()
        if (!authentication.hasAnyRole(RoleGroups.AdminOnly)) return ResponseEntity.status(403).build()
        if (page !in 0..100000 || limit !in 1..100 || (since != null && until != null && since >= until)) return ResponseEntity.badRequest().build()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(records.history(actorUserId, targetReference, operationId, since, until, PageRequest.of(page, limit)))
    }
}
