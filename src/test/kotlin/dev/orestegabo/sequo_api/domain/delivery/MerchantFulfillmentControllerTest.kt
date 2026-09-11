package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.JwtAuthenticationDetails
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecord
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.order.FulfillmentPriority
import dev.orestegabo.sequo_api.domain.order.OrderRoute
import dev.orestegabo.sequo_api.domain.order.OrderServiceLevel
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class MerchantFulfillmentControllerTest @Autowired constructor(
    private val controller: MerchantFulfillmentController,
    private val orders: CustomerOrderRecordRepository,
) {
    @Test
    fun merchantWorkflowIsExposedAndScopedToTheOwningMerchant() {
        val admin = auth("admin-merchant", RoleCode.ADMIN)
        val merchant = auth("merchant-controller-1", RoleCode.MERCHANT_OWNER)
        val otherMerchant = auth("merchant-controller-2", RoleCode.MERCHANT_OWNER)
        val customer = auth("customer-controller", RoleCode.CUSTOMER)

        val created = controller.create(admin, command("CTRL-SUB-ORDER"))
        val subOrder = created.bodyAs<MerchantSubOrderSnapshot>()

        val ownList = controller.list(merchant, merchantId = "merchant-controller-1", status = null)
        val otherList = controller.list(otherMerchant, merchantId = "merchant-controller-1", status = null)
        val otherAccept = controller.accept(
            otherMerchant,
            subOrder.id,
            MerchantFulfillmentController.MerchantActionRequest(merchantId = "merchant-controller-1"),
        )
        val customerAccept = controller.accept(
            customer,
            subOrder.id,
            MerchantFulfillmentController.MerchantActionRequest(merchantId = "merchant-controller-1"),
        )

        assertEquals(HttpStatus.OK, ownList.statusCode)
        assertEquals(listOf(subOrder.id), ownList.bodyAs<List<MerchantSubOrderSnapshot>>().map { it.id })
        assertEquals(HttpStatus.FORBIDDEN, otherList.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, otherAccept.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, customerAccept.statusCode)

        controller.accept(
            merchant,
            subOrder.id,
            MerchantFulfillmentController.MerchantActionRequest(merchantId = "merchant-controller-1"),
        )
        controller.startPreparation(
            merchant,
            subOrder.id,
            MerchantFulfillmentController.MerchantActionRequest(merchantId = "merchant-controller-1"),
        )
        controller.markPacked(
            merchant,
            subOrder.id,
            MerchantFulfillmentController.MarkPackedRequest(merchantId = "merchant-controller-1", packageCount = 2),
        )
        val handedOff = controller.handoff(
            merchant,
            subOrder.id,
            MerchantFulfillmentController.MerchantActionRequest(merchantId = "merchant-controller-1"),
        )

        assertEquals(HttpStatus.OK, handedOff.statusCode)
        assertEquals(MerchantSubOrderStatus.HANDED_TO_COURIER, handedOff.bodyAs<MerchantSubOrderSnapshot>().status)
        assertEquals(HttpStatus.OK, controller.sla(merchant, subOrder.id, merchantId = null).statusCode)
        assertEquals(HttpStatus.OK, controller.get(admin, subOrder.id, merchantId = null).statusCode)
    }

    @Test
    fun adminCanPublishOverdueSlaWarnings() {
        val admin = auth("admin-sla-warning", RoleCode.ADMIN)
        orders.save(order("order-CTRL-SLA-WARNING"))
        val subOrder = controller.create(admin, command("CTRL-SLA-WARNING")).bodyAs<MerchantSubOrderSnapshot>()
        val overdueAt = requireNotNull(subOrder.sellerResponseDueAt).plusSeconds(1)

        val response = controller.publishOverdueSlaWarnings(
            admin,
            MerchantFulfillmentController.PublishSlaWarningsRequest(evaluatedAt = overdueAt, limit = 20),
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        response.bodyAs<List<MerchantFulfillmentSlaWarning>>().single().also {
            assertEquals(subOrder.id, it.subOrder.id)
            assertEquals("seller_response_sla_exceeded", it.overdueReason)
        }
    }

    @Test
    fun adminCanCreateAndReadMerchantFulfillmentEscalations() {
        val admin = auth("admin-escalation", RoleCode.ADMIN)
        val support = auth("support-escalation-admin", RoleCode.SUPPORT_AGENT)
        val merchant = auth("merchant-controller-1", RoleCode.MERCHANT_OWNER)
        val subOrder = controller.create(admin, command("CTRL-ESCALATION")).bodyAs<MerchantSubOrderSnapshot>()

        val escalation = controller.escalate(
            support,
            subOrder.id,
            MerchantFulfillmentController.EscalateRequest(
                reason = MerchantFulfillmentEscalationReason.MANUAL_SUPPORT_REVIEW,
                note = "Seller called support and needs manual follow-up.",
            ),
        )
        val merchantRead = controller.escalations(merchant, subOrder.id)
        val supportRead = controller.escalations(support, subOrder.id)

        assertEquals(HttpStatus.OK, escalation.statusCode)
        escalation.bodyAs<MerchantFulfillmentEscalationSnapshot>().also {
            assertEquals(subOrder.id, it.subOrderId)
            assertEquals("support-escalation-admin", it.actorUserId)
            assertEquals(MerchantFulfillmentEscalationReason.MANUAL_SUPPORT_REVIEW, it.reason)
        }
        assertEquals(HttpStatus.FORBIDDEN, merchantRead.statusCode)
        assertEquals(HttpStatus.OK, supportRead.statusCode)
        assertEquals(
            listOf(MerchantFulfillmentEscalationReason.MANUAL_SUPPORT_REVIEW),
            supportRead.bodyAs<List<MerchantFulfillmentEscalationSnapshot>>().map { it.reason },
        )
    }

    @Test
    fun merchantStaffCanUsePersistedMerchantScopeFromJwtDetails() {
        val admin = auth("admin-merchant-scope", RoleCode.ADMIN)
        val scopedStaff = auth("staff-controller-1", RoleCode.MERCHANT_STAFF, merchantScopes = setOf("merchant-controller-1"))
        val unscopedStaff = auth("staff-controller-2", RoleCode.MERCHANT_STAFF, merchantScopes = setOf("merchant-controller-2"))
        val subOrder = controller.create(admin, command("CTRL-MERCHANT-SCOPE")).bodyAs<MerchantSubOrderSnapshot>()

        val scopedDetail = controller.get(scopedStaff, subOrder.id, merchantId = null)
        val unscopedDetail = controller.get(unscopedStaff, subOrder.id, merchantId = null)
        val scopedAccept = controller.accept(
            scopedStaff,
            subOrder.id,
            MerchantFulfillmentController.MerchantActionRequest(merchantId = "merchant-controller-1"),
        )

        assertEquals(HttpStatus.OK, scopedDetail.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, unscopedDetail.statusCode)
        assertEquals(HttpStatus.OK, scopedAccept.statusCode)
    }

    private fun command(code: String): CreateMerchantSubOrderCommand =
        CreateMerchantSubOrderCommand(
            subOrderCode = code,
            orderId = "order-$code",
            merchantId = "merchant-controller-1",
            itemSubtotalCfa = 20_000,
            commissionRateBps = 1500,
            commissionCfa = 3_000,
            merchantNetCfa = 17_000,
        )

    private fun auth(
        userId: String,
        role: RoleCode,
        merchantScopes: Set<String> = emptySet(),
    ): Authentication =
        UsernamePasswordAuthenticationToken(userId, null, listOf(role.toGrantedAuthority())).apply {
            details = JwtAuthenticationDetails(webAuthenticationDetails = null, sessionId = null, merchantScopeIds = merchantScopes)
        }

    private fun order(orderId: String) =
        CustomerOrderRecord(
            id = orderId,
            checkoutId = "checkout-$orderId",
            customerId = "customer-$orderId",
            serviceLevel = OrderServiceLevel.Regular,
            route = OrderRoute.FastDelivery,
            fulfillmentPriority = FulfillmentPriority.Standard,
            requiresConsolidation = false,
            customerFacingStatus = "Accepted for fulfillment",
            itemSubtotalCfa = 20_000,
            deliveryFeeCfa = 400,
            totalCfa = 20_400,
            paymentProvider = "YAS_TOGO",
            paymentReference = "payment-$orderId",
            providerReference = "provider-$orderId",
            paymentStatus = PaymentValidationStatus.Validated,
            orderStatus = CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT,
            createdAt = Instant.parse("2026-09-09T10:00:00Z"),
            updatedAt = Instant.parse("2026-09-09T10:00:00Z"),
        )

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> org.springframework.http.ResponseEntity<Any>.bodyAs(): T =
        body as T
}
