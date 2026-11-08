package dev.orestegabo.sequo_api.domain.localfixture

import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.settlement.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.*

@ActiveProfiles("dev")
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:sequo_admin_dev_journeys;DB_CLOSE_DELAY=-1",
    "server.address=127.0.0.1", "server.forward-headers-strategy=NONE",
    "sequo.admin.local-access.enabled=true", "sequo.admin.local-access.environment=LOCAL_FIXTURE",
    "sequo.admin.local-access.user-id=00000000-0000-0000-0000-000000000061",
    "sequo.admin.local-fixtures.enabled=true",
    "sequo.security.cors.allowed-origins=http://127.0.0.1:5173",
    "sequo.auth.jwt.secret=local_journey_signing_key_for_isolated_fixtures_only",
    "sequo.auth.jwt.issuer=sequo-api-local-admin-fixture", "sequo.auth.jwt.audience=sequo-admin-local-fixture",
    "sequo.notifications.outbox-worker.enabled=false", "sequo.notifications.provider-delivery-worker.enabled=false",
    "sequo.wallets.yas-togo.api-base-url=", "sequo.wallets.yas-togo.api-key=", "sequo.wallets.yas-togo.merchant-account-id=", "sequo.wallets.yas-togo.webhook-secret=",
    "sequo.wallets.moov-africa.api-base-url=", "sequo.wallets.moov-africa.api-key=", "sequo.wallets.moov-africa.merchant-account-id=", "sequo.wallets.moov-africa.webhook-secret=",
])
class LocalOperationalJourneySeederTest @Autowired constructor(
    private val foundation: LocalOperationalFixtureFoundation,
    private val seeder: LocalOperationalJourneySeeder,
    private val pending: PendingPaymentCheckoutRepository,
    private val orders: CustomerOrderRecordRepository,
    private val missions: DeliveryMissionRepository,
    private val parts: MerchantFulfillmentService,
    private val products: CommerceProductRepository,
    private val postings: DeliverySettlementRecordRepository,
    private val ledger: SettlementLedgerEntryRecordRepository,
    private val receipts: PaymentWebhookEventRepository,
    private val webhooks: PaymentWebhookService,
    private val jdbc: JdbcTemplate,
) {
    private val admin = "00000000-0000-0000-0000-000000000061"

    @Test
    fun staticJourneysUseNormalReconciliationProofSettlementAndPreserveLaterUserEdits() {
        val at = Instant.now()
        jdbc.update("insert into users (id,email,display_name,is_active,provider,status,created_at,updated_at) values (?,?,?,?,?,?,?,?)",
            admin, "journey-admin@fixture.invalid", "Local fixture admin", true, "EMAIL", "ACTIVE", Timestamp.from(at), Timestamp.from(at))
        jdbc.update("insert into user_roles (user_id,role_code) values (?,?)", admin, "SUPER_ADMIN")
        val context = LocalOperationalFixtureContext(admin, at)
        foundation.seed(context)
        val scenes = seeder.seed(context)
        assertEquals(18, scenes.size)
        assertTrue(scenes.all { it.summary.contains("LOCAL_FIXTURE") })
        assertEquals(PendingPaymentCheckoutStatus.AWAITING_WEBHOOK, pending.findById(context.id("payment-pending")).orElseThrow().status)
        assertEquals(PendingPaymentCheckoutStatus.FAILED, pending.findById(context.id("payment-failed")).orElseThrow().status)
        assertNull(orders.findByCheckoutId(context.id("payment-pending")))
        assertNull(orders.findByCheckoutId(context.id("payment-failed")))
        val paid = orders.findByCheckoutId(context.id("finance-independent"))!!
        assertEquals(PaymentValidationStatus.Validated, paid.paymentStatus)
        assertEquals(CustomerOrderStatus.DELIVERED, paid.orderStatus)
        assertNotNull(paid.deliveredAt)
        assertNotNull(paid.returnWindowEndsAt)
        val independent = missions.findByOrderIdOrderByCreatedAtAsc(paid.id).single()
        assertEquals(DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER, independent.status)
        assertEquals(DeliveryWorkforceType.Freelancer, independent.assignmentWorkforceType)
        assertNotNull(independent.pickupProofActorId)
        assertNotNull(independent.dropoffProofActorId)
        assertEquals(DeliverySettlementPostingStatus.POSTED, postings.findById(independent.id!!).orElseThrow().status)
        assertEquals(paid.deliveryFeeCfa, postings.findById(independent.id!!).orElseThrow().courierPayableCfa)
        val salariedOrder = orders.findByCheckoutId(context.id("finance-salaried"))!!
        val salaried = missions.findByOrderIdOrderByCreatedAtAsc(salariedOrder.id).single()
        assertEquals(DeliveryWorkforceType.SequoSalaried, salaried.assignmentWorkforceType)
        assertEquals(0, postings.findById(salaried.id!!).orElseThrow().courierPayableCfa)
        for (id in context.returnOrderIds.values) assertEquals(CustomerOrderStatus.DELIVERED, orders.findById(id).orElseThrow().orderStatus)
        val busyOrder = orders.findByCheckoutId(context.id("dispatch-busy"))!!
        val busy = missions.findByOrderIdOrderByCreatedAtAsc(busyOrder.id).single()
        assertEquals(context.courierIds[2], busy.courierId)
        assertEquals(DeliveryMissionRecordStatus.OFFERED_TO_COURIER, busy.status)
        assertEquals(0L, missions.countByCourierIdAndStatusIn(context.courierIds[0], setOf(DeliveryMissionRecordStatus.CREATED,
            DeliveryMissionRecordStatus.OFFERED_TO_COURIER, DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER, DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER)))
        val verifiedReceipt = receipts.findByProviderAndEventId("yas_togo", "LOCAL_FIXTURE:${context.id("finance-independent")}")!!
        assertEquals(PaymentWebhookReconciliationStatus.FULFILLED, verifiedReceipt.reconciliationStatus)
        assertTrue(verifiedReceipt.providerReference!!.startsWith("LOCAL_FIXTURE:"))
        assertEquals(MerchantSubOrderStatus.HANDED_TO_COURIER, parts.listForOrder(paid.id).single().status)
        val group = scenes.single { it.id == context.id("group-final") }
        assertNotNull(group.resourceIds["manifestId"])
        assertEquals(3, missions.findByOrderIdOrderByCreatedAtAsc(group.resourceIds.getValue("orderId")).size)

        val counts = listOf(pending.count(), orders.count(), missions.count(), receipts.count(), ledger.count(), postings.count())
        val merchantOrder = orders.findByCheckoutId(context.id("merchant-new"))!!
        val part = parts.listForOrder(merchantOrder.id).single()
        assertIs<MerchantFulfillmentServiceResult.Success>(parts.reject(part.id, part.merchantId, "Fixture user rejected this order"))
        products.findById(context.generalProductId).orElseThrow().also { it.name = "Edited by local tester"; products.save(it) }
        seeder.seed(context)
        assertEquals(counts, listOf(pending.count(), orders.count(), missions.count(), receipts.count(), ledger.count(), postings.count()))
        assertEquals(MerchantSubOrderStatus.REJECTED, parts.get(part.id)!!.status)
        assertEquals("Edited by local tester", products.findById(context.generalProductId).orElseThrow().name)
        assertEquals(paid.deliveredAt, orders.findById(paid.id).orElseThrow().deliveredAt)
    }

    @Test
    fun localSceneBridgeDoesNotEnableOrdinaryProviderCallbackVerification() {
        val now = Instant.now()
        assertFailsWith<IllegalArgumentException> {
            webhooks.accept(PaymentWebhookCommand(SequoPaymentProviders.YasTogo, "arbitrary-callback", "not-a-fixture-checkout", "arbitrary",
                amountCfa = 1, status = PaymentWebhookProviderStatus.VALIDATED, occurredAt = now, receivedAt = now,
                signatureTimestamp = now, signature = "browser-says-success", rawPayload = "unverified payload"))
        }
        assertNull(receipts.findByProviderAndEventId("yas_togo", "arbitrary-callback"))
    }
}
