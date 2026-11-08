package dev.orestegabo.sequo_api.domain.commission

import java.time.Instant
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class MerchantCommissionConfigurationServiceTest @Autowired constructor(
    private val service: MerchantCommissionConfigurationService,
    private val merchants: CommerceMerchantRepository,
) {
    @org.junit.jupiter.api.BeforeEach
    fun existingMerchants() {
        for (id in listOf("merchant-commission-config", "merchant-commission-clear", "merchant-commission-invalid"))
            merchants.save(MerchantRecord(id, name = "Fixture commission merchant"))
    }

    @Test
    fun merchantOverrideIsPersistedAndUsedForCalculation() {
        val updatedAt = Instant.parse("2026-09-09T10:00:00Z")

        val override = service.upsertOverride(
            merchantId = "merchant-commission-config",
            commissionRateBps = 750,
            updatedByUserId = "admin-commission",
            reason = "Launch partner rate.",
            updatedAt = updatedAt,
        )
        val snapshot = service.calculateForMerchant(
            merchantId = "merchant-commission-config",
            baseAmountCfa = 20_000,
            platformMarginCfa = 500,
        )

        assertEquals("merchant-commission-config", override.merchantId)
        assertEquals(750, override.commissionRateBps)
        assertEquals("Launch partner rate.", override.reason)
        assertEquals("admin-commission", override.updatedByUserId)
        assertEquals(updatedAt, override.updatedAt)
        assertEquals(750, service.resolveRateBps("merchant-commission-config"))
        assertEquals(750, snapshot.commissionRateBps)
        assertEquals(1_500, snapshot.commissionCfa)
        assertEquals(18_500, snapshot.merchantNetCfa)
        assertEquals(2_000, snapshot.sequoItemRevenueCfa)
    }

    @Test
    fun clearingOverrideFallsBackToDefaultCommission() {
        service.upsertOverride(
            merchantId = "merchant-commission-clear",
            commissionRateBps = 500,
            updatedByUserId = "admin-commission",
            reason = null,
        )

        service.clearOverride("merchant-commission-clear")

        assertNull(service.getOverride("merchant-commission-clear"))
        assertEquals(1500, service.resolveRateBps("merchant-commission-clear"))
    }

    @Test
    fun invalidPersistedOverrideIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            service.upsertOverride(
                merchantId = "merchant-commission-invalid",
                commissionRateBps = 499,
                updatedByUserId = "admin-commission",
                reason = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.upsertOverride(
                merchantId = "merchant-commission-invalid",
                commissionRateBps = 1501,
                updatedByUserId = "admin-commission",
                reason = null,
            )
        }
    }
}
