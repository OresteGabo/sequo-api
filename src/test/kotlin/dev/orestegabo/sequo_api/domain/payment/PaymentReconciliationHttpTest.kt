package dev.orestegabo.sequo_api.domain.payment

import dev.orestegabo.sequo_api.domain.auth.InMemoryRateLimiter
import dev.orestegabo.sequo_api.domain.order.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@Import(PaymentWebhookFixtureConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:durable_payment_reconciliation;DB_CLOSE_DELAY=-1",
    "sequo.wallets.yas-togo.webhook-secret=isolated-callback-yas-secret",
    "sequo.wallets.moov-africa.webhook-secret=isolated-callback-moov-secret",
])
class PaymentReconciliationHttpTest @Autowired constructor(
    private val context: ApplicationContext,
    private val events: PaymentWebhookEventRepository,
    private val orders: CustomerOrderRecordRepository,
    private val pending: PendingPaymentCheckoutRepository,
    private val claims: PaymentTransactionClaimRepository,
    private val jdbc: JdbcTemplate,
    private val failures: PaymentReconciliationFailureRecorder,
) {
    companion object {
        @JvmStatic @DynamicPropertySource fun postgres(registry: DynamicPropertyRegistry) = reconciliationPostgres(registry)
    }
    @LocalServerPort private var port = 0
    @org.junit.jupiter.api.BeforeEach fun resetBudget() = context.getBean(InMemoryRateLimiter::class.java).resetForTests()

    @Test
    fun matchingAndOutOfOrderEventsHaveDurableDistinctOutcomesWithoutChangingPaidOrders() {
        for (provider in listOf(SequoPaymentProviders.YasTogo, SequoPaymentProviders.MoovAfrica)) {
            val fixture = CallbackFixtures.pending(context, provider)
            assertOutcome(send(fixture, status = "PENDING"), PaymentWebhookReconciliationStatus.IGNORED, "webhook_still_pending")
            assertOutcome(send(fixture, amount = 2401), PaymentWebhookReconciliationStatus.REVIEW_REQUIRED, "amount_mismatch")
            assertOutcome(send(fixture, paymentReference = "wrong-reference"), PaymentWebhookReconciliationStatus.REVIEW_REQUIRED, "payment_reference_mismatch")
            assertNull(orders.findByCheckoutId(fixture.checkoutId))
            val success = send(fixture)
            assertOutcome(success, PaymentWebhookReconciliationStatus.FULFILLED, "fulfilled")
            val canonicalOrder = assertNotNull(orders.findByCheckoutId(fixture.checkoutId))
            assertEquals(success.receivedAt, canonicalOrder.createdAt)
            assertOutcome(send(fixture, status = "PENDING"), PaymentWebhookReconciliationStatus.IGNORED, "pending_after_completion")
            assertOutcome(send(fixture, status = "FAILED"), PaymentWebhookReconciliationStatus.REVIEW_REQUIRED, "terminal_event_after_completion")
            assertOutcome(send(fixture, reference = "different-transaction-${UUID.randomUUID()}"),
                PaymentWebhookReconciliationStatus.REVIEW_REQUIRED, "completed_checkout_provider_reference_mismatch")
            jdbc.update("update pending_payment_checkouts set provider_reference = null where checkout_id = ?", fixture.checkoutId)
            assertOutcome(send(fixture, reference = "different-legacy-transaction-${UUID.randomUUID()}"),
                PaymentWebhookReconciliationStatus.REVIEW_REQUIRED, "completed_checkout_provider_reference_mismatch")
            val inconsistent = "legacy-inconsistent-${UUID.randomUUID()}"
            jdbc.update("update pending_payment_checkouts set provider_reference = ? where checkout_id = ?", inconsistent, fixture.checkoutId)
            assertOutcome(send(fixture), PaymentWebhookReconciliationStatus.REVIEW_REQUIRED, "completed_checkout_provider_reference_mismatch")
            assertEquals(inconsistent, pending.findById(fixture.checkoutId).orElseThrow().providerReference)
            assertEquals("internal-fixture-${fixture.checkoutId}", orders.findByCheckoutId(fixture.checkoutId)?.providerReference)
            assertEquals(canonicalOrder.id, orders.findByCheckoutId(fixture.checkoutId)?.id)
            assertEquals(2400, orders.findByCheckoutId(fixture.checkoutId)?.totalCfa)
            for (terminal in listOf("FAILED", "CANCELLED")) {
                val unpaid = CallbackFixtures.pending(context, provider)
                assertOutcome(send(unpaid, status = terminal), PaymentWebhookReconciliationStatus.IGNORED, "payment_${terminal.lowercase()}")
                assertOutcome(send(unpaid), PaymentWebhookReconciliationStatus.REVIEW_REQUIRED,
                    "validated_payment_after_${terminal.lowercase()}_checkout")
                assertEquals(PendingPaymentCheckoutStatus.valueOf(terminal), pending.findById(unpaid.checkoutId).orElseThrow().status)
                assertNull(orders.findByCheckoutId(unpaid.checkoutId))
                assertFalse(claims.existsById(paymentTransactionClaimId(provider.value, "internal-fixture-${unpaid.checkoutId}")))
            }
        }
    }

    @Test
    fun failedProcessingRetainsSafeOutcomeAndIdenticalVerifiedRetryRecoversCanonicalEvidence() {
        val fixture = CallbackFixtures.pending(context, SequoPaymentProviders.YasTogo)
        val saved = pending.findById(fixture.checkoutId).orElseThrow()
        val original = saved.pricingPayload
        jdbc.update("update pending_payment_checkouts set pricing_payload = ? where checkout_id = ?", "BROKEN_PRIVATE_FIXTURE", fixture.checkoutId)
        val id = "processing-retry-${UUID.randomUUID()}"
        val payload = CallbackFixtures.event(fixture, id)
        assertEquals(202, CallbackFixtures.send(port, fixture.provider, payload).statusCode())
        val failed = receipt(fixture.provider, id)
        assertOutcome(failed, PaymentWebhookReconciliationStatus.ERROR, "processing_failed")
        assertNull(orders.findByCheckoutId(fixture.checkoutId))
        assertFalse(claims.existsById(paymentTransactionClaimId(fixture.provider.value, "internal-fixture-${fixture.checkoutId}")))
        assertEquals(PendingPaymentCheckoutStatus.AWAITING_WEBHOOK, pending.findById(fixture.checkoutId).orElseThrow().status)
        jdbc.update("update pending_payment_checkouts set pricing_payload = ? where checkout_id = ?", original, fixture.checkoutId)
        val retry = CallbackFixtures.send(port, fixture.provider, payload)
        assertEquals(202, retry.statusCode(), retry.body())
        assertTrue(CallbackFixtures.json.readTree(retry.body())["duplicate"].asBoolean())
        val recovered = receipt(fixture.provider, id)
        assertOutcome(recovered, PaymentWebhookReconciliationStatus.FULFILLED, "fulfilled")
        assertEquals(failed.payloadHash, recovered.payloadHash)
        assertEquals(failed.receivedAt, recovered.receivedAt)
        assertEquals(1, orders.findByCustomerIdOrderByCreatedAtDesc(fixture.customerId).size)
        failures.record(recovered.toAcceptedEvent())
        val protected = receipt(fixture.provider, id)
        assertEquals(recovered.reconciliationStatus, protected.reconciliationStatus)
        assertEquals(recovered.reconciliationReason, protected.reconciliationReason)
        assertEquals(recovered.reconciledAt, protected.reconciledAt)
    }

    @Test
    fun concurrentEventIdentityConflictCannotMergeOrOverwriteCanonicalEvidence() {
        val first = CallbackFixtures.pending(context, SequoPaymentProviders.YasTogo)
        val second = CallbackFixtures.pending(context, SequoPaymentProviders.YasTogo)
        val id = "identity-race-${UUID.randomUUID()}"
        val bodies = listOf(first, second).associateBy({ it.checkoutId }, { CallbackFixtures.event(it, id) })
        val responses = bodies.values.map { body -> CompletableFuture.supplyAsync { CallbackFixtures.send(port, first.provider, body) } }.map { it.join() }
        assertEquals(1, responses.count { it.statusCode() == 202 })
        assertTrue(responses.all { it.statusCode() in setOf(202, 400, 409) }, responses.joinToString { "${it.statusCode()}:${it.body()}" })
        val winner = receipt(first.provider, id)
        assertEquals(hash(requireNotNull(bodies[winner.checkoutId])), winner.payloadHash)
        assertNotNull(orders.findByCheckoutId(winner.checkoutId))
        val loser = listOf(first, second).single { it.checkoutId != winner.checkoutId }
        assertNull(orders.findByCheckoutId(loser.checkoutId))
        assertEquals(202, CallbackFixtures.send(port, first.provider, requireNotNull(bodies[winner.checkoutId])).statusCode())
        assertEquals(400, CallbackFixtures.send(port, first.provider, requireNotNull(bodies[loser.checkoutId])).statusCode())
        val after = receipt(first.provider, id)
        assertEquals(winner.checkoutId, after.checkoutId)
        assertEquals(winner.payloadHash, after.payloadHash)
        assertEquals(winner.receivedAt, after.receivedAt)
    }

    @Test
    fun oneExplicitProviderTransactionCannotFundConcurrentCheckoutsOrAnExistingLegacyPaidReference() {
        val first = CallbackFixtures.pending(context, SequoPaymentProviders.MoovAfrica)
        val second = CallbackFixtures.pending(context, SequoPaymentProviders.MoovAfrica)
        val reference = "shared-transaction-${UUID.randomUUID()}"
        val ids = listOf(first, second).associate { it.checkoutId to "transaction-race-${UUID.randomUUID()}" }
        val bodies = listOf(first, second).associateBy({ it.checkoutId }, { CallbackFixtures.event(it, requireNotNull(ids[it.checkoutId]), providerReference = reference) })
        val responses = bodies.values.map { body -> CompletableFuture.supplyAsync { CallbackFixtures.send(port, first.provider, body) } }.map { it.join() }
        responses.forEach { assertEquals(202, it.statusCode(), it.body()) }
        val paid = listOf(first, second).filter { orders.findByCheckoutId(it.checkoutId) != null }
        assertEquals(1, paid.size)
        val winner = paid.single()
        val loser = listOf(first, second).single { it.checkoutId != winner.checkoutId }
        assertEquals(202, CallbackFixtures.send(port, first.provider, requireNotNull(bodies[loser.checkoutId])).statusCode())
        assertOutcome(receipt(first.provider, requireNotNull(ids[loser.checkoutId])),
            PaymentWebhookReconciliationStatus.REVIEW_REQUIRED, "provider_transaction_reference_already_used")
        val claim = claims.findById(paymentTransactionClaimId(first.provider.value, reference)).orElseThrow()
        assertEquals(winner.checkoutId, claim.checkoutId)
        assertNull(orders.findByCheckoutId(loser.checkoutId))
        // Model a preserved paid order predating the claim table: its existing payment reference
        // must still block a new claim without backfilling or changing historical money.
        claims.delete(claim)
        val legacyReuse = CallbackFixtures.pending(context, first.provider)
        assertOutcome(send(legacyReuse, reference = reference), PaymentWebhookReconciliationStatus.REVIEW_REQUIRED,
            "provider_transaction_reference_already_used")
        assertNull(orders.findByCheckoutId(legacyReuse.checkoutId))
        assertEquals(winner.checkoutId, orders.findByCheckoutId(winner.checkoutId)?.checkoutId)
        assertFalse(claims.existsById(claim.id))
    }

    private fun send(fixture: CallbackPending, status: String = "VALIDATED", reference: String? = "internal-fixture-${fixture.checkoutId}",
        amount: Int = 2400, paymentReference: String = fixture.checkoutId): PaymentWebhookEventRecord {
        val id = "reconcile-${UUID.randomUUID()}"
        val result = CallbackFixtures.send(port, fixture.provider, CallbackFixtures.event(fixture, id, status, reference, amount, paymentReference))
        assertEquals(202, result.statusCode(), result.body())
        return receipt(fixture.provider, id)
    }
    private fun receipt(provider: PaymentProviderId, id: String) = assertNotNull(events.findByProviderAndEventId(provider.value, id))
    private fun assertOutcome(receipt: PaymentWebhookEventRecord, status: PaymentWebhookReconciliationStatus, reason: String) {
        assertEquals(status, receipt.reconciliationStatus)
        assertEquals(reason, receipt.reconciliationReason)
        assertNotNull(receipt.reconciledAt)
    }
}

internal fun reconciliationPostgres(registry: DynamicPropertyRegistry) {
    val url = System.getenv("SEQUO_RECONCILIATION_TEST_URL") ?: return
    require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) { "Use a disposable local reconciliation fixture database." }
    registry.add("spring.datasource.url") { url }; registry.add("spring.datasource.username") { "sequo_fixture" }
    registry.add("spring.datasource.password") { "" }; registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
    registry.add("spring.jpa.database-platform") { "org.hibernate.dialect.PostgreSQLDialect" }
    registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }; registry.add("spring.flyway.enabled") { "true" }
}

internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
