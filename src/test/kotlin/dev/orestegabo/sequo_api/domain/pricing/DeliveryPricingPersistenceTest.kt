package dev.orestegabo.sequo_api.domain.pricing

import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:delivery_pricing_persistence;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    ]
)
@Transactional
class DeliveryPricingPersistenceTest @Autowired constructor(
    private val repository: DeliveryPricingSettingsRepository,
    private val service: DeliveryPricingService,
) {
    @Test
    fun activePricingSettingsAreLoadedFromDatabase() {
        repository.save(
            DeliveryPricingSettingsRecord(
                id = "test-active-pricing",
                profile = "TEST",
                minimumDeliveryFeeCfa = 500,
                extraKmFeeCfa = 200,
                includedKm = 2,
                active = true,
                effectiveFrom = Instant.parse("2026-09-30T00:00:00Z"),
            )
        )

        val pricing = service.calculate(DeliveryPricingInput(distanceKm = 2.1))

        assertEquals(3, pricing.billableKm)
        assertEquals(700, pricing.baseFeeCfa)
        assertEquals(700, pricing.finalDeliveryFeeCfa)
    }

    @Test
    fun futureActiveProfileDoesNotOverrideTheEffectiveProfile() {
        val now = Instant.parse("2026-10-01T00:00:00Z")
        repository.save(DeliveryPricingSettingsRecord("effective-fixture", "LOCAL_FIXTURE_ONLY", 400, 100, 5, true, now.minusSeconds(1)))
        repository.save(DeliveryPricingSettingsRecord("future-fixture", "LOCAL_FIXTURE_ONLY", 900, 300, 1, true, now.plusSeconds(1)))
        val fixed = DeliveryPricingService(repository, Clock.fixed(now, ZoneOffset.UTC))
        assertEquals("effective-fixture", fixed.effectiveRecord()?.id)
        assertEquals(400, fixed.calculate(DeliveryPricingInput(1.0)).finalDeliveryFeeCfa)
    }
}
