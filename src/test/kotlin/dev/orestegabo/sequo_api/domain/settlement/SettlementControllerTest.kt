package dev.orestegabo.sequo_api.domain.settlement

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.JwtAuthenticationDetails
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class SettlementControllerTest @Autowired constructor(
    private val controller: SettlementController,
    private val service: SettlementPersistenceService,
) {
    private val receivedAt = Instant.parse("2026-09-08T09:00:00Z")

    @Test
    fun `merchant can read own payouts but not another merchant payouts`() {
        seedPayout("settlement-ctrl-own", "merchant-settlement-1", activeReturnHold = false)

        val owner = auth("merchant-settlement-1", RoleCode.MERCHANT_OWNER)
        val otherMerchant = auth("merchant-settlement-2", RoleCode.MERCHANT_OWNER)
        val customer = auth("customer-settlement", RoleCode.CUSTOMER)

        val own = controller.merchantPayouts(owner, "merchant-settlement-1", null)
        val crossMerchant = controller.merchantPayouts(otherMerchant, "merchant-settlement-1", null)
        val customerRead = controller.merchantPayouts(customer, "merchant-settlement-1", null)

        assertEquals(HttpStatus.OK, own.statusCode)
        assertEquals(listOf("settlement-ctrl-own"), own.bodyAs<List<MerchantPayoutResponse>>().map { it.id })
        assertEquals(HttpStatus.FORBIDDEN, crossMerchant.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, customerRead.statusCode)
    }

    @Test
    fun `admin can filter payouts, inspect ledger, and evaluate eligible payouts`() {
        seedPayout("settlement-ctrl-admin-1", "merchant-settlement-admin", activeReturnHold = false)
        seedPayout("settlement-ctrl-admin-held", "merchant-settlement-admin", activeReturnHold = true)
        val admin = auth("admin-settlement", RoleCode.ADMIN)

        val filtered = controller.merchantPayouts(admin, "merchant-settlement-admin", MerchantPayoutStatus.Accrued)
        val ledger = controller.ledgerEntries(admin, SettlementSourceType.OrderItem, "settlement-ctrl-admin-1-item")
        val promoted = controller.evaluateEligible(admin, receivedAt.plusSeconds(1))

        assertEquals(HttpStatus.OK, filtered.statusCode)
        assertEquals(listOf("settlement-ctrl-admin-1"), filtered.bodyAs<List<MerchantPayoutResponse>>().map { it.id })
        assertEquals(3, ledger.bodyAs<List<SettlementLedgerEntryResponse>>().size)
        assertTrue(promoted.bodyAs<List<MerchantPayoutResponse>>().any { it.id == "settlement-ctrl-admin-1" })
    }

    @Test
    fun `merchant staff can read payouts for scoped merchant membership`() {
        seedPayout("settlement-ctrl-scoped", "merchant-settlement-scoped", activeReturnHold = false)
        val scopedStaff = auth("staff-settlement-1", RoleCode.MERCHANT_STAFF, merchantScopes = setOf("merchant-settlement-scoped"))
        val unscopedStaff = auth("staff-settlement-2", RoleCode.MERCHANT_STAFF, merchantScopes = setOf("merchant-other"))

        val scoped = controller.merchantPayouts(scopedStaff, "merchant-settlement-scoped", null)
        val unscoped = controller.merchantPayouts(unscopedStaff, "merchant-settlement-scoped", null)

        assertEquals(HttpStatus.OK, scoped.statusCode)
        assertEquals(listOf("settlement-ctrl-scoped"), scoped.bodyAs<List<MerchantPayoutResponse>>().map { it.id })
        assertEquals(HttpStatus.FORBIDDEN, unscoped.statusCode)
    }

    private fun seedPayout(
        id: String,
        merchantId: String,
        activeReturnHold: Boolean,
    ) {
        service.accrueMerchantPayout(
            MerchantPayoutAccrualCommand(
                accrualId = id,
                merchantId = merchantId,
                orderId = "$id-order",
                sourceOrderItemId = "$id-item",
                merchantNetCfa = 4_000,
                commissionCfa = 500,
                platformMarginCfa = 150,
                packageReceivedAt = receivedAt,
                workflowType = SettlementWorkflowType.DeliveryConfirmed,
                activeReturnHold = activeReturnHold,
            )
        )
    }

    private fun auth(
        userId: String,
        role: RoleCode,
        merchantScopes: Set<String> = emptySet(),
    ): Authentication =
        UsernamePasswordAuthenticationToken(userId, null, listOf(role.toGrantedAuthority())).apply {
            details = JwtAuthenticationDetails(webAuthenticationDetails = null, sessionId = null, merchantScopeIds = merchantScopes)
        }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> org.springframework.http.ResponseEntity<Any>.bodyAs(): T =
        body as T
}
