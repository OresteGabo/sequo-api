package dev.orestegabo.sequo_api.domain.localfixture

import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.delivery.MerchantFulfillmentService
import dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderSnapshot
import dev.orestegabo.sequo_api.domain.delivery.MerchantResponseDeadlinePolicy
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.PendingPaymentCheckoutRepository
import dev.orestegabo.sequo_api.domain.order.toOrderId
import dev.orestegabo.sequo_api.domain.payment.SequoPaymentProviders
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant

/** Eighteen server-owned slots, not an arbitrary mark-paid or deadline-reset interface. */
enum class LocalMerchantIncomingScene(val slug: String) {
    ANDROID_ACCEPT("android-accept"), ANDROID_REFUSE("android-refuse"), ANDROID_WAIT("android-wait"),
    IOS_ACCEPT("ios-accept"), IOS_REFUSE("ios-refuse"), IOS_WAIT("ios-wait");

    fun checkoutId(attempt: Int): String {
        require(attempt in 1..3) { "The local fixture attempt must be 1, 2 or 3." }
        return "local-ops-v1-merchant-incoming-$slug-$attempt"
    }
}

data class LocalMerchantIncomingFixtureResult(
    val environment: String = "LOCAL_FIXTURE",
    val scene: String,
    val attempt: Int,
    val reused: Boolean,
    val checkoutId: String,
    val checkoutStatus: String,
    val orderId: String?,
    val subOrder: MerchantSubOrderSnapshot?,
    val note: String = "Fixed synthetic evidence only; no funds move. Existing slots, deadlines and user actions are never reset. A partial slot requires review; choose another unused attempt rather than repairing it.",
)

@Service
class LocalMerchantIncomingFixtureService(
    private val policy: LocalOperationalFixturePolicy,
    private val runs: LocalOperationalFixtureRunRepository,
    private val foundation: LocalOperationalFixtureFoundation,
    private val journeys: LocalOperationalJourneySeeder,
    private val checkouts: PendingPaymentCheckoutRepository,
    private val orders: CustomerOrderRecordRepository,
    private val fulfillment: MerchantFulfillmentService,
    private val users: UserRepository,
    private val memberships: MerchantMembershipRepository,
    private val merchants: CommerceMerchantRepository,
    private val responsePolicy: MerchantResponseDeadlinePolicy,
) {
    /** Registry serialization is held while the existing NOT_SUPPORTED scene commits its domain stages. */
    @Transactional(rollbackFor = [Exception::class])
    fun create(scene: LocalMerchantIncomingScene, attempt: Int): LocalMerchantIncomingFixtureResult {
        policy.requireEnabled()
        val run = checkNotNull(runs.lockRun("local-ops-v1")) { "The completed local fixture manifest is required." }
        check(run.seededAt != null && run.manifestJson != null) { "The completed local fixture manifest is required." }
        val manifest = LocalFixtureManifestCodec.decode(requireNotNull(run.manifestJson))
        check(manifest.seededAt != null && manifest.namespace == "local-ops-v1" && manifest.environment == "LOCAL_FIXTURE") {
            "The completed local fixture manifest is required."
        }
        val context = LocalOperationalFixtureContext(policy.adminId, Instant.now())
        val checkoutId = scene.checkoutId(attempt)
        val existing = checkouts.findById(checkoutId).orElse(null)
        if (existing != null) {
            check(existing.customerId == context.customerId && existing.paymentProvider == SequoPaymentProviders.YasTogo.value &&
                existing.paymentReference == checkoutId) { "The fixed local slot is occupied; existing records were retained." }
            return readback(scene, attempt, reused = true)
        }
        check(responsePolicy.enabled) { "Incoming-order server deadlines must be enabled for a new timed fixture." }
        check(!orders.existsById(checkoutId.toOrderId())) { "The fixed local order identity is occupied; existing records were retained." }
        requireSyntheticParties(context, manifest)
        journeys.seedMerchantIncoming(context, scene, attempt)
        return readback(scene, attempt, reused = false)
    }

    private fun requireSyntheticParties(context: LocalOperationalFixtureContext, manifest: LocalFixtureManifest) {
        val ownerId = context.id("owner-yawa")
        val expected = foundation.accounts(context).filter { it.id in setOf(context.customerId, ownerId) }
        check(expected.size == 2)
        for (account in expected) {
            check(manifest.accounts.singleOrNull { it.id == account.id } == account) { "The original synthetic account manifest is required." }
            val user = checkNotNull(users.findById(account.id).orElse(null)) { "The synthetic account is unavailable; existing records were retained." }
            check(user.active && user.status == UserStatus.ACTIVE && user.provider == AuthProvider.EMAIL &&
                user.email == account.email && user.displayName == account.name && user.phoneNumber == null && user.avatarUrl == null &&
                user.roles.map { it.name }.toSet() == account.roles.toSet()) {
                "The synthetic account was changed; existing records were retained."
            }
        }
        val merchant = checkNotNull(merchants.findById(context.merchantIds.first()).orElse(null)) { "The synthetic merchant is unavailable." }
        check(merchant.ownerUserId == ownerId && merchant.status == "ACTIVE" &&
            memberships.findAllByUserIdAndActiveTrue(ownerId).any {
                it.merchantId == merchant.id && it.role == RoleCode.MERCHANT_OWNER
            }) { "The synthetic merchant ownership or membership was changed; existing records were retained." }
    }

    private fun readback(scene: LocalMerchantIncomingScene, attempt: Int, reused: Boolean): LocalMerchantIncomingFixtureResult {
        val checkoutId = scene.checkoutId(attempt)
        val checkout = checkouts.findById(checkoutId).orElseThrow()
        val order = orders.findByCheckoutId(checkoutId)
        val part = order?.let { fulfillment.listForOrder(it.id).singleOrNull() }
        return LocalMerchantIncomingFixtureResult(scene = scene.slug, attempt = attempt, reused = reused,
            checkoutId = checkoutId, checkoutStatus = checkout.status.name, orderId = order?.id, subOrder = part)
    }
}

@RestController
@RequestMapping("/api/admin/local-fixtures/vendor-incoming")
class LocalMerchantIncomingFixtureController(
    private val policy: LocalOperationalFixturePolicy,
    private val fixtures: LocalOperationalFixtureService,
    private val incoming: LocalMerchantIncomingFixtureService,
) {
    @PostMapping("/{scene}")
    fun create(request: HttpServletRequest, authentication: Authentication?, @PathVariable scene: String,
        @RequestBody(required = false) body: Map<String, Any?>?): ResponseEntity<Any> {
        if (!policy.allows(request) || !fixtures.authorized(authentication)) return response(404,
            mapOf("message" to "Local test fixtures are unavailable here."))
        val selected = LocalMerchantIncomingScene.entries.singleOrNull { it.slug == scene }
        val attempt = body?.get("attempt") as? Int
        if (selected == null || body?.keys != setOf("attempt") || attempt == null || attempt !in 1..3)
            return response(400, mapOf("message" to "Choose a fixed Android/iOS accept, refuse or wait scene with only an integer attempt from 1 to 3."))
        return try {
            response(200, incoming.create(selected, attempt))
        } catch (_: IllegalStateException) {
            response(409, mapOf("message" to "The local incoming fixture is unavailable or requires review. Existing records were retained; no slot was reset."))
        } catch (_: IllegalArgumentException) {
            response(409, mapOf("message" to "The fixed local product or parties are unavailable. Existing records were retained; no slot was reset."))
        }
    }

    private fun response(status: Int, body: Any): ResponseEntity<Any> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
}
