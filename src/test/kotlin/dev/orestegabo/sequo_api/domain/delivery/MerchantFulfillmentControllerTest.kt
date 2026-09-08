package dev.orestegabo.sequo_api.domain.delivery

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
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

    private fun auth(userId: String, role: RoleCode): Authentication =
        UsernamePasswordAuthenticationToken(userId, null, listOf(role.toGrantedAuthority()))

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> org.springframework.http.ResponseEntity<Any>.bodyAs(): T =
        body as T
}
