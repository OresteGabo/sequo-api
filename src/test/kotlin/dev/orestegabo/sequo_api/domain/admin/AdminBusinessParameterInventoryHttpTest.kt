package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.JwtService
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.UserSession
import dev.orestegabo.sequo_api.domain.platform.SubscriptionPlanRecord
import dev.orestegabo.sequo_api.domain.platform.SubscriptionPlanRepository
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingInput
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingSettingsRecord
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingSettingsRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:admin_parameter_inventory;DB_CLOSE_DELAY=-1",
    "sequo.bargaining.max-customer-attempts=2", "sequo.bargaining.max-merchant-attempts=4",
    "sequo.bargaining.accepted-price-lock-hours=12", "sequo.returns.window-hours=48",
    "sequo.referrals.credit-cfa=850", "sequo.referrals.approval-reference=", "sequo.referrals.expiry-days=14",
    "sequo.notifications.token-encryption-secret=private_inventory_fixture_secret_never_exposed_2026",
])
class AdminBusinessParameterInventoryHttpTest @Autowired constructor(
    private val jwt: JwtService,
    private val plans: SubscriptionPlanRepository,
    private val pricingRecords: DeliveryPricingSettingsRepository,
    private val pricing: DeliveryPricingService,
) {
    @LocalServerPort private var port = 0
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    @Test
    fun `authenticated inventory matches runtime and separates unapproved plans and awards from applied values`() {
        val futureId = "inventory-future-price"
        val planIds = listOf("inventory-approved", "inventory-unapproved", "inventory-inactive")
        try {
            plans.saveAll(listOf(
                SubscriptionPlanRecord(planIds[0], "Approved fixture", 1200, 20, monthlyDiscountCapCfa = 700,
                    loyaltyMonthsThreshold = 6, loyaltyDiscountPercent = 5, active = true, approvalReference = "LOCAL_FIXTURE_APPROVAL"),
                SubscriptionPlanRecord(planIds[1], "Unapproved fixture", 1600, 45, active = true),
                SubscriptionPlanRecord(planIds[2], "Inactive fixture", 1900, 35, active = false, approvalReference = "LOCAL_FIXTURE_APPROVAL"),
            ))
            pricingRecords.save(DeliveryPricingSettingsRecord(futureId, "FUTURE_LOCAL_FIXTURE", 9999, 999, 1, true,
                Instant.now().plusSeconds(86_400)))
            val response = get("/api/admin/web/business-parameters", RoleCode.ADMIN)
            assertEquals(200, response.statusCode(), response.body())
            val body = mapper.readTree(response.body())
            val entries = body["parameters"].associateBy { it["reference"].asInt() }
            assertEquals((131..140).toSet(), entries.keys)
            assertTrue(entries.values.all { it["source"].asText().isNotBlank() && it["changeProcedure"].asText().isNotBlank() && it["verification"].asText().isNotBlank() })
            val effective = pricing.effectiveSettings()
            assertEquals("${effective.minimumDeliveryFeeCfa} CFA", entries.getValue(131)["effectiveValues"]["Minimum delivery charge"].asText())
            assertEquals("${effective.extraKmFeeCfa} CFA", entries.getValue(132)["effectiveValues"]["Additional kilometer charge"].asText())
            assertEquals(effective.minimumDeliveryFeeCfa, pricing.calculate(DeliveryPricingInput(0.0)).baseFeeCfa)
            assertFalse(response.body().contains("FUTURE_LOCAL_FIXTURE"))
            assertFalse(response.body().contains("private_inventory_fixture_secret_never_exposed_2026"))
            val subscription = entries.getValue(134)
            assertEquals("CONFIGURED", subscription["state"].asText())
            assertTrue(subscription["effectiveValues"]["Plan ${planIds[0]}"].asText().contains("APPROVED_ACTIVE"))
            assertTrue(subscription["effectiveValues"]["Plan ${planIds[1]}"].asText().contains("PENDING_APPROVAL"))
            assertTrue(subscription["effectiveValues"]["Plan ${planIds[2]}"].asText().contains("INACTIVE"))
            assertTrue(subscription["effectiveValues"]["Customer eligibility"].asText().contains("Verified paid activation"))
            val publishedPlans = mapper.readTree(get("/api/subscriptions/plans", RoleCode.ADMIN).body()).map { it["id"].asText() }
            assertTrue(planIds[0] in publishedPlans)
            assertFalse(planIds[1] in publishedPlans)
            assertFalse(planIds[2] in publishedPlans)
            val referral = entries.getValue(135)
            assertEquals("PENDING_APPROVAL", referral["state"].asText())
            assertEquals("850 CFA", referral["effectiveValues"]["Configured new award"].asText())
            assertEquals("0 CFA", referral["effectiveValues"]["Effective new award"].asText())
            assertEquals("2", entries.getValue(136)["effectiveValues"]["Customer attempts"].asText())
            assertEquals("4", entries.getValue(136)["effectiveValues"]["Merchant counteroffers"].asText())
            assertEquals("12 hours", entries.getValue(137)["effectiveValues"]["New accepted-price lock"].asText())
            assertEquals("48 hours", entries.getValue(138)["effectiveValues"]["Current request window"].asText())
            assertTrue(entries.getValue(139)["effectiveValues"]["Daily tariff"].asText().contains("No global tariff"))
            assertTrue(entries.getValue(140)["effectiveValues"]["Relay exclusions"].asText().contains("Sensitive"))
            assertEquals(403, get("/api/admin/web/business-parameters", RoleCode.CUSTOMER).statusCode())
            assertTrue(get("/api/admin/web/business-parameters", null).statusCode() in setOf(401, 403))
        } finally {
            planIds.forEach { plans.deleteById(it) }
            pricingRecords.deleteById(futureId)
        }
    }

    private fun get(path: String, role: RoleCode?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        role?.let { request.header("Authorization", "Bearer ${jwt.generateAccessToken(UserSession("inventory-$it", null, AuthProvider.EMAIL, setOf(it)))}") }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }
}
