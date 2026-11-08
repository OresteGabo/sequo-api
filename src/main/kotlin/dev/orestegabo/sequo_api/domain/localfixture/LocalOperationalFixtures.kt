package dev.orestegabo.sequo_api.domain.localfixture

import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.order.toOrderId
import jakarta.persistence.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock

/** Static, synthetic scenarios only. This is not a general payment simulator or account impersonation API. */
data class LocalOperationalFixtureContext(val adminId: String, val createdAt: Instant) {
    val prefix = "local-ops-v1"
    fun id(suffix: String) = "$prefix-$suffix"
    val customerId = id("customer-ama")
    val merchantIds = listOf(id("merchant-maison"), id("merchant-atelier"))
    val courierIds = listOf(id("courier-senam"), id("courier-komlan"), id("courier-kokou"), id("courier-akossua"))
    val relayOperatorIds = listOf(id("operator-dede"), id("operator-esso"))
    val relayPointIds = listOf(id("relay-tokoin"), id("relay-adidogome"))
    val generalProductId = id("product-general")
    val foodProductId = id("product-food")
    val secondMerchantProductId = id("product-second")
    val relayOrderIds = listOf("fresh", "after-eight-days", "after-thirty-days").associateWith { id("relay-order-$it").toOrderId() }
    val returnOrderIds = listOf("request", "awaiting-dropoff", "dropoff", "collected", "received", "accepted", "refund")
        .associateWith { id("return-$it").toOrderId() }
}

data class LocalFixtureAccount(val id: String, val name: String, val email: String, val roles: List<String>, val purpose: String,
    val publicReference: String? = null)
data class LocalFixtureScenario(val id: String, val title: String, val summary: String, val category: String,
    val resourceIds: Map<String, String>, val nextActions: List<String>, val credentials: Map<String, String> = emptyMap(),
    val resourceReferences: Map<String, String> = emptyMap())
data class LocalFixtureManifest(val environment: String = "LOCAL_FIXTURE", val namespace: String = "local-ops-v1",
    val version: Int = 1, val seededAt: Instant? = null, val accounts: List<LocalFixtureAccount> = emptyList(),
    val scenarios: List<LocalFixtureScenario> = emptyList(),
    val note: String = "Synthetic local data only. Payments are simulated internal evidence; no funds move. Initial scenario descriptions do not reset current records.")

@Component
class LocalOperationalFixturePolicy(private val access: DevelopmentAdminAccessPolicy, environment: Environment) {
    val enabled = environment.getProperty("sequo.admin.local-fixtures.enabled", Boolean::class.java, false)
    init {
        if (enabled) {
            check(access.settings.enabled && DevelopmentAdminAccessChecks.findings(access.settings).isEmpty()) {
                "Local fixtures require the independently guarded local development workspace."
            }
            val workers = listOf("outbox-worker", "provider-delivery-worker")
            check(workers.none { environment.getProperty("sequo.notifications.$it.enabled", Boolean::class.java, true) }) {
                "Local fixtures require notification delivery workers to be explicitly disabled."
            }
            check(!environment.getProperty("sequo.payments.simulator.enabled", Boolean::class.java, false)) {
                "The general runtime payment simulator must remain disabled."
            }
            check(listOf("yas-togo", "moov-africa").all { provider ->
                listOf("api-base-url", "api-key", "merchant-account-id", "webhook-secret").all { key ->
                    environment.getProperty("sequo.wallets.$provider.$key", "").isBlank()
                }
            }) { "Local fixtures refuse configured wallet/provider credentials." }
        }
    }
    fun requireEnabled() { check(enabled) { "Local operational fixtures are disabled." } }
    fun allows(request: HttpServletRequest) = enabled && access.allows(request)
    val adminId: String get() = access.settings.userId
}

@Entity
@Table(name = "local_operational_fixture_runs")
class LocalOperationalFixtureRun(
    @Id @Column(name = "namespace", nullable = false) val namespace: String,
    @Column(name = "seeded_at") var seededAt: Instant? = null,
    @Column(name = "manifest_json", columnDefinition = "text") var manifestJson: String? = null,
)
interface LocalOperationalFixtureRunRepository : JpaRepository<LocalOperationalFixtureRun, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LocalOperationalFixtureRun r where r.namespace = :namespace")
    fun lockRun(namespace: String): LocalOperationalFixtureRun?
}

@Service
class LocalOperationalFixtureService(
    private val policy: LocalOperationalFixturePolicy,
    private val runs: LocalOperationalFixtureRunRepository,
    private val foundation: LocalOperationalFixtureFoundation,
    private val journeys: LocalOperationalJourneySeeder,
    private val returns: LocalReturnRelayFixtureSeeder,
    private val users: UserRepository,
    private val publicReferences: PublicReferenceRegistry? = null,
) {
    fun authorized(authentication: Authentication?): Boolean {
        if (!policy.enabled || authentication?.name != policy.adminId) return false
        val user = users.findById(policy.adminId).orElse(null) ?: return false
        return user.active && user.status.canAuthenticate() && RoleCode.SUPER_ADMIN in user.roles &&
            authentication.authorities.any { it.authority == "ROLE_SUPER_ADMIN" }
    }

    @Transactional(readOnly = true)
    fun manifest(): LocalFixtureManifest = withReferences(runs.findById("local-ops-v1").orElse(null)?.manifestJson
        ?.let { LocalFixtureManifestCodec.decode(it) } ?: LocalFixtureManifest())

    /** Refresh display metadata only; never rewrite the retained scene, credentials or business state. */
    private fun withReferences(manifest: LocalFixtureManifest): LocalFixtureManifest {
        val registry = publicReferences ?: return manifest
        val kinds = mapOf("userId" to PublicReferenceKind.USER, "customerId" to PublicReferenceKind.USER,
            "operatorUserId" to PublicReferenceKind.USER, "merchantId" to PublicReferenceKind.MERCHANT,
            "productId" to PublicReferenceKind.PRODUCT, "courierId" to PublicReferenceKind.COURIER,
            "checkoutId" to PublicReferenceKind.CHECKOUT, "orderId" to PublicReferenceKind.ORDER,
            "missionId" to PublicReferenceKind.DELIVERY, "manifestId" to PublicReferenceKind.GROUP,
            "returnId" to PublicReferenceKind.RETURN, "parcelId" to PublicReferenceKind.PARCEL,
            "relayPointId" to PublicReferenceKind.RELAY, "lockerId" to PublicReferenceKind.LOCKER)
        return manifest.copy(accounts = manifest.accounts.map { it.copy(publicReference = registry.reference(PublicReferenceKind.USER, it.id)) },
            scenarios = manifest.scenarios.map { scene -> scene.copy(resourceReferences = scene.resourceIds.mapNotNull { (field, id) ->
                kinds[field]?.let { field to registry.reference(it, id) }
            }.toMap()) })
    }

    /** The registry row alone stays locked. Domain service transactions must commit before reconciliation. */
    @Transactional
    fun seed(): LocalFixtureManifest {
        policy.requireEnabled()
        val run = requireNotNull(runs.lockRun("local-ops-v1")) { "Local fixture metadata migration is missing." }
        run.manifestJson?.let { return withReferences(LocalFixtureManifestCodec.decode(it)) }
        val context = LocalOperationalFixtureContext(policy.adminId, Instant.now())
        val accounts = foundation.seed(context)
        val supportingOrderIds = context.returnOrderIds.keys.map { stage -> context.id("return-$stage") } + context.relayOrderIds.keys.map { name -> context.id("relay-order-$name") }
        val orderScenes = journeys.seed(context).filterNot { it.id in supportingOrderIds }
        val scenes = foundation.scenarios(context) + orderScenes + returns.seed(context)
        val manifest = LocalFixtureManifest(seededAt = context.createdAt, accounts = accounts, scenarios = scenes)
        run.manifestJson = LocalFixtureManifestCodec.encode(manifest)
        run.seededAt = context.createdAt
        return withReferences(manifest)
    }
}

@RestController
@RequestMapping("/api/admin/local-fixtures")
class LocalOperationalFixtureController(private val policy: LocalOperationalFixturePolicy, private val fixtures: LocalOperationalFixtureService) {
    private val seedGate = ReentrantLock()
    @GetMapping
    fun manifest(request: HttpServletRequest, authentication: Authentication?): ResponseEntity<Any> =
        if (!policy.allows(request) || !fixtures.authorized(authentication)) unavailable()
        else ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(fixtures.manifest())

    @PostMapping("/seed")
    fun seed(request: HttpServletRequest, authentication: Authentication?, @RequestBody(required = false) body: Map<String, Any?>?): ResponseEntity<Any> {
        if (!policy.allows(request) || !fixtures.authorized(authentication)) return unavailable()
        if (!body.isNullOrEmpty()) return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
            .body(mapOf("message" to "Local fixtures accept only the fixed synthetic dataset; no custom accounts or payment values."))
        if (!seedGate.tryLock()) return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).header("Retry-After", "3")
            .body(mapOf("message" to "Local test setup is already running. Wait for it to finish, then refresh."))
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(fixtures.seed()) }
        finally { seedGate.unlock() }
    }
    private fun unavailable(): ResponseEntity<Any> = ResponseEntity.status(404).cacheControl(CacheControl.noStore())
        .body(mapOf("message" to "Local test fixtures are unavailable here."))
}
