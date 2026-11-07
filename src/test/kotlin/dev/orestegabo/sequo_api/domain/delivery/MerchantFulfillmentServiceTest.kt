package dev.orestegabo.sequo_api.domain.delivery

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:merchant_fulfillment_service;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class MerchantFulfillmentServiceTest @Autowired constructor(
    private val service: MerchantFulfillmentService,
    private val repository: MerchantSubOrderRepository,
    private val orders: CustomerOrderRecordRepository,
) {
    @BeforeTest
    fun cleanDatabase() {
        repository.deleteAll()
        if (!orders.existsById("merchant-service-order")) orders.save(paidMerchantOrderFixture("merchant-service-order"))
    }

    @Test
    fun persistsSellerAcceptPreparePackAndCourierHandoffFlow() {
        val subOrder = service.create(sampleCommand("SC-FLOW"))
        val acceptedAt = Instant.parse("2026-08-02T10:00:00Z")
        val preparingAt = Instant.parse("2026-08-02T10:05:00Z")
        val packedAt = Instant.parse("2026-08-02T10:20:00Z")
        val handedOffAt = Instant.parse("2026-08-02T10:30:00Z")

        service.accept(subOrder.id, "merchant-1", acceptedAt)
        service.startPreparation(subOrder.id, "merchant-1", preparingAt)
        service.markPacked(subOrder.id, "merchant-1", packageCount = 2, occurredAt = packedAt)
        val result = service.confirmCourierHandoff(subOrder.id, "merchant-1", handedOffAt)

        assertTrue(result is MerchantFulfillmentServiceResult.Success)
        assertEquals(MerchantSubOrderStatus.HANDED_TO_COURIER, result.subOrder.status)

        val persisted = repository.findById(subOrder.id).orElseThrow()
        assertEquals(MerchantSubOrderStatus.HANDED_TO_COURIER, persisted.status)
        assertEquals(2, persisted.packageCount)
        assertEquals(acceptedAt, persisted.acceptedAt)
        assertEquals(preparingAt, persisted.preparingAt)
        assertEquals(packedAt, persisted.packedReadyAt)
        assertEquals(handedOffAt, persisted.handedToCourierAt)
    }

    @Test
    fun rejectsWrongMerchantScopeAndLeavesStateUnchanged() {
        val subOrder = service.create(sampleCommand("SC-SCOPE"))

        val result = service.accept(subOrder.id, "merchant-2")

        assertTrue(result is MerchantFulfillmentServiceResult.Rejected)
        assertEquals("merchant_scope_mismatch", result.code)
        assertEquals(MerchantSubOrderStatus.MERCHANT_PENDING, repository.findById(subOrder.id).orElseThrow().status)
    }

    @Test
    fun rejectsPackingWithoutPackageCount() {
        val subOrder = service.create(sampleCommand("SC-PACKAGE-COUNT"))
        service.accept(subOrder.id, "merchant-1")

        val result = service.markPacked(subOrder.id, "merchant-1", packageCount = 0)

        assertTrue(result is MerchantFulfillmentServiceResult.Rejected)
        assertEquals("invalid_merchant_fulfillment_transition", result.code)
        assertEquals(MerchantSubOrderStatus.ACCEPTED, repository.findById(subOrder.id).orElseThrow().status)
    }

    @Test
    fun sellerCanRejectPendingSubOrderWithReason() {
        val subOrder = service.create(sampleCommand("SC-REJECT"))

        val result = service.reject(
            subOrderId = subOrder.id,
            merchantId = "merchant-1",
            reason = "Product is out of stock.",
        )

        assertTrue(result is MerchantFulfillmentServiceResult.Success)
        assertEquals(MerchantSubOrderStatus.REJECTED, result.subOrder.status)
        assertEquals("Product is out of stock.", result.subOrder.rejectionReason)
        assertNotNull(repository.findById(subOrder.id).orElseThrow().rejectionReason)
    }

    @Test
    fun cannotRejectAfterCourierHandoff() {
        val subOrder = service.create(sampleCommand("SC-TERMINAL"))
        service.accept(subOrder.id, "merchant-1")
        service.markPacked(subOrder.id, "merchant-1", packageCount = 1)
        service.confirmCourierHandoff(subOrder.id, "merchant-1")

        val result = service.reject(subOrder.id, "merchant-1", "Too late.")

        assertTrue(result is MerchantFulfillmentServiceResult.Rejected)
        assertEquals("invalid_merchant_fulfillment_transition", result.code)
        assertEquals(MerchantSubOrderStatus.HANDED_TO_COURIER, repository.findById(subOrder.id).orElseThrow().status)
    }

    @Test
    fun rejectsLegacyUnpaidSubOrderActionsAndKeepsItOutOfMerchantQueue() {
        orders.save(paidMerchantOrderFixture("merchant-unpaid-order", PaymentValidationStatus.Pending))
        val subOrder = service.create(sampleCommand("SC-UNPAID").copy(orderId = "merchant-unpaid-order"))
        val result = service.accept(subOrder.id, "merchant-1")
        assertTrue(result is MerchantFulfillmentServiceResult.Rejected)
        assertEquals("order_not_paid_or_active", result.code)
        assertTrue(service.listForMerchant("merchant-1").none { it.id == subOrder.id })
        assertEquals(MerchantSubOrderStatus.MERCHANT_PENDING, service.get(subOrder.id)?.status)
    }

    private fun sampleCommand(code: String): CreateMerchantSubOrderCommand =
        CreateMerchantSubOrderCommand(
            subOrderCode = code,
            orderId = "merchant-service-order",
            merchantId = "merchant-1",
            itemSubtotalCfa = 10_000,
            commissionRateBps = 1500,
            commissionCfa = 1_500,
            merchantNetCfa = 8_500,
        )
}


internal fun paidMerchantOrderFixture(id: String, paymentStatus: PaymentValidationStatus = PaymentValidationStatus.Validated) = CustomerOrderRecord(
    id = id, checkoutId = "checkout-$id", customerId = "customer-$id",
    serviceLevel = OrderServiceLevel.Regular, route = OrderRoute.FastDelivery,
    fulfillmentPriority = FulfillmentPriority.Standard, requiresConsolidation = false,
    customerFacingStatus = "Paid", itemSubtotalCfa = 10000, deliveryFeeCfa = 400, totalCfa = 10400,
    paymentProvider = "yas_togo", paymentReference = "payment-$id", providerReference = "provider-$id",
    paymentStatus = paymentStatus, orderStatus = CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT,
    createdAt = Instant.parse("2026-11-07T00:00:00Z"), updatedAt = Instant.parse("2026-11-07T00:00:00Z"),
)
