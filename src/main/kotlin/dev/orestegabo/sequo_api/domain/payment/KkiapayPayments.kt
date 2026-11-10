package dev.orestegabo.sequo_api.domain.payment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.platform.PaymentProviderConfigurationRepository
import dev.orestegabo.sequo_api.domain.platform.PaymentProviderReadiness
import jakarta.persistence.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val KKIAPAY_SANDBOX_REFERENCE = "KKIAPAY_SANDBOX:"

@Service
class KkiapayConfiguration(
    @Value("\${sequo.kkiapay.enabled:false}") val enabled: Boolean,
    @Value("\${sequo.kkiapay.sandbox:true}") val sandbox: Boolean,
    @Value("\${sequo.kkiapay.live-approved:false}") val liveApproved: Boolean,
    @Value("\${sequo.kkiapay.public-key:}") val publicKey: String,
    @Value("\${sequo.kkiapay.private-key:}") val privateKey: String,
    @Value("\${sequo.kkiapay.secret-key:}") val secretKey: String,
    @Value("\${sequo.kkiapay.webhook-secret:}") val webhookSecret: String,
    @Value("\${sequo.kkiapay.public-base-url:https://api.sequoservice.com}") val publicBaseUrl: String,
    @Value("\${sequo.kkiapay.refunds-enabled:false}") val refundsEnabled: Boolean = false,
) {
    fun configured() = publicKey.matches(Regex("[A-Za-z0-9_-]{16,128}")) && listOf(publicKey, privateKey, secretKey).all { it.length in 16..1024 && it.none(Char::isISOControl) }
    fun verificationReady() = configured() && webhookSecret.length in 32..512 && webhookSecret.none(Char::isISOControl)
    fun ready() = enabled && verificationReady() && (sandbox || liveApproved) && runCatching {
        val uri = URI(publicBaseUrl)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/")
    }.getOrDefault(false)
    fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(UTF_8)))
    }
}

internal fun constantTimeEqual(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(UTF_8), b.toByteArray(UTF_8))

data class KkiapayTransaction(val id: String, val status: String, val amountCfa: Int,
    val correlation: String, val occurredAt: Instant?, val operator: String)

/** Uses the official SDK's fixed endpoints/headers. No redirects or user-selected upstream hosts. */
@Service
class KkiapayGateway(private val config: KkiapayConfiguration) {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun verify(id: String): KkiapayTransaction {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid transaction identifier." }
        val root = post("/api/v1/transactions/status", mapOf("transactionId" to id))
        return parseKkiapayTransaction(root, id)
    }
    fun refund(id: String): Boolean = post("/api/v1/transactions/revert", mapOf("transactionId" to id)).path("code").asText() == "SUCCESS"
    private fun post(path: String, body: Map<String, String>): JsonNode {
        check(config.configured()) { "KKiaPay credentials are unavailable." }
        val base = if (config.sandbox) "https://api-sandbox.kkiapay.me" else "https://api.kkiapay.me"
        val request = HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("x-api-key", config.publicKey).header("x-private-key", config.privateKey).header("x-secret-key", config.secretKey)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
            catch (_: Exception) { throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "KKiaPay verification is temporarily unavailable.") }
        val bytes = response.body().use { it.readNBytes(65_537) }
        if (response.statusCode() !in 200..299 || bytes.size > 65_536)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "KKiaPay did not verify this request.")
        return try { mapper.readTree(bytes) } catch (_: Exception) { throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid KKiaPay response.") }
    }
}

internal fun parseKkiapayTransaction(root: JsonNode, expectedId: String): KkiapayTransaction {
    val id = root.path("transactionId").asText()
    require(id == expectedId) { "Provider transaction identity mismatch." }
    require(root.path("amount").isIntegralNumber && root.path("amount").canConvertToInt()) { "Invalid verified payment amount." }
    val amount = root.path("amount").asInt(); require(amount > 0) { "Invalid verified payment amount." }
    // KKiaPay's documented API operates in XOF. Reject any explicit contradictory currency.
    require(!root.hasNonNull("currency") || root.path("currency").asText() == "XOF") { "Payment currency mismatch." }
    require(root.path("type").asText() == "DEBIT") { "Only incoming customer payments are accepted." }
    require(root.path("source").asText() == "MOBILE_MONEY") { "Only Togo mobile-money payments are accepted." }
    val operator = root.path("source_common_name").asText().lowercase()
    require(operator in setOf("mixx-tg", "tmoney-tg", "moov-tg")) { "Only Yas Togo and Moov Africa Togo are accepted." }
    val correlations = listOf("partnerId", "externalTransactionId").mapNotNull { root.path(it).asText().takeIf(String::isNotBlank) }.distinct()
    require(correlations.size == 1) { "Provider payment has no unambiguous checkout binding." }
    val time = listOf("performed_at", "performedAt").firstNotNullOfOrNull { root.path(it).asText().takeIf(String::isNotBlank) }
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return KkiapayTransaction(id, root.path("status").asText().uppercase(), amount, correlations.single(), time, operator)
}

@Entity @Table(name = "kkiapay_checkout_sessions")
class KkiapayCheckoutSession(
    @Id val id: String,
    @Column(name = "checkout_id", nullable = false, unique = true) val checkoutId: String,
    @Column(nullable = false) val sandbox: Boolean,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant,
    @Column(name = "transaction_id", unique = true) var transactionId: String? = null,
)
interface KkiapayCheckoutSessionRepository : JpaRepository<KkiapayCheckoutSession, String> {
    fun findByCheckoutId(checkoutId: String): KkiapayCheckoutSession?
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from KkiapayCheckoutSession s where s.id = :id")
    fun lockById(id: String): KkiapayCheckoutSession?
}

data class KkiapayPaymentLink(val url: String, val sandbox: Boolean, val expiresAt: Instant)

@Service
class KkiapayCheckoutService(private val config: KkiapayConfiguration, private val gateway: KkiapayGateway,
    private val sessions: KkiapayCheckoutSessionRepository, private val checkouts: PendingPaymentCheckoutRepository,
    private val receipts: PaymentWebhookService, private val providers: PaymentProviderConfigurationRepository) {
    private val mapper = ObjectMapper()
    @Transactional
    fun createLink(customer: String, checkoutId: String): KkiapayPaymentLink {
        require(config.ready() && providers.findById("kkiapay").orElse(null)?.readiness?.let { it != PaymentProviderReadiness.DISABLED } == true) { "KKiaPay is not ready for payment." }
        val checkout = checkouts.lockForReconciliation(checkoutId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        require(checkout.customerId == customer) { "Checkout is unavailable." }
        require(checkout.paymentProvider == SequoPaymentProviders.Kkiapay.value) { "This checkout uses another payment method." }
        require(checkout.status == PendingPaymentCheckoutStatus.AWAITING_WEBHOOK) { "This checkout is no longer awaiting payment." }
        require(config.sandbox == checkout.paymentReference.startsWith(KKIAPAY_SANDBOX_REFERENCE)) { "Payment environment mismatch." }
        val now = Instant.now()
        val session = sessions.findByCheckoutId(checkoutId) ?: KkiapayCheckoutSession(UUID.randomUUID().toString(), checkoutId, config.sandbox, now, now.plusSeconds(1200))
        require(session.sandbox == config.sandbox) { "Payment environment changed." }
        if (!session.expiresAt.isAfter(now)) session.expiresAt = now.plusSeconds(1200)
        sessions.saveAndFlush(session)
        val token = token(session)
        return KkiapayPaymentLink(config.publicBaseUrl.trimEnd('/') + "/payments/kkiapay?token=" + token, session.sandbox, session.expiresAt)
    }
    private fun token(s: KkiapayCheckoutSession) = s.id + "." + config.sign("kkiapay-launch:v1:${s.id}:${s.expiresAt.epochSecond}")
    private fun authorizedSession(token: String): KkiapayCheckoutSession {
        if (token.length !in 70..100) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val id = token.substringBefore('.')
        val session = sessions.findById(id).orElse(null) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (!config.verificationReady() || !constantTimeEqual(token(session), token) || !session.expiresAt.isAfter(Instant.now()) || session.sandbox != config.sandbox)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Payment link expired. Open Orders in Sequo to continue.")
        return session
    }
    @Transactional(readOnly = true)
    fun page(token: String): String {
        val session = authorizedSession(token)
        val checkout = checkouts.findById(session.checkoutId).orElseThrow()
        val paid = checkout.status == PendingPaymentCheckoutStatus.COMPLETED
        val verified = session.transactionId != null
        require(paid || verified || (config.ready() && providers.findById("kkiapay").orElse(null)?.readiness != PaymentProviderReadiness.DISABLED)) { "New payments are temporarily unavailable." }
        require(paid || checkout.status == PendingPaymentCheckoutStatus.AWAITING_WEBHOOK) { "Payment is no longer available." }
        val widget = mapper.writeValueAsString(mapOf("amount" to checkout.amountCfa, "key" to config.publicKey,
            "sandbox" to session.sandbox, "partnerId" to session.id, "countries" to listOf("TG"), "providers" to mapOf("accept" to listOf("mixx-tg", "moov-tg")),
            "theme" to "#0c7655", "position" to "center", "paymentmethod" to listOf("momo")))
        return paymentPage(widget, mapper.writeValueAsString(token), session.sandbox, checkout.amountCfa, paid || verified)
    }
    @Transactional
    fun complete(token: String, transactionId: String): Map<String, String> {
        val session = authorizedSession(token)
        return verifyForSession(session.id, transactionId)
    }
    @Transactional
    fun webhook(secret: String, raw: String): Map<String, String> {
        if (!config.verificationReady() || !constantTimeEqual(config.webhookSecret, secret)) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        require(raw.toByteArray(UTF_8).size <= 65_536) { "Webhook is too large." }
        val root = mapper.readTree(raw)
        require(root?.isObject == true) { "Invalid payment payload." }
        val id = root.path("transactionId").asText()
        // A webhook's amount, success flag and partnerId never authorize fulfillment.
        val verified = gateway.verify(id)
        return verifyForSession(verified.correlation, id, verified)
    }
    @Transactional
    fun refresh(customer: String, checkoutId: String): Map<String, String> {
        val checkout = checkouts.findByCheckoutIdAndCustomerId(checkoutId, customer)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        require(checkout.paymentProvider == "kkiapay") { "This checkout uses another payment method." }
        require(config.verificationReady()) { "KKiaPay verification is temporarily unavailable." }
        val session = sessions.findByCheckoutId(checkoutId)
        val transaction = session?.transactionId
        if (transaction == null) return mapOf("status" to "PENDING", "message" to "No verified transaction yet. If you authorized payment, wait for confirmation before trying again.")
        return verifyForSession(session.id, transaction.substringAfter(':'))
    }
    private fun verifyForSession(sessionId: String, transactionId: String, evidence: KkiapayTransaction? = null): Map<String, String> {
        val session = sessions.lockById(sessionId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val checkout = checkouts.findById(session.checkoutId).orElseThrow()
        require(session.sandbox == config.sandbox && checkout.paymentProvider == SequoPaymentProviders.Kkiapay.value) { "Payment environment mismatch." }
        require(session.sandbox == checkout.paymentReference.startsWith(KKIAPAY_SANDBOX_REFERENCE)) { "Checkout environment mismatch." }
        val verified = evidence ?: gateway.verify(transactionId)
        require(verified.id == transactionId && verified.correlation == session.id) { "Provider transaction belongs to another checkout." }
        require(verified.amountCfa == checkout.amountCfa) { "Verified payment amount differs from the reviewed checkout." }
        val success = verified.status == "SUCCESS"
        if (!success) return mapOf("status" to "PENDING", "message" to "Payment is not confirmed. Refresh your order before trying again.")
        val reference = (if (session.sandbox) "sandbox:" else "live:") + transactionId
        if (session.transactionId != null && session.transactionId != reference)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Another payment already belongs to this checkout. Contact support; do not pay again.")
        session.transactionId = reference; sessions.saveAndFlush(session)
        val now = Instant.now()
        val occurred = verified.occurredAt ?: session.createdAt
        val canonical = mapper.writeValueAsString(linkedMapOf("checkoutId" to checkout.checkoutId, "transactionId" to reference,
            "operator" to verified.operator, "amountCfa" to checkout.amountCfa, "currency" to "XOF", "status" to "VALIDATED", "occurredAt" to occurred.toString()))
        receipts.acceptKkiapayVerified(PaymentWebhookCommand(SequoPaymentProviders.Kkiapay, "$reference:success", checkout.checkoutId,
            checkout.paymentReference, reference, checkout.amountCfa, PaymentWebhookProviderStatus.VALIDATED,
            occurred, now, now, "server-verified", canonical))
        return mapOf("status" to "VERIFIED", "message" to "Payment verified. Return to Sequo and refresh Orders.")
    }
}

@RestController
class KkiapayPaymentController(private val service: KkiapayCheckoutService) {
    data class Completion(val token: String, val transactionId: String)
    @PostMapping("/api/orders/checkouts/{checkoutId}/kkiapay-session")
    fun link(@AuthenticationPrincipal customer: String?, @PathVariable checkoutId: String): KkiapayPaymentLink {
        if (customer == null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return service.createLink(customer, checkoutId)
    }
    @PostMapping("/api/orders/checkouts/{checkoutId}/kkiapay-refresh")
    fun refresh(@AuthenticationPrincipal customer: String?, @PathVariable checkoutId: String): Map<String, String> {
        if (customer == null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return service.refresh(customer, checkoutId)
    }
    @ExceptionHandler(ResponseStatusException::class)
    fun providerFailure(error: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(error.statusCode).body(mapOf("message" to (error.reason ?: "Payment request could not be completed.")))
    @ExceptionHandler(com.fasterxml.jackson.core.JsonProcessingException::class)
    fun malformedPayload(): ResponseEntity<Map<String, String>> = ResponseEntity.badRequest().body(mapOf("message" to "Invalid payment payload."))
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidRequest(error: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("message" to (error.message ?: "Invalid payment request.")))
    @GetMapping("/payments/kkiapay", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(@RequestParam token: String): ResponseEntity<String> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
        .header("X-Content-Type-Options", "nosniff").header("X-Frame-Options", "DENY")
        .header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline' https://cdn.kkiapay.me; style-src 'unsafe-inline'; frame-src https://*.kkiapay.me; connect-src 'self' https://*.kkiapay.me wss://*.kkiapay.me; img-src 'self' data: https://*.kkiapay.me; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
        .body(service.page(token))
    @PostMapping("/api/payments/kkiapay/complete")
    fun complete(@RequestBody body: Completion) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.complete(body.token, body.transactionId))
    @PostMapping("/api/payments/kkiapay/webhook")
    fun webhook(@RequestHeader("x-kkiapay-secret", defaultValue = "") secret: String, @RequestBody raw: String) = service.webhook(secret, raw)
}

private fun paymentPage(widget: String, token: String, sandbox: Boolean, amount: Int, paid: Boolean): String = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><title>Sequo payment</title>
<style>body{font:17px system-ui;background:#f4f8f5;color:#14261e;margin:0;min-height:100vh;display:grid;place-items:center}main{max-width:430px;margin:24px;padding:28px;background:white;border-radius:24px;box-shadow:0 12px 45px #153b2012}h1{font-size:26px}button{width:100%;border:0;border-radius:14px;background:#0c7655;color:white;font:inherit;padding:16px;cursor:pointer}p{line-height:1.5}.note{color:#5b6d63}.test{background:#fff2cf;padding:12px;border-radius:12px}</style></head>
<body><main><h1>Sequo · KKiaPay</h1>${if (sandbox) "<p class='test'>TEST PAYMENT · No real money is moved.</p>" else ""}<h2>$amount CFA</h2>
<p>Pay securely with Yas Togo (Mixx) or Moov Africa Togo (Flooz).</p><button id="pay" ${if (paid) "disabled" else ""}>${if (paid) "Payment received for verification" else "Continue with KKiaPay"}</button>
<p id="status" role="status" aria-live="polite">${if (paid) "Return to Sequo and refresh Orders." else "Your order becomes paid after Sequo verifies the transaction."}</p><p class="note">If a payment is pending, check your order before paying again. Close this page to return to Sequo.</p></main>
<script src="https://cdn.kkiapay.me/k.js"></script><script>
const options=$widget, token=$token, button=document.getElementById('pay'), status=document.getElementById('status');
button.onclick=()=>{button.disabled=true;status.textContent='Waiting for KKiaPay. Do not pay twice.';openKkiapayWidget(options);};
addSuccessListener(async response=>{try{const r=await fetch('/api/payments/kkiapay/complete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token,transactionId:response.transactionId})});if(!r.ok)throw Error();const result=await r.json();status.textContent=result.message;}catch(e){status.textContent='Confirmation is still pending. Return to Sequo and refresh Orders before paying again.';}});
addFailedListener(()=>{status.textContent='Payment was not confirmed. Return to Sequo and check your order.';});
</script></body></html>
""".trimIndent()

@Service
class KkiapayPaymentProviderAdapter(private val config: KkiapayConfiguration, private val gateway: KkiapayGateway,
    private val sessions: KkiapayCheckoutSessionRepository) : PaymentProviderAdapter {
    override val provider = SequoPaymentProviders.Kkiapay
    override val displayName = "KKiaPay"
    override val enabledFeatures get() = if (config.refundsEnabled) setOf(PaymentFeature.CustomerCheckout, PaymentFeature.Refund) else setOf(PaymentFeature.CustomerCheckout)
    override fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult =
        if (config.ready()) PaymentAttemptResult.Created(provider, request.checkoutId, request.amountCfa, "Continue in KKiaPay to authorize payment.")
        else PaymentAttemptResult.Blocked("KKiaPay is not configured for payment.")
    override fun checkPaymentStatus(request: ProviderPaymentStatusRequest) =
        if (config.ready()) PaymentValidationResult.pending("Awaiting verified KKiaPay payment.") else PaymentValidationResult.blocked("KKiaPay is not configured for payment.")
    override fun verifyCallback(request: ProviderCallbackVerificationRequest) = ProviderCallbackVerificationResult.Rejected("Use the dedicated authenticated KKiaPay webhook.")
    override fun issueRefundInstruction(request: PaymentRefundRequest): PaymentRefundResult {
        if (!config.ready() || !config.refundsEnabled) return PaymentRefundResult.Blocked("KKiaPay refunds require operator activation.")
        val prefix = if (config.sandbox) "sandbox:" else "live:"
        require(request.providerReference.startsWith(prefix)) { "Refund environment mismatch." }
        val transaction = gateway.verify(request.providerReference.removePrefix(prefix))
        val session = sessions.findById(transaction.correlation).orElseThrow { IllegalArgumentException("Refund checkout is unavailable.") }
        require(session.transactionId == request.providerReference && session.sandbox == config.sandbox && request.orderId == "SQ-" + session.checkoutId) { "Refund order binding mismatch." }
        require(transaction.amountCfa == request.amountCfa && transaction.status == "SUCCESS") { "Only a verified full refund is supported." }
        return if (gateway.refund(transaction.id)) PaymentRefundResult.Triggered(request.providerReference, "KKiaPay accepted the refund.")
        else PaymentRefundResult.Pending(request.providerReference, "Refund outcome needs reconciliation before retrying.")
    }
    override fun issuePayoutInstruction(request: ProviderPayoutInstructionRequest) = ProviderPayoutInstructionResult.Pending("Merchant payouts require verified payout destinations and provider marketplace approval.")
}
