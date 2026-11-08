package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.localfixture.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant

/** Independent opt-in. Ordinary authentication, deployments, and identities are unchanged. */
@Component
class DevelopmentMobileAccessPolicy(
    private val access: DevelopmentAdminAccessPolicy,
    private val fixtures: LocalOperationalFixturePolicy,
    environment: Environment,
) {
    val enabled = environment.getProperty("sequo.auth.local-mobile-access.enabled", Boolean::class.java, false)
    init {
        if (enabled) check(access.settings.enabled && fixtures.enabled &&
            DevelopmentAdminAccessChecks.findings(access.settings).isEmpty()) {
            "Local mobile access requires the independently guarded local fixture workspace."
        }
    }
    fun allows(request: HttpServletRequest): Boolean = enabled &&
        DevelopmentAdminAccessChecks.loopbackAddress(request.remoteAddr.orEmpty()) &&
        DevelopmentAdminAccessChecks.loopbackAddress(request.serverName.orEmpty()) &&
        (request.getHeader("Origin").isNullOrEmpty() || request.getHeader("Origin") in access.settings.origins) &&
        listOf("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP")
            .none { request.getHeader(it) != null }
}

private data class MobileFixtureAccount(val family: String, val key: String, val suffix: String, val appSource: AppSource,
    val merchantSuffix: String? = null, val courierSuffix: String? = null, val relaySuffix: String? = null)

@Service
class DevelopmentMobileSessionService(
    private val policy: DevelopmentMobileAccessPolicy,
    private val fixturePolicy: LocalOperationalFixturePolicy,
    private val runs: LocalOperationalFixtureRunRepository,
    private val foundation: LocalOperationalFixtureFoundation,
    private val users: UserRepository,
    private val auth: AuthService,
    private val jdbc: JdbcTemplate,
) {
    data class Account(val key: String, val label: String)
    data class Session(val accessToken: String, val refreshToken: String, val expiresIn: Long,
        val user: AuthController.CurrentUserResponse)

    @Transactional(readOnly = true)
    fun accounts(family: String?): List<Account> {
        if (!policy.enabled) return emptyList()
        return registered.filter { it.family == family }.mapNotNull { entry ->
            eligible(entry)?.let { Account(entry.key, it.displayName.orEmpty()) }
        }
    }

    @Transactional
    fun create(body: DevelopmentMobileAccessController.SessionRequest): Session? {
        if (!policy.enabled) return null
        val entry = registered.singleOrNull { it.family == body.appSource && it.key == body.accountKey } ?: return null
        val deviceId = ApiInputPolicy.requiredIdentifier(body.deviceId.orEmpty(), "deviceId")
        val label = ApiInputPolicy.optionalShortText(body.deviceLabel, "deviceLabel", 255)
        // User-access/membership writes take this same lock. Validate current authority before minting tokens.
        users.lockCheckoutOwner("local-ops-v1-${entry.suffix}") ?: return null
        val user = eligible(entry) ?: return null
        val tokens = auth.issueTokensForExistingUser(requireNotNull(user.id), user.provider,
            DeviceBinding(deviceId, entry.appSource, null, deviceLabel = label)) ?: return null
        val account = auth.accountSession(user)
        return Session(tokens.accessToken, tokens.refreshToken, tokens.expiresIn,
            AuthController.CurrentUserResponse(requireNotNull(user.id), user.phoneNumber, user.email, user.displayName,
                user.avatarUrl, user.provider, user.status, user.active, account.roles, account.merchantScopeIds))
    }

    private fun eligible(entry: MobileFixtureAccount): User? {
        val run = runs.findById("local-ops-v1").orElse(null) ?: return null
        if (run.seededAt == null) return null
        val manifest = runCatching { LocalFixtureManifestCodec.decode(run.manifestJson ?: return null) }.getOrNull() ?: return null
        if (manifest.seededAt == null) return null
        val context = LocalOperationalFixtureContext(fixturePolicy.adminId, Instant.EPOCH)
        val expected = foundation.accounts(context).single { it.id == context.id(entry.suffix) }
        if (manifest.accounts.singleOrNull { it.id == expected.id } != expected) return null
        val user = users.findById(expected.id).orElse(null) ?: return null
        if (!user.active || user.status != UserStatus.ACTIVE || user.provider != AuthProvider.EMAIL ||
            user.email != expected.email || user.displayName != expected.name || user.phoneNumber != null || user.avatarUrl != null ||
            user.roles.map { it.name }.toSet() != expected.roles.toSet()) return null
        val scopes = auth.accountSession(user).merchantScopeIds
        if (scopes != entry.merchantSuffix?.let { setOf(context.id(it)) }.orEmpty()) return null
        if (entry.merchantSuffix != null && jdbc.queryForObject(
                "select count(*) from merchants where id=? and owner_user_id=? and status='ACTIVE'", Long::class.java,
                context.id(entry.merchantSuffix), expected.id) != 1L) return null
        if (entry.courierSuffix != null && jdbc.queryForObject(
                "select count(*) from couriers where id=? and user_id=? and status='ACTIVE'", Long::class.java,
                context.id(entry.courierSuffix), expected.id) != 1L) return null
        if (entry.relaySuffix != null) {
            val sites = jdbc.queryForList("select id from relay_points where operator_user_id=? and status='ACTIVE'", String::class.java, expected.id)
            if (sites.toSet() != setOf(context.id(entry.relaySuffix))) return null
        }
        return user
    }

    private companion object {
        val registered = listOf(
            MobileFixtureAccount("SEQUO_APP", "ama", "customer-ama", AppSource.SEQUO_APP),
            MobileFixtureAccount("SEQUO_APP", "koffi", "customer-koffi", AppSource.SEQUO_APP),
            // Vendor's existing persisted app-source enum is HUB; role and merchant scope remain authoritative.
            MobileFixtureAccount("SEQUO_VENDOR", "yawa", "owner-yawa", AppSource.SEQUO_HUB, merchantSuffix = "merchant-maison"),
            MobileFixtureAccount("SEQUO_VENDOR", "mawuli", "owner-mawuli", AppSource.SEQUO_HUB, merchantSuffix = "merchant-atelier"),
            MobileFixtureAccount("SEQUO_RIDER", "senam", "rider-senam", AppSource.SEQUO_RIDER, courierSuffix = "courier-senam"),
            MobileFixtureAccount("SEQUO_RIDER", "komlan", "rider-komlan", AppSource.SEQUO_RIDER, courierSuffix = "courier-komlan"),
            MobileFixtureAccount("SEQUO_HUB", "dede", "operator-dede", AppSource.SEQUO_HUB, relaySuffix = "relay-tokoin"),
            MobileFixtureAccount("SEQUO_HUB", "esso", "operator-esso", AppSource.SEQUO_HUB, relaySuffix = "relay-adidogome"),
        )
    }
}

@RestController
@RequestMapping("/api/auth")
class DevelopmentMobileAccessController(
    private val policy: DevelopmentMobileAccessPolicy,
    private val sessions: DevelopmentMobileSessionService,
    private val limiter: AuthRateLimiter,
) {
    data class AccessResponse(val enabled: Boolean, val mode: String?, val accounts: List<DevelopmentMobileSessionService.Account>)
    data class SessionRequest(val appSource: String? = null, val accountKey: String? = null, val deviceId: String? = null,
        val deviceLabel: String? = null)

    @GetMapping("/development-access")
    fun access(request: HttpServletRequest, @RequestParam(required = false) appSource: String?): ResponseEntity<AccessResponse> {
        val accounts = if (policy.allows(request)) sessions.accounts(appSource) else emptyList()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(AccessResponse(accounts.isNotEmpty(), "LOCAL_FIXTURE".takeIf { accounts.isNotEmpty() }, accounts))
    }
    @PostMapping("/development-session")
    fun session(request: HttpServletRequest, @RequestBody body: SessionRequest): ResponseEntity<Any> {
        if (!policy.allows(request)) return unavailable()
        return try {
            limiter.checkAdminDevelopmentSession()
            val session = sessions.create(body) ?: return unavailable()
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session)
        } catch (e: RateLimitExceededException) {
            ResponseEntity.status(429).cacheControl(CacheControl.noStore()).header("Retry-After", e.retryAfterSeconds.toString())
                .body(mapOf("code" to "rate_limited", "message" to "Please wait before trying again."))
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(mapOf("code" to "invalid_development_session", "message" to "A valid local deviceId is required."))
        } catch (_: IllegalStateException) {
            ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                .body(mapOf("code" to "development_session_conflict", "message" to "Sign out this device before switching accounts."))
        }
    }
    private fun unavailable(): ResponseEntity<Any> = ResponseEntity.status(404).cacheControl(CacheControl.noStore())
        .body(mapOf("code" to "development_access_unavailable", "message" to "Local fixture access is unavailable."))
}
