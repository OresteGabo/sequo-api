package dev.orestegabo.sequo_api.domain.admin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.*
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
    "spring.datasource.url=jdbc:h2:mem:admin_payment_investigation;DB_CLOSE_DELAY=-1",
])
class AdminPaymentInvestigationHttpTest @Autowired constructor(
    private val jwt: JwtService,
    private val users: UserRepository,
    private val checkouts: PendingPaymentCheckoutRepository,
    private val orders: CustomerOrderRecordRepository,
    private val events: PaymentWebhookEventRepository,
) {
    @LocalServerPort private var port = 0
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private val now = Instant.parse("2026-11-08T10:00:00Z")

    @Test
    fun adminInvestigatesCheckoutWithoutCallbackAndReadsOutcomesWithoutSensitivePayloadOrInventedOrderLink() {
        val customerId = users.save(User(email = "payment-owner@fixture.invalid", name = "PRIVATE_CUSTOMER_CONTACT_SENTINEL")).id!!
        fun checkout(id: String, status: PendingPaymentCheckoutStatus) = PendingPaymentCheckoutRecord(
            checkoutId = id, customerId = customerId, paymentProvider = "yas_togo", paymentReference = "payment-$id", amountCfa = 4500,
            requestPayload = "PRIVATE_REQUEST_PAYLOAD_SENTINEL", pricingPayload = "PRIVATE_PRICING_PAYLOAD_SENTINEL",
            status = status, createdAt = now, updatedAt = now,
        )
        checkouts.save(checkout("pending-no-callback", PendingPaymentCheckoutStatus.AWAITING_WEBHOOK))
        checkouts.save(checkout("completed-actual-link", PendingPaymentCheckoutStatus.COMPLETED))
        checkouts.save(checkout("legacy-completed-no-order", PendingPaymentCheckoutStatus.COMPLETED))
        orders.save(CustomerOrderRecord(id = "actual-order-id", checkoutId = "completed-actual-link", customerId = customerId,
            serviceLevel = OrderServiceLevel.Regular, route = OrderRoute.FastDelivery,
            fulfillmentPriority = FulfillmentPriority.Standard, requiresConsolidation = false,
            customerFacingStatus = "Preparing", itemSubtotalCfa = 4100, deliveryFeeCfa = 400, totalCfa = 4500,
            paymentProvider = "yas_togo", paymentReference = "payment-completed-actual-link", providerReference = "provider-fixture",
            paymentStatus = PaymentValidationStatus.Validated, orderStatus = CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT,
            createdAt = now, updatedAt = now))

        val queue = get("/api/admin/web/payments", RoleCode.ADMIN)
        assertEquals(200, queue.statusCode(), queue.body())
        assertEquals("no-store", queue.headers().firstValue("Cache-Control").orElse(null))
        val rows = json.readTree(queue.body())["checkouts"].associateBy { it["checkoutId"].asText() }
        assertEquals("AWAITING_WEBHOOK", rows.getValue("pending-no-callback")["status"].asText())
        assertTrue(rows.getValue("pending-no-callback")["orderId"].isNull)
        assertEquals("actual-order-id", rows.getValue("completed-actual-link")["orderId"].asText())
        assertTrue(rows.getValue("legacy-completed-no-order")["orderId"].isNull)
        val noEvent = get("/api/admin/web/payments/checkouts/pending-no-callback", RoleCode.ADMIN)
        assertEquals(0, json.readTree(noEvent.body())["events"].size())

        events.save(PaymentWebhookEventRecord("admin-ignored-event", "yas_togo", "ignored-event", "pending-no-callback",
            paymentReference = "wrong-reference", providerReference = null, amountCfa = 4500,
            status = PaymentWebhookProviderStatus.VALIDATED, occurredAt = now, payloadHash = "PRIVATE_HASH_SENTINEL", receivedAt = now,
            reconciliationStatus = PaymentWebhookReconciliationStatus.IGNORED,
            reconciliationReason = "payment_reference_mismatch", reconciledAt = now))
        val detail = get("/api/admin/web/payments/checkouts/pending-no-callback", RoleCode.SUPER_ADMIN)
        assertEquals(200, detail.statusCode(), detail.body())
        val body = json.readTree(detail.body())
        assertEquals("AWAITING_WEBHOOK", body["checkout"]["status"].asText())
        assertTrue(body["checkout"]["orderId"].isNull)
        assertEquals("VALIDATED", body["events"].single()["status"].asText())
        assertEquals("IGNORED", body["events"].single()["reconciliationStatus"].asText())
        assertEquals("payment_reference_mismatch", body["events"].single()["reconciliationReason"].asText())
        assertTrue(body["events"].single()["reconciledAt"].isTextual)
        for (response in listOf(queue, detail)) for (sensitive in listOf(customerId, "PRIVATE_CUSTOMER_CONTACT_SENTINEL",
            "PRIVATE_REQUEST_PAYLOAD_SENTINEL", "PRIVATE_PRICING_PAYLOAD_SENTINEL", "PRIVATE_HASH_SENTINEL",
            "requestPayload", "pricingPayload", "payloadHash", "rawPayload", "signature", "apiKey")) {
            assertFalse(response.body().contains(sensitive), "Payment investigation must use the safe projection")
        }
        assertEquals(404, get("/api/admin/web/payments/checkouts/missing-checkout", RoleCode.ADMIN).statusCode())
        for (role in listOf(RoleCode.CUSTOMER, RoleCode.COURIER, RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF, RoleCode.SUPPORT_AGENT)) {
            assertEquals(403, get("/api/admin/web/payments", role).statusCode())
            assertEquals(403, get("/api/admin/web/payments/checkouts/pending-no-callback", role).statusCode())
        }
        assertTrue(get("/api/admin/web/payments/checkouts/pending-no-callback", null).statusCode() in setOf(401, 403))
        assertEquals(PendingPaymentCheckoutStatus.AWAITING_WEBHOOK, checkouts.findById("pending-no-callback").orElseThrow().status)
        assertEquals(1L, orders.count())
    }

    private fun get(path: String, role: RoleCode?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        role?.let { request.header("Authorization", "Bearer ${jwt.generateAccessToken(UserSession("payment-investigator-$it", null, AuthProvider.EMAIL, setOf(it)))}") }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }
}
