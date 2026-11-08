package dev.orestegabo.sequo_api.domain.payment

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.platform.PaymentProviderConfigurationRecord
import dev.orestegabo.sequo_api.domain.platform.PaymentProviderConfigurationRepository
import dev.orestegabo.sequo_api.domain.platform.PaymentProviderReadiness
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:payment_provider_readiness;DB_CLOSE_DELAY=-1",
        "sequo.wallets.yas-togo.api-base-url=https://wallet-fixture.sequo.tg/v1/",
        "sequo.wallets.yas-togo.api-key=readiness-synthetic-key",
        "sequo.wallets.yas-togo.merchant-account-id=readiness-synthetic-account",
        "sequo.wallets.moov-africa.api-base-url=http://malformed-fixture.sequo.tg",
        "sequo.wallets.moov-africa.api-key=change_me",
        "sequo.wallets.moov-africa.merchant-account-id=",
    ],
)
class PaymentProviderReadinessHttpTest @Autowired constructor(
    private val providers: PaymentProviderConfigurationRepository,
) {
    @LocalServerPort private var port = 0

    @Test
    fun publicReadCannotTurnPersistedLiveFlagsIntoExecutionOrExposeConfigurationValues() {
        providers.save(PaymentProviderConfigurationRecord("yas_togo", "Yas fixture", "TG", PaymentProviderReadiness.LIVE_READY,
            customerCheckoutEnabled = true, refundEnabled = true, courierPayoutEnabled = true))
        providers.save(PaymentProviderConfigurationRecord("moov_africa", "Moov fixture", "TG", PaymentProviderReadiness.DISABLED,
            customerCheckoutEnabled = true, refundEnabled = true, courierPayoutEnabled = true))
        providers.save(PaymentProviderConfigurationRecord("unknown_wallet", "Unknown fixture", "TG", PaymentProviderReadiness.SANDBOX_READY,
            customerCheckoutEnabled = true, refundEnabled = true, courierPayoutEnabled = true))

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/payments/providers")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        val rows = ObjectMapper().readTree(response.body()).associateBy { it["providerId"].asText() }
        val yas = rows.getValue("yas_togo")
        assertEquals("LIVE_READY", yas["configuredReadiness"].asText())
        assertEquals("PLANNED", yas["readiness"].asText())
        assertEquals("NOT_CONNECTED", yas["integrationStatus"].asText())
        assertTrue(yas["apiConfigured"].asBoolean())
        assertEquals(0, yas["configurationIssues"].size())
        for (row in rows.values) {
            for (flag in listOf("customerCheckoutEnabled", "refundEnabled", "courierPayoutEnabled", "checkoutConnected")) {
                assertFalse(row[flag].asBoolean(), "$flag must stay false without an implemented transport")
            }
        }
        val moov = rows.getValue("moov_africa")
        assertEquals("DISABLED", moov["readiness"].asText())
        assertFalse(moov["apiConfigured"].asBoolean())
        assertEquals(listOf("API_BASE_URL_INVALID", "API_KEY_PLACEHOLDER", "MERCHANT_ACCOUNT_ID_MISSING"),
            moov["configurationIssues"].map { it.asText() })
        assertEquals("UNSUPPORTED", rows.getValue("unknown_wallet")["integrationStatus"].asText())
        assertEquals(listOf("UNSUPPORTED_PROVIDER"), rows.getValue("unknown_wallet")["configurationIssues"].map { it.asText() })
        for (value in listOf("wallet-fixture.sequo.tg", "malformed-fixture.sequo.tg", "readiness-synthetic-key",
            "readiness-synthetic-account", "change_me")) assertFalse(response.body().contains(value))

        val stored = providers.findById("yas_togo").orElseThrow()
        assertEquals(PaymentProviderReadiness.LIVE_READY, stored.readiness)
        assertTrue(stored.customerCheckoutEnabled && stored.refundEnabled && stored.courierPayoutEnabled)
    }
}
