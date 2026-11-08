package dev.orestegabo.sequo_api.domain.payment

import dev.orestegabo.sequo_api.SequoApiApplication
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

class PaymentReconciliationRestartTest {
    @TempDir lateinit var databaseDirectory: Path

    @Test
    fun verifiedRetryResumesDurablePendingEvidenceAfterRestartWhileLegacyEvidenceStaysForReview() {
        lateinit var interrupted: StoredFixture
        lateinit var legacy: StoredFixture
        start().use { context ->
            interrupted = stored(context, legacy = false)
            legacy = stored(context, legacy = true)
            assertNull(context.getBean(CustomerOrderRecordRepository::class.java).findByCheckoutId(interrupted.pending.checkoutId))
        }
        start().use { context ->
            val events = context.getBean(PaymentWebhookEventRepository::class.java)
            val orders = context.getBean(CustomerOrderRecordRepository::class.java)
            assertEquals(PaymentWebhookReconciliationStatus.PENDING, events.findById(interrupted.id).orElseThrow().reconciliationStatus)
            assertNull(orders.findByCheckoutId(interrupted.pending.checkoutId))
            val port = context.environment.getRequiredProperty("local.server.port").toInt()
            val retained = CallbackFixtures.send(port, legacy.pending.provider, legacy.body)
            assertEquals(202, retained.statusCode(), retained.body())
            assertEquals(PaymentWebhookReconciliationStatus.LEGACY_REVIEW_REQUIRED, events.findById(legacy.id).orElseThrow().reconciliationStatus)
            assertNull(orders.findByCheckoutId(legacy.pending.checkoutId))
            val retry = CallbackFixtures.send(port, interrupted.pending.provider, interrupted.body)
            assertEquals(202, retry.statusCode(), retry.body())
            assertTrue(CallbackFixtures.json.readTree(retry.body())["duplicate"].asBoolean())
            val event = events.findById(interrupted.id).orElseThrow()
            assertEquals(PaymentWebhookReconciliationStatus.FULFILLED, event.reconciliationStatus)
            assertEquals("fulfilled", event.reconciliationReason)
            assertNotNull(event.reconciledAt)
            assertEquals(interrupted.receivedAt, event.receivedAt)
            assertEquals(hash(interrupted.body), event.payloadHash)
            assertEquals(event.receivedAt, orders.findByCheckoutId(interrupted.pending.checkoutId)?.createdAt)
        }
        start().use { context ->
            val events = context.getBean(PaymentWebhookEventRepository::class.java)
            val orders = context.getBean(CustomerOrderRecordRepository::class.java)
            assertEquals(PaymentWebhookReconciliationStatus.FULFILLED, events.findById(interrupted.id).orElseThrow().reconciliationStatus)
            assertEquals(1, orders.findByCustomerIdOrderByCreatedAtDesc(interrupted.pending.customerId).size)
            assertEquals(PaymentWebhookReconciliationStatus.LEGACY_REVIEW_REQUIRED, events.findById(legacy.id).orElseThrow().reconciliationStatus)
            val port = context.environment.getRequiredProperty("local.server.port").toInt()
            assertEquals(202, CallbackFixtures.send(port, interrupted.pending.provider, interrupted.body).statusCode())
            assertEquals(1, orders.findByCustomerIdOrderByCreatedAtDesc(interrupted.pending.customerId).size)
        }
    }

    private data class StoredFixture(val pending: CallbackPending, val id: String, val body: String, val receivedAt: Instant)
    private fun stored(context: ConfigurableApplicationContext, legacy: Boolean): StoredFixture {
        val pending = CallbackFixtures.pending(context, SequoPaymentProviders.YasTogo)
        val eventId = "restart-evidence-${UUID.randomUUID()}"
        val body = CallbackFixtures.event(pending, eventId)
        val payload = CallbackFixtures.json.readTree(body)
        val id = "${pending.provider.value}:$eventId"
        val events = context.getBean(PaymentWebhookEventRepository::class.java)
        // Trusted local evidence models a process stopping after receipt commit but before the
        // after-commit listener. No unverified production callback is enabled by this fixture.
        events.saveAndFlush(PaymentWebhookEventRecord(id = id, provider = pending.provider.value, eventId = eventId,
            checkoutId = pending.checkoutId, paymentReference = pending.checkoutId,
            providerReference = payload["providerReference"].asText(), amountCfa = 2400, status = PaymentWebhookProviderStatus.VALIDATED,
            occurredAt = Instant.parse(payload["occurredAt"].asText()), payloadHash = hash(body), receivedAt = Instant.now(),
            reconciliationStatus = if (legacy) PaymentWebhookReconciliationStatus.LEGACY_REVIEW_REQUIRED else PaymentWebhookReconciliationStatus.PENDING))
        return StoredFixture(pending, id, body, events.findById(id).orElseThrow().receivedAt)
    }

    private fun start(): ConfigurableApplicationContext {
        val url = System.getenv("SEQUO_RECONCILIATION_TEST_URL")
        val database = if (url == null) listOf(
            "--spring.datasource.url=jdbc:h2:file:${databaseDirectory.resolve("reconciliation")}",
            "--spring.jpa.hibernate.ddl-auto=update", "--spring.flyway.enabled=false",
        ) else {
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) { "Use a disposable local reconciliation fixture database." }
            listOf("--spring.datasource.url=$url", "--spring.datasource.username=sequo_fixture", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.postgresql.Driver", "--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=true")
        }
        return SpringApplicationBuilder(SequoApiApplication::class.java, PaymentWebhookFixtureConfiguration::class.java).run(*(
            database + listOf("--server.port=0", "--sequo.wallets.yas-togo.webhook-secret=isolated-callback-yas-secret",
                "--sequo.wallets.moov-africa.webhook-secret=isolated-callback-moov-secret")
        ).toTypedArray())
    }
}
