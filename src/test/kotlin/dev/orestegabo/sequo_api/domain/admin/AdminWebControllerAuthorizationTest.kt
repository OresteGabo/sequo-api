package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.authority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

class AdminWebControllerAuthorizationTest {
    private val controller = AdminWebController(FakeAdminWebReadPort)

    @Test
    fun overviewRequiresAuthentication() {
        val response = controller.overview(authentication = null, limit = 25)

        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun overviewRejectsNonAdminRoles() {
        val response = controller.overview(authentication = authentication(RoleCode.SUPPORT_AGENT), limit = 25)

        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun overviewAllowsSequoAdminRoles() {
        val response = controller.overview(authentication = authentication(RoleCode.ADMIN), limit = 25)

        assertEquals(200, response.statusCode.value())
    }

    private fun authentication(role: RoleCode): TestingAuthenticationToken =
        TestingAuthenticationToken("user-admin-test", "n/a", listOf(SimpleGrantedAuthority(role.authority)))

    private object FakeAdminWebReadPort : AdminWebReadPort {
        override fun overview(limit: Int): AdminWebOverviewDto =
            AdminWebOverviewDto(
                generatedAt = java.time.Instant.EPOCH,
                totals = AdminWebTotalsDto(
                    users = 0,
                    merchants = 0,
                    orders = 0,
                    deliveryMissions = 0,
                    merchantSubOrders = 0,
                    relayParcels = 0,
                    merchantPayouts = 0,
                    commissionOverrides = 0,
                    paymentWebhookEvents = 0,
                ),
                operationalLinks = emptyMap(),
            )

        override fun users(limit: Int): List<AdminUserDto> = emptyList()
        override fun merchants(limit: Int): List<AdminMerchantDto> = emptyList()
        override fun orders(limit: Int): List<AdminOrderDto> = emptyList()
        override fun deliveries(limit: Int): AdminDeliveryDto = AdminDeliveryDto(emptyList(), emptyMap())
        override fun payments(limit: Int): AdminPaymentsDto = AdminPaymentsDto(emptyList(), emptyList())
        override fun commissions(limit: Int): AdminCommissionsDto = AdminCommissionsDto(1500, emptyList())
        override fun deliveryPricing(): AdminDeliveryPricingDto =
            AdminDeliveryPricingDto(AdminDeliveryPricingSettingsDto("default", 400, 100, 5, null))

        override fun relay(limit: Int): AdminRelayDto = AdminRelayDto(emptyList(), 0)
        override fun businessParameters(): AdminBusinessParametersDto =
            AdminBusinessParametersDto(
                minimumDeliveryPriceCfa = 400,
                extraKmPriceCfa = 100,
                includedKm = 5,
                defaultCommissionRateBps = 1500,
                commissionOverrideCount = 0,
                relayStorageFeeAssessmentCount = 0,
                relayExcludedProductPolicySource = "RelayParcelPolicy",
                returnWindowPolicySource = "ReturnProcessing",
            )
    }
}
