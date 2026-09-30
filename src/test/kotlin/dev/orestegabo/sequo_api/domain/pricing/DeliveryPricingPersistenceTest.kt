package dev.orestegabo.sequo_api.domain.pricing

import java.time.Instant
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
}
