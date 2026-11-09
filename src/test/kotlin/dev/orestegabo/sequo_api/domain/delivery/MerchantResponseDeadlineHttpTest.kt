package dev.orestegabo.sequo_api.domain.delivery

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.notification.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.returns.ReturnJourneyFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:merchant_response_deadlines;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "sequo.merchant-fulfillment.response-deadline.scheduler.enabled=false",
])
class MerchantResponseDeadlineHttpTest @Autowired constructor(
    private val service: MerchantFulfillmentService,
    private val deadlines: MerchantResponseDeadlineService,
    private val parts: MerchantSubOrderRepository,
    private val orders: CustomerOrderRecordRepository,
    private val events: CustomerOrderEventRecordRepository,
    private val users: UserRepository,
    private val memberships: MerchantMembershipRepository,
    private val merchants: CommerceMerchantRepository,
    private val products: CommerceProductRepository,
    private val pending: PendingPaymentCheckoutService,
    private val pendingRows: PendingPaymentCheckoutRepository,
    private val fulfillment: OrderFulfillmentPersistenceService,
    private val auth: AuthService,
    private val jwt: JwtService,
    private val worker: NotificationOutboxWorker,
    private val inbox: NotificationMessageRepository,
    private val recipients: MerchantOrderNotificationRecipients,
    private val dispatch: DeliveryReadinessDispatchService,
    private val jdbc: JdbcTemplate,
    private val limiter: InMemoryRateLimiter,
) {
    @LocalServerPort private var port = 0
    private val json = ObjectMapper()
    @BeforeEach fun resetLimiter() { limiter.resetForTests() }

    @Test fun newPaidPartsSnapshotWindowsAndAcceptanceBeforeDeadlineSurvivesLaterScans() {
        val fixture = paidOrder()
        val row = fixture.parts.single()
        assertEquals(Duration.ofMinutes(15), Duration.between(row.createdAt, row.responseDeadlineAt))
        assertEquals(Duration.ofSeconds(60), Duration.between(row.createdAt, row.alertUntilAt))
        assertEquals(row.responseDeadlineAt, row.sellerResponseDueAt)
        assertEquals("PENDING", row.responseState)
        assertNotNull(row.serverNow)
        assertEquals(row.responseDeadlineAt, service.sla(row.id)?.sellerResponseDueAt)
        assertIs<MerchantFulfillmentServiceResult.Success>(service.accept(row.id, row.merchantId, row.responseDeadlineAt!!.minusNanos(1)))
        deadlines.enforce(at = row.responseDeadlineAt.plusSeconds(1))
        assertEquals(MerchantSubOrderStatus.ACCEPTED, service.get(row.id)?.status)
        assertEquals(CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT, orders.findById(fixture.orderId).orElseThrow().orderStatus)
        assertNull(service.get(row.id)?.responseExpiredAt)
    }

    @Test fun storedFortyFiveSecondTenMinuteSnapshotsKeepTheirOriginalDeadlineAfterDefaultChange() {
        val fixture = paidOrder()
        val row = fixture.parts.single()
        val createdAt = requireNotNull(row.createdAt).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val originalAlert = createdAt.plusSeconds(45)
        val originalDeadline = createdAt.plus(Duration.ofMinutes(10))
        // Isolated test record representing an already-stored earlier-policy snapshot, not a runtime backfill.
        jdbc.update("update merchant_sub_orders set alert_until_at=?, response_deadline_at=?, seller_response_due_at=? where id=?",
            java.sql.Timestamp.from(originalAlert), java.sql.Timestamp.from(originalDeadline), java.sql.Timestamp.from(originalDeadline), row.id)
        val beforeVersion = parts.findById(row.id).orElseThrow().version
        val first = read("/api/merchant/sub-orders/${row.id}?merchantId=${row.merchantId}", fixture.owners.single())
        val repeated = service.get(row.id)!!
        assertEquals(originalAlert, Instant.parse(first["alertUntilAt"].asText()))
        assertEquals(originalDeadline, Instant.parse(first["responseDeadlineAt"].asText()))
        assertEquals(originalAlert, repeated.alertUntilAt)
        assertEquals(originalDeadline, repeated.responseDeadlineAt)
        assertEquals(originalDeadline, repeated.sellerResponseDueAt)
        assertEquals(beforeVersion, parts.findById(row.id).orElseThrow().version)
        // The new 15-minute default must not extend the existing 10-minute acceptance boundary.
        val expired = assertIs<MerchantFulfillmentServiceResult.Rejected>(service.accept(row.id, row.merchantId, originalDeadline))
        assertEquals("merchant_response_deadline_expired", expired.code)
        assertEquals(originalDeadline, service.get(row.id)?.responseExpiredAt)
        assertEquals(originalAlert, service.get(row.id)?.alertUntilAt)
        assertEquals(originalDeadline, service.get(row.id)?.responseDeadlineAt)
    }

    @Test fun acceptanceAtDeadlineExpiresOnceAndCustomerSeesCancellationWithoutARefund() {
        val fixture = paidOrder()
        val row = fixture.parts.single()
        val at = row.responseDeadlineAt!!
        val refused = assertIs<MerchantFulfillmentServiceResult.Rejected>(service.accept(row.id, row.merchantId, at))
        assertEquals("merchant_response_deadline_expired", refused.code)
        val stored = orders.findById(fixture.orderId).orElseThrow()
        assertEquals(CustomerOrderStatus.CANCELLED, stored.orderStatus)
        assertEquals(PaymentValidationStatus.Validated, stored.paymentStatus)
        assertEquals(PendingPaymentCheckoutStatus.COMPLETED, pendingRows.findById(stored.checkoutId).orElseThrow().status)
        val expired = requireNotNull(service.get(row.id))
        assertEquals(MerchantSubOrderStatus.CANCELLED, expired.status)
        assertEquals("EXPIRED", expired.responseState)
        assertEquals("ORDER_CANCELLED", expired.responseExpiryDisposition)
        assertEquals(at, expired.responseExpiredAt)
        val read = read("/api/orders/${fixture.orderId}", fixture.customer)
        assertEquals("CANCELLED", read["order"]["orderStatus"].asText())
        assertEquals("Validated", read["order"]["paymentStatus"].asText())
        assertTrue(read["order"]["customerFacingStatus"].asText().contains("no refund"))
        assertTrue(read["timeline"].any { it["eventType"].asText() == "MERCHANT_RESPONSE_EXPIRED" })
        deadlines.enforce(at = at.plusSeconds(5))
        service.accept(row.id, row.merchantId, at.plusSeconds(5))
        assertEquals(at, service.get(row.id)?.responseExpiredAt)
        assertEquals(1, events.findByOrderIdOrderByCreatedAtAsc(fixture.orderId).count { it.eventType == CustomerOrderEventType.MERCHANT_RESPONSE_EXPIRED })
    }

    @Test fun legacyUntimedAndAlreadyRejectedPartsNeverGetRetroactiveAutoCancellation() {
        val legacy = paidOrder().parts.single()
        jdbc.update("update merchant_sub_orders set response_deadline_at=null, alert_until_at=null, seller_response_due_at=? where id=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(86400)), legacy.id)
        val rejected = paidOrder().parts.single()
        assertIs<MerchantFulfillmentServiceResult.Success>(service.reject(rejected.id, rejected.merchantId, "Unavailable", rejected.responseDeadlineAt!!.minusSeconds(1)))
        deadlines.enforce(at = Instant.now().plusSeconds(864000))
        assertEquals("LEGACY_UNTIMED", service.get(legacy.id)?.responseState)
        assertEquals(MerchantSubOrderStatus.MERCHANT_PENDING, service.get(legacy.id)?.status)
        assertIs<MerchantFulfillmentServiceResult.Success>(service.accept(legacy.id, legacy.merchantId, Instant.now().plusSeconds(864001)))
        assertEquals(MerchantSubOrderStatus.REJECTED, service.get(rejected.id)?.status)
        assertNull(service.get(rejected.id)?.responseExpiredAt)
    }

    @Test fun repairingAnOldPaidOrderDoesNotOptItsNewPartIntoAutomaticCancellation() {
        val fixture = paidOrder()
        val existing = fixture.parts.single()
        val repair = service.create(CreateMerchantSubOrderCommand("repair-${UUID.randomUUID()}", fixture.orderId,
            existing.merchantId, 3000), Instant.now())
        assertNull(repair.responseDeadlineAt)
        assertNull(repair.alertUntilAt)
        assertEquals("LEGACY_UNTIMED", repair.responseState)
    }

    @Test fun fullyUnconfirmedGroupedOrderCancelsAllPendingPartsButKeepsPaidEvidence() {
        val fixture = paidOrder(2)
        val due = fixture.parts.maxOf { it.responseDeadlineAt!! }
        deadlines.enforce(at = due)
        assertEquals(CustomerOrderStatus.CANCELLED, orders.findById(fixture.orderId).orElseThrow().orderStatus)
        assertEquals(PaymentValidationStatus.Validated, orders.findById(fixture.orderId).orElseThrow().paymentStatus)
        assertTrue(service.listForOrder(fixture.orderId).all { it.status == MerchantSubOrderStatus.CANCELLED })
        assertEquals(1, events.findByOrderIdOrderByCreatedAtAsc(fixture.orderId).count { it.eventType == CustomerOrderEventType.MERCHANT_RESPONSE_EXPIRED })
        deadlines.enforce(at = due.plusSeconds(30))
        assertEquals(1, events.findByOrderIdOrderByCreatedAtAsc(fixture.orderId).count { it.eventType == CustomerOrderEventType.MERCHANT_RESPONSE_EXPIRED })
    }

    @Test fun partiallyAcceptedOrderPreservesConfirmedWorkAndRequiresOperationsBeforeDispatch() {
        val fixture = paidOrder(2, route = OrderRoute.FastDelivery)
        val confirmed = fixture.parts[0]
        val missed = fixture.parts[1]
        val due = fixture.parts.maxOf { it.responseDeadlineAt!! }
        service.accept(confirmed.id, confirmed.merchantId, due.minusSeconds(1))
        service.markPacked(confirmed.id, confirmed.merchantId, 1, due.minusSeconds(1))
        deadlines.enforce(at = due)
        assertEquals(MerchantSubOrderStatus.PACKED_READY, service.get(confirmed.id)?.status)
        assertEquals("OPERATIONS_REVIEW_REQUIRED", service.get(missed.id)?.responseExpiryDisposition)
        val order = orders.findById(fixture.orderId).orElseThrow()
        assertEquals(CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT, order.orderStatus)
        assertEquals(PaymentValidationStatus.Validated, order.paymentStatus)
        assertTrue(order.customerFacingStatus.contains("SEQUO review"))
        val run = dispatch.dispatchReadySubOrders()
        assertTrue(run.createdMissions.none { it.orderId == fixture.orderId })
        assertTrue(run.skippedSubOrders.any { it.orderId == fixture.orderId && it.reason == "merchant_response_expired_operations_review_required" })
    }

    @Test fun acceptAndTimeoutSerializeOnTheSameParentWithoutRevivingAnExpiredOrder() {
        val fixture = paidOrder()
        val row = fixture.parts.single()
        val due = row.responseDeadlineAt!!
        val gate = CountDownLatch(1)
        val acceptance = CompletableFuture.supplyAsync { gate.await(); service.accept(row.id, row.merchantId, due.minusNanos(1)) }
        val expiry = CompletableFuture.supplyAsync { gate.await(); deadlines.enforce(at = due) }
        gate.countDown()
        val accepted = acceptance.join()
        expiry.join()
        val final = service.get(row.id)!!
        when (accepted) {
            is MerchantFulfillmentServiceResult.Success -> {
                assertEquals(MerchantSubOrderStatus.ACCEPTED, final.status)
                assertNull(final.responseExpiredAt)
                assertEquals(CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT, orders.findById(fixture.orderId).orElseThrow().orderStatus)
            }
            is MerchantFulfillmentServiceResult.Rejected -> {
                assertEquals("merchant_response_deadline_expired", accepted.code)
                assertEquals("EXPIRED", final.responseState)
                assertEquals(CustomerOrderStatus.CANCELLED, orders.findById(fixture.orderId).orElseThrow().orderStatus)
            }
        }
        assertEquals(PaymentValidationStatus.Validated, orders.findById(fixture.orderId).orElseThrow().paymentStatus)
    }

    @Test fun merchantScopeAndManualEnforcementRemainServerControlled() {
        val own = paidOrder()
        val foreign = paidOrder()
        val row = own.parts.single()
        val body = json.writeValueAsString(mapOf("merchantId" to row.merchantId))
        assertEquals(403, call("POST", "/api/merchant/sub-orders/${row.id}/accept", foreign.owners.single(), body).statusCode())
        assertEquals(403, call("POST", "/api/merchant/sub-orders/response-deadlines/enforce", own.customer, "{}").statusCode())
        assertEquals(403, call("POST", "/api/merchant/sub-orders/response-deadlines/enforce", own.owners.single(), "{}").statusCode())
        assertEquals(MerchantSubOrderStatus.MERCHANT_PENDING, service.get(row.id)?.status)
        val admin = users.save(User(email="response-admin-${UUID.randomUUID()}@fixture.invalid", roles=mutableSetOf(RoleCode.ADMIN)))
        val response = call("POST", "/api/merchant/sub-orders/response-deadlines/enforce", admin,
            "{\"limit\":100,\"evaluatedAt\":\"2999-01-01T00:00:00Z\"}")
        assertTrue(response.statusCode() in setOf(200,400))
        assertEquals(MerchantSubOrderStatus.MERCHANT_PENDING, service.get(row.id)?.status)
        jdbc.update("update merchant_sub_orders set response_deadline_at=? where id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), row.id)
        val expiredAccept = call("POST", "/api/merchant/sub-orders/${row.id}/accept", own.owners.single(), body)
        assertEquals(400, expiredAccept.statusCode())
        assertEquals("merchant_response_deadline_expired", json.readTree(expiredAccept.body())["code"].asText())
    }

    @Test fun timelySoleMerchantRefusalCancelsParentWithCanonicalReplayAndNoRefund() {
        val fixture = paidOrder()
        val row = fixture.parts.single()
        val body = json.writeValueAsString(mapOf("merchantId" to row.merchantId, "reason" to "Cannot fulfil this order."))
        val path = "/api/merchant/sub-orders/${row.id}/reject"
        val first = call("POST", path, fixture.owners.single(), body)
        assertEquals(200, first.statusCode())
        val refused = json.readTree(first.body())
        assertEquals("REJECTED", refused["responseState"].asText())
        assertEquals("ORDER_CANCELLED", refused["responseDisposition"].asText())
        assertTrue(refused["responseExpiredAt"].isNull)
        assertTrue(refused["responseExpiryDisposition"].isNull)
        val parent = orders.findById(fixture.orderId).orElseThrow()
        assertEquals(CustomerOrderStatus.CANCELLED, parent.orderStatus)
        assertEquals(PaymentValidationStatus.Validated, parent.paymentStatus)
        assertTrue(parent.effectiveCustomerFacingStatus().contains("declined"))
        assertTrue(parent.effectiveCustomerFacingStatus().contains("no refund"))
        val audit = events.findByOrderIdOrderByCreatedAtAsc(fixture.orderId).single { it.eventType == CustomerOrderEventType.MERCHANT_RESPONSE_REJECTED }
        assertEquals(fixture.owners.single().id, audit.actorUserId)
        val repeated = call("POST", path, fixture.owners.single(), body)
        assertEquals(200, repeated.statusCode())
        assertEquals(refused["updatedAt"].asText(), json.readTree(repeated.body())["updatedAt"].asText())
        assertEquals(1, events.findByOrderIdOrderByCreatedAtAsc(fixture.orderId).count { it.eventType == CustomerOrderEventType.MERCHANT_RESPONSE_REJECTED })
        val changed = json.writeValueAsString(mapOf("merchantId" to row.merchantId, "reason" to "Changed refusal."))
        assertEquals(400, call("POST", path, fixture.owners.single(), changed).statusCode())
    }

    @Test fun partialRefusalPreservesAcceptedWorkAndBlocksFurtherReadinessDispatch() {
        val fixture = paidOrder(2, route = OrderRoute.FastDelivery)
        val confirmed = fixture.parts[0]
        val declined = fixture.parts[1]
        service.accept(confirmed.id, confirmed.merchantId)
        service.markPacked(confirmed.id, confirmed.merchantId, 1)
        assertIs<MerchantFulfillmentServiceResult.Success>(service.reject(declined.id, declined.merchantId, "Unavailable"))
        assertIs<MerchantFulfillmentServiceResult.Success>(service.reject(declined.id, declined.merchantId, "Unavailable"))
        assertEquals(MerchantSubOrderStatus.PACKED_READY, service.get(confirmed.id)?.status)
        assertEquals("REJECTED", service.get(declined.id)?.responseState)
        assertEquals("OPERATIONS_REVIEW_REQUIRED", service.get(declined.id)?.responseDisposition)
        assertNull(service.get(declined.id)?.responseExpiredAt)
        val parent = orders.findById(fixture.orderId).orElseThrow()
        assertEquals(CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT, parent.orderStatus)
        assertEquals(PaymentValidationStatus.Validated, parent.paymentStatus)
        assertTrue(parent.customerFacingStatus.contains("was refused; SEQUO review"))
        assertTrue(dispatch.dispatchReadySubOrders().createdMissions.none { it.orderId == fixture.orderId })
        assertEquals(1, events.findByOrderIdOrderByCreatedAtAsc(fixture.orderId).count { it.eventType == CustomerOrderEventType.MERCHANT_RESPONSE_REJECTED })
    }

    @Test fun concurrentAcceptAndRefusalCannotLeaveAcceptedWorkUnderAnAutoCancelledParent() {
        val fixture = paidOrder()
        val row = fixture.parts.single()
        val gate = CountDownLatch(1)
        val accept = CompletableFuture.supplyAsync { gate.await(); service.accept(row.id, row.merchantId) }
        val refuse = CompletableFuture.supplyAsync { gate.await(); service.reject(row.id, row.merchantId, "Cannot fulfil") }
        gate.countDown()
        val results = listOf(accept.join(), refuse.join())
        assertEquals(1, results.count { it is MerchantFulfillmentServiceResult.Success })
        val stored = service.get(row.id)!!
        val parent = orders.findById(fixture.orderId).orElseThrow()
        if (stored.status == MerchantSubOrderStatus.ACCEPTED) {
            assertEquals(CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT, parent.orderStatus)
            assertNull(stored.responseDisposition)
        } else {
            assertEquals(MerchantSubOrderStatus.REJECTED, stored.status)
            assertEquals(CustomerOrderStatus.CANCELLED, parent.orderStatus)
            assertEquals("ORDER_CANCELLED", stored.responseDisposition)
        }
        assertEquals(PaymentValidationStatus.Validated, parent.paymentStatus)
    }

    @Test fun differentMerchantsAcceptAndTimeoutShareTheParentLock() {
        val fixture = paidOrder(2)
        val missed = fixture.parts[0]
        val confirmed = fixture.parts[1]
        val deadline = missed.responseDeadlineAt!!
        val gate = CountDownLatch(1)
        val accept = CompletableFuture.supplyAsync { gate.await(); service.accept(confirmed.id, confirmed.merchantId, deadline.minusNanos(1)) }
        val expire = CompletableFuture.supplyAsync { gate.await(); deadlines.enforce(at = deadline) }
        gate.countDown()
        val result = accept.join(); expire.join()
        val parent = orders.findById(fixture.orderId).orElseThrow()
        if (result is MerchantFulfillmentServiceResult.Success) {
            assertEquals(MerchantSubOrderStatus.ACCEPTED, service.get(confirmed.id)?.status)
            assertEquals(CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT, parent.orderStatus)
            assertEquals("OPERATIONS_REVIEW_REQUIRED", service.get(missed.id)?.responseDisposition)
        } else {
            assertEquals(CustomerOrderStatus.CANCELLED, parent.orderStatus)
            assertTrue(service.listForOrder(fixture.orderId).all { it.status == MerchantSubOrderStatus.CANCELLED })
        }
        assertEquals(PaymentValidationStatus.Validated, parent.paymentStatus)
    }

    @Test fun closedParentCannotStarveAnActiveDueOrderAtTheScanLimit() {
        val closed = paidOrder()
        val active = paidOrder()
        orders.findById(closed.orderId).orElseThrow().also { it.orderStatus = CustomerOrderStatus.CANCELLED; orders.save(it) }
        val at = Instant.now()
        jdbc.update("update merchant_sub_orders set response_deadline_at=? where id=?", java.sql.Timestamp.from(at.minusSeconds(2)), closed.parts.single().id)
        jdbc.update("update merchant_sub_orders set response_deadline_at=? where id=?", java.sql.Timestamp.from(at.minusSeconds(1)), active.parts.single().id)
        val run = deadlines.enforce(limit=1,at=at)
        assertEquals(listOf(active.parts.single().id),run.expired.map { it.subOrderId })
        assertNull(service.get(closed.parts.single().id)?.responseExpiredAt)
        assertEquals(CustomerOrderStatus.CANCELLED,orders.findById(active.orderId).orElseThrow().orderStatus)
    }

    @Test fun aRefusalAtTheDeadlineRecordsExpiryRatherThanBackdatingAResponse() {
        val fixture = paidOrder()
        val row = fixture.parts.single()
        val result = assertIs<MerchantFulfillmentServiceResult.Rejected>(service.reject(row.id,row.merchantId,"Too late",row.responseDeadlineAt!!))
        assertEquals("merchant_response_deadline_expired",result.code)
        assertEquals("EXPIRED",service.get(row.id)?.responseState)
        assertEquals(1,events.findByOrderIdOrderByCreatedAtAsc(fixture.orderId).count { it.eventType == CustomerOrderEventType.MERCHANT_RESPONSE_EXPIRED })
        assertTrue(events.findByOrderIdOrderByCreatedAtAsc(fixture.orderId).none { it.eventType == CustomerOrderEventType.MERCHANT_RESPONSE_REJECTED })
    }

    @Test fun newPaidOrderInboxTargetsActualActiveMerchantUsersAndNeverOtherMerchant() {
        val activeStaff = users.save(User(email="response-staff-${UUID.randomUUID()}@fixture.invalid"))
        val inactiveStaff = users.save(User(email="response-inactive-${UUID.randomUUID()}@fixture.invalid", active=false))
        val inactiveMembership = users.save(User(email="response-unbound-${UUID.randomUUID()}@fixture.invalid"))
        val fixture = paidOrder(2, beforeOrder = { merchantIds ->
            memberships.save(MerchantMembership(userId=activeStaff.id!!, merchantId=merchantIds[0], role=RoleCode.MERCHANT_STAFF))
            memberships.save(MerchantMembership(userId=inactiveStaff.id!!, merchantId=merchantIds[0], role=RoleCode.MERCHANT_STAFF))
            memberships.save(MerchantMembership(userId=inactiveMembership.id!!, merchantId=merchantIds[0], role=RoleCode.MERCHANT_STAFF,active=false))
        })
        val first = fixture.parts[0]
        val second = fixture.parts[1]
        assertTrue(fixture.owners[0].id in recipients.users(first.merchantId))
        assertTrue(activeStaff.id in recipients.users(first.merchantId))
        assertFalse(inactiveStaff.id in recipients.users(first.merchantId))
        assertFalse(inactiveMembership.id in recipients.users(first.merchantId))
        assertFalse(fixture.owners[1].id in recipients.users(first.merchantId))
        val dispatched = List(5) { worker.dispatchReady("deadline-inbox-fixture-$it", 20) }
        assertTrue(dispatched.all { run -> run.failedEventIds.none { it in setOf("${first.id}:incoming-paid:0", "${second.id}:incoming-paid:0") } })
        val yawa = read("/api/notifications/inbox", fixture.owners[0])
        val mawuli = read("/api/notifications/inbox", fixture.owners[1])
        assertTrue(yawa.any { it["eventId"].asText() == "${first.id}:incoming-paid:0" })
        assertTrue(yawa.none { it["eventId"].asText() == "${second.id}:incoming-paid:0" })
        assertTrue(mawuli.any { it["eventId"].asText() == "${second.id}:incoming-paid:0" })
        assertTrue(read("/api/notifications/inbox", activeStaff).any { it["eventId"].asText() == "${first.id}:incoming-paid:0" })
        assertTrue(inbox.findByRecipientUserIdOrderByCreatedAtDesc(inactiveStaff.id!!).isEmpty())
        assertTrue(read("/api/notifications/inbox", inactiveMembership).none { it["eventId"].asText() == "${first.id}:incoming-paid:0" })
        val message = yawa.first { it["eventId"].asText() == "${first.id}:incoming-paid:0" }
        assertEquals(404, call("PATCH", "/api/notifications/inbox/${message["id"].asText()}/read", fixture.owners[1]).statusCode())
        worker.dispatchReady("deadline-inbox-fixture-repeat",20)
        assertEquals(1, read("/api/notifications/inbox", fixture.owners[0]).count { it["eventId"].asText() == "${first.id}:incoming-paid:0" })
    }

    private data class Fixture(val customer: User, val owners: List<User>, val orderId: String, val parts: List<MerchantSubOrderSnapshot>)
    private fun paidOrder(count: Int = 1, route: OrderRoute = if(count > 1) OrderRoute.GroupedSequo else OrderRoute.FastDelivery,
        beforeOrder: ((List<String>) -> Unit)? = null): Fixture {
        val id = UUID.randomUUID().toString()
        val customer = users.save(User(email="response-customer-$id@fixture.invalid"))
        val owners = (0 until count).map { users.save(User(email="response-owner-$id-$it@fixture.invalid", roles=mutableSetOf(RoleCode.MERCHANT_OWNER))) }
        val lines = owners.mapIndexed { index, owner ->
            val merchantId="response-merchant-$id-$index"
            val productId="response-product-$id-$index"
            merchants.save(MerchantRecord(merchantId, owner.id, name="Response fixture merchant $index"))
            memberships.save(MerchantMembership(userId=owner.id!!, merchantId=merchantId, role=RoleCode.MERCHANT_OWNER))
            products.save(ProductRecord(productId, merchantId, name="Response fixture item",kind=CatalogProductKind.GenericSealedItem,basePriceCfa=3000,category="general"))
            OrderLineRequest(productId,merchantId,"Response fixture merchant","Response fixture item",OrderProductCategory.GenericSealedItem,1,3000,photoEvidence=ProductPhotoEvidence.GenericCatalogImage)
        }
        beforeOrder?.invoke(lines.map { it.sellerId })
        val checkout="response-checkout-$id"
        val request=OrderProcessingRequest(checkout,customer.id!!,OrderServiceLevel.Regular,route,lines,1.0,
            paymentProvider=SequoPaymentProviders.YasTogo,paymentReference=checkout)
        val processor=RegularOrderProcessor(PaymentProcessor(listOf(YasTogoPaymentMethod(validateHandler={PaymentValidationResult.validated(it.paymentReference)}))),DeliveryPricingService())
        val accepted=processor.process(request) as OrderProcessingResult.AcceptedForFulfillment
        pending.recordCartCheckout(request,accepted.order.pricing,PendingPaymentCheckoutStatus.COMPLETED,Instant.now())
        val stored=fulfillment.persistAcceptedOrder(request,accepted)
        return Fixture(customer,owners,stored.order.id,service.listForOrder(stored.order.id).sortedBy { it.merchantId })
    }
    private fun token(user: User)=jwt.generateAccessToken(auth.accountSession(users.findById(user.id!!).orElseThrow()))
    private fun call(method: String,path: String,user: User,body: String?=null)=ReturnJourneyFixtures.request(port,method,path,token(user),body)
    private fun read(path: String,user: User)=call("GET",path,user).let {
        assertEquals(200,it.statusCode(),"Read failed: $path");json.readTree(it.body())
    }
}
