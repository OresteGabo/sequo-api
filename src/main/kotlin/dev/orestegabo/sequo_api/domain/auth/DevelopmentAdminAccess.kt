package dev.orestegabo.sequo_api.domain.auth

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

data class DevelopmentAdminAccessSettings(
    val enabled: Boolean,
    val profiles: Set<String>,
    val fixtureEnvironment: String,
    val bindAddress: String,
    val forwardHeadersStrategy: String,
    val origins: List<String>,
    val datasourceUrl: String,
    val userId: String,
    val jwtIssuer: String,
    val jwtAudience: String,
)

/** Independent of allow-dev-defaults: production-like or mixed profiles can never opt in. */
object DevelopmentAdminAccessChecks {
    const val Issuer = "sequo-api-local-admin-fixture"
    const val Audience = "sequo-admin-local-fixture"
    private val profiles = setOf("local", "dev", "development", "postgres-local")
    private val fixtureDatabase = Regex("sequo_admin_dev[A-Za-z0-9_]*")

    fun findings(settings: DevelopmentAdminAccessSettings): List<String> {
        if (!settings.enabled) return emptyList()
        return buildList {
            if (settings.profiles.isEmpty() || settings.profiles.any { it.lowercase() !in profiles }) {
                add("Only explicit local/dev development profiles may enable local admin access.")
            }
            if (settings.fixtureEnvironment != "LOCAL_FIXTURE") add("Local admin access requires LOCAL_FIXTURE configuration.")
            if (!loopbackAddress(settings.bindAddress)) add("server.address must bind to a loopback literal.")
            if (!settings.forwardHeadersStrategy.equals("NONE", true)) add("server.forward-headers-strategy must be NONE.")
            if (settings.origins.isEmpty() || settings.origins.any { !loopbackOrigin(it) }) {
                add("CORS origins must explicitly list only loopback HTTP(S) origins.")
            }
            if (!fixtureDatasource(settings.datasourceUrl)) add("A loopback or in-memory sequo_admin_dev fixture database is required.")
            if (settings.userId.isBlank()) add("A pre-existing local fixture admin user-id must be configured.")
            if (settings.jwtIssuer != Issuer || settings.jwtAudience != Audience) {
                add("The local fixture JWT issuer and audience must be isolated from ordinary deployments.")
            }
        }
    }

    fun loopbackAddress(value: String): Boolean =
        value.removeSurrounding("[", "]").lowercase() in setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

    fun loopbackHost(value: String): Boolean = value.equals("localhost", true) || loopbackAddress(value)

    private fun loopbackOrigin(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && uri.host?.let(::loopbackHost) == true &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.orEmpty().isEmpty() && (uri.port == -1 || uri.port in 1..65535)
    }.getOrDefault(false)

    private fun fixtureDatasource(value: String): Boolean {
        if (value.startsWith("jdbc:h2:mem:")) {
            return fixtureDatabase.matches(value.removePrefix("jdbc:h2:mem:").substringBefore(';'))
        }
        if (!value.startsWith("jdbc:postgresql://")) return false
        return runCatching {
            val uri = URI(value.removePrefix("jdbc:"))
            uri.host?.let(::loopbackHost) == true && uri.rawUserInfo == null && uri.rawFragment == null &&
                fixtureDatabase.matches(uri.path.orEmpty().removePrefix("/")) &&
                (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)
    }
}

@Component
class DevelopmentAdminAccessPolicy(environment: Environment) {
    val settings = DevelopmentAdminAccessSettings(
        enabled = environment.getProperty("sequo.admin.local-access.enabled", Boolean::class.java, false),
        profiles = environment.activeProfiles.toSet(),
        fixtureEnvironment = environment.getProperty("sequo.admin.local-access.environment", ""),
        bindAddress = environment.getProperty("server.address", ""),
        forwardHeadersStrategy = environment.getProperty("server.forward-headers-strategy", ""),
        origins = environment.getProperty("sequo.security.cors.allowed-origins", "")
            .split(',').map(String::trim).filter(String::isNotBlank),
        datasourceUrl = environment.getProperty("spring.datasource.url", ""),
        userId = environment.getProperty("sequo.admin.local-access.user-id", "").trim(),
        jwtIssuer = environment.getProperty("sequo.auth.jwt.issuer", "sequo-api"),
        jwtAudience = environment.getProperty("sequo.auth.jwt.audience", "sequo-mobile"),
    )

    init {
        val findings = DevelopmentAdminAccessChecks.findings(settings)
        check(findings.isEmpty()) { "Unsafe local admin access configuration: ${findings.joinToString(" ")}" }
    }

    fun allows(request: HttpServletRequest): Boolean =
        settings.enabled && DevelopmentAdminAccessChecks.loopbackAddress(request.remoteAddr.orEmpty()) &&
            DevelopmentAdminAccessChecks.loopbackHost(request.serverName.orEmpty()) &&
            request.getHeader("Origin") in settings.origins &&
            listOf("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP")
                .none { request.getHeader(it) != null }
}

@Service
class DevelopmentAdminSessionService(
    private val policy: DevelopmentAdminAccessPolicy,
    private val users: UserRepository,
    private val authService: AuthService,
    private val audit: AdminAuthAuditService,
) {
    @Transactional
    fun create(request: DevelopmentAdminAccessController.SessionRequest): AdminAuthController.ExchangeLoginResponse? {
        if (!policy.settings.enabled) return null
        val deviceId = ApiInputPolicy.requiredIdentifier(request.deviceId ?: "", "deviceId")
        val user = users.lockCheckoutOwner(policy.settings.userId) ?: return null
        if (!user.active || !user.status.canAuthenticate() || user.roles.none { it in RoleGroups.AdminOnly }) return null
        val tokens = authService.issueTokensForExistingUser(requireNotNull(user.id), user.provider, DeviceBinding(
            deviceId = deviceId,
            appSource = AppSource.SEQUO_ADMIN,
            fcmToken = null,
            deviceLabel = ApiInputPolicy.optionalShortText(request.deviceLabel, "deviceLabel", 255),
            browserInfo = ApiInputPolicy.optionalShortText(request.browserInfo, "browserInfo", 255),
        )) ?: return null
        audit.record(AdminAuthAuditEvent(AdminAuthAuditEventType.LOCAL_DEVELOPMENT_SESSION_CREATED, userId = user.id))
        return AdminAuthController.ExchangeLoginResponse(tokens.accessToken, tokens.refreshToken, tokens.expiresIn,
            AuthController.CurrentUserResponse(requireNotNull(user.id), user.phoneNumber, user.email, user.displayName,
                user.avatarUrl, user.provider, user.status, user.active, user.roles))
    }
}

@RestController
@RequestMapping("/api/admin-auth")
class DevelopmentAdminAccessController(
    private val policy: DevelopmentAdminAccessPolicy,
    private val sessions: DevelopmentAdminSessionService,
    private val rateLimiter: AuthRateLimiter,
) {
    data class AccessResponse(val enabled: Boolean, val mode: String?)
    data class SessionRequest(val deviceId: String? = null, val deviceLabel: String? = null, val browserInfo: String? = null)

    @GetMapping("/development-access")
    fun access(request: HttpServletRequest): ResponseEntity<AccessResponse> {
        val enabled = policy.allows(request)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(AccessResponse(enabled, "LOCAL_DEVELOPMENT".takeIf { enabled }))
    }

    @PostMapping("/development-session")
    fun session(request: HttpServletRequest, @RequestBody body: SessionRequest): ResponseEntity<Any> {
        if (!policy.allows(request)) return unavailable()
        return try {
            rateLimiter.checkAdminDevelopmentSession()
            val result = sessions.create(body) ?: return unavailable()
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
        } catch (e: RateLimitExceededException) {
            ResponseEntity.status(429).cacheControl(CacheControl.noStore()).header("Retry-After", e.retryAfterSeconds.toString())
                .body(AdminAuthController.AdminAuthErrorResponse("rate_limited", "Please wait before trying again."))
        } catch (_: IllegalArgumentException) {
            ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(AdminAuthController.AdminAuthErrorResponse("invalid_development_session", "A valid browser deviceId is required."))
        } catch (_: IllegalStateException) {
            ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                .body(AdminAuthController.AdminAuthErrorResponse("development_session_conflict", "Sign out this browser before switching accounts."))
        }
    }

    private fun unavailable(): ResponseEntity<Any> = ResponseEntity.status(404).cacheControl(CacheControl.noStore())
        .body(AdminAuthController.AdminAuthErrorResponse("development_access_unavailable", "Local development access is unavailable."))
}
