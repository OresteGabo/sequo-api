package dev.orestegabo.sequo_api.domain.delivery

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class MerchantFulfillmentSlaTest @Autowired constructor(
    private val service: MerchantFulfillmentService,
) {
    @Test
    fun pendingSellerOrderBecomesOverdueAfterResponseDeadline() {
        val order = service.create(command("SLA-RESPONSE"))
        val sellerResponseDueAt = requireNotNull(order.sellerResponseDueAt)
        val before = service.sla(order.id, sellerResponseDueAt.minusSeconds(1))
        val after = requireNotNull(service.sla(order.id, sellerResponseDueAt.plusSeconds(1)))

        assertFalse(before?.overdue ?: true)
        assertTrue(after.overdue)
        assertEquals("seller_response_sla_exceeded", after.overdueReason)
        assertNull(service.sla("missing-sub-order"))
    }

    @Test
    fun acceptedOrderUsesPackingDeadlineAndCompletedOrderIsNotOverdue() {
        val order = service.create(command("SLA-PACKING"))
        val packingDueAt = requireNotNull(order.packingDueAt)
        service.accept(order.id, "merchant-1")
        val overdue = requireNotNull(service.sla(order.id, packingDueAt.plus(Duration.ofSeconds(1))))
        service.startPreparation(order.id, "merchant-1")
        service.markPacked(order.id, "merchant-1", packageCount = 1)
        val ready = service.sla(order.id, packingDueAt.plus(Duration.ofDays(3)))

        assertTrue(overdue.overdue)
        assertEquals("packing_sla_exceeded", overdue.overdueReason)
        assertFalse(ready?.overdue ?: true)
    }

    private fun command(code: String) = CreateMerchantSubOrderCommand(
        subOrderCode = code,
        orderId = "order-sla",
        merchantId = "merchant-1",
        itemSubtotalCfa = 10_000,
        commissionCfa = 1_500,
        merchantNetCfa = 8_500,
    )
}
