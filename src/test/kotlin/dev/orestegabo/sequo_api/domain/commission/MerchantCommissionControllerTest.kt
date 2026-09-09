package dev.orestegabo.sequo_api.domain.commission

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class MerchantCommissionControllerTest @Autowired constructor(
    private val controller: MerchantCommissionController,
) {
    @Test
    fun adminCanManageMerchantCommissionOverrides() {
        val admin = auth("admin-commission-controller", RoleCode.ADMIN)

        val upsert = controller.upsert(
            admin,
            "merchant-commission-controller",
            MerchantCommissionController.UpsertMerchantCommissionRequest(
                commissionRateBps = 900,
                reason = "Negotiated pilot rate.",
            ),
        )
        val read = controller.get(admin, "merchant-commission-controller")
        val clear = controller.clear(admin, "merchant-commission-controller")
        val afterClear = controller.get(admin, "merchant-commission-controller")

        assertEquals(HttpStatus.OK, upsert.statusCode)
        upsert.bodyAs<MerchantCommissionOverrideSnapshot>().also {
            assertEquals("merchant-commission-controller", it.merchantId)
            assertEquals(900, it.commissionRateBps)
            assertEquals("admin-commission-controller", it.updatedByUserId)
        }
        assertEquals(900, read.bodyAs<MerchantCommissionController.MerchantCommissionRateResponse>().commissionRateBps)
        assertEquals(HttpStatus.NO_CONTENT, clear.statusCode)
        afterClear.bodyAs<MerchantCommissionController.MerchantCommissionRateResponse>().also {
            assertEquals(1500, it.commissionRateBps)
            assertNull(it.override)
        }
    }

    @Test
    fun merchantCannotManageCommissionOverrides() {
        val merchant = auth("merchant-commission-controller", RoleCode.MERCHANT_OWNER)

        val response = controller.upsert(
            merchant,
            "merchant-commission-controller",
            MerchantCommissionController.UpsertMerchantCommissionRequest(commissionRateBps = 900),
        )

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    private fun auth(userId: String, role: RoleCode): Authentication =
        UsernamePasswordAuthenticationToken(userId, null, listOf(role.toGrantedAuthority()))

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> org.springframework.http.ResponseEntity<Any>.bodyAs(): T =
        body as T
}
