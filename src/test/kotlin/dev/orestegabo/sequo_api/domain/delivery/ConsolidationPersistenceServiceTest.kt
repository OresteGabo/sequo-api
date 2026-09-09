package dev.orestegabo.sequo_api.domain.delivery

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:consolidation_persistence;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class ConsolidationPersistenceServiceTest @Autowired constructor(
    private val service: ConsolidationPersistenceService,
) {
    private val at = Instant.parse("2026-09-09T10:00:00Z")

    @Test
    fun sellerReadinessIsPersistedAndCompletesManifestWhenAllPackagesAreReady() {
        service.create(manifest(), at)

        service.markSellerPackageReady("manifest-persistence-1", "sub-1", "merchant-1", at.plusSeconds(60))
        assertEquals(
            ConsolidationStatus.AwaitingSellerPackages,
            requireNotNull(service.get("manifest-persistence-1")).status,
        )

        val completed = service.markSellerPackageReady(
            "manifest-persistence-1",
            "sub-2",
            "merchant-2",
            at.plusSeconds(120),
        )

        assertEquals(ConsolidationStatus.ReadyForSequoPickup, completed.status)
        assertEquals(
            ConsolidationStatus.ReadyForSequoPickup,
            requireNotNull(service.findByOrderId("order-persistence-1")).status,
        )
        assertEquals(2, requireNotNull(service.get("manifest-persistence-1")).sellerPackages.count { it.ready })
    }

    @Test
    fun sellerCannotMarkAnotherMerchantsPackageReady() {
        service.create(manifest("manifest-persistence-2", "order-persistence-2"), at)

        assertFailsWith<IllegalArgumentException> {
            service.markSellerPackageReady("manifest-persistence-2", "sub-1", "merchant-other", at)
        }
        assertEquals(
            ConsolidationStatus.AwaitingSellerPackages,
            requireNotNull(service.findByOrderId("order-persistence-2")).status,
        )
    }

    private fun manifest(
        manifestId: String = "manifest-persistence-1",
        orderId: String = "order-persistence-1",
    ) = SequoConsolidationManifest(
        manifestId = manifestId,
        orderId = orderId,
        customerId = "customer-persistence-1",
        sellerPackages = listOf(
            ConsolidationSellerPackage("sub-1", "merchant-1", 1, ready = false),
            ConsolidationSellerPackage("sub-2", "merchant-2", 2, ready = false),
        ),
    )
}
