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
    private val missions: DeliveryMissionRepository,
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

        service.markSellerPackageCollected("manifest-persistence-1", "sub-1", at.plusSeconds(180))
        val custody = service.markSellerPackageCollected("manifest-persistence-1", "sub-2", at.plusSeconds(240))
        assertEquals(ConsolidationStatus.InSequoCustody, custody.status)
        assertEquals(2, custody.sellerPackages.count { it.collected })
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

    @Test
    fun consolidatedPackageCreatesOneFinalCustomerMissionIdempotently() {
        service.create(manifest("manifest-persistence-3", "order-persistence-3"), at)
        service.markSellerPackageReady("manifest-persistence-3", "sub-1", "merchant-1", at)
        service.markSellerPackageReady("manifest-persistence-3", "sub-2", "merchant-2", at)
        service.markSellerPackageCollected("manifest-persistence-3", "sub-1", at)
        service.markSellerPackageCollected("manifest-persistence-3", "sub-2", at)
        service.transition(
            ConsolidationTransitionRequest(
                manifest = requireNotNull(service.get("manifest-persistence-3")),
                event = ConsolidationEvent.SequoCreatesFinalPackage,
                finalPackageId = "SEQ-PKG-3",
                at = at,
            )
        )

        val first = service.dispatchFinalPackage("manifest-persistence-3", 700, 500, at.plusSeconds(60))
        val second = service.dispatchFinalPackage("manifest-persistence-3", 999, 999, at.plusSeconds(120))

        assertEquals(ConsolidationStatus.Dispatched, first.manifest.status)
        assertEquals(DeliveryMissionRecordDestination.CUSTOMER_ADDRESS, first.mission.destinationType)
        assertEquals(first.mission.id, second.mission.id)
        assertEquals(true, second.alreadyDispatched)
        assertEquals(1, missions.findAll().count { it.orderId == "order-persistence-3" && it.merchantSubOrderId == null })

        val tracking = requireNotNull(service.trackFinalPackage("manifest-persistence-3"))
        assertEquals("customer-persistence-1", tracking.customerId)
        assertEquals(ConsolidationStatus.Dispatched, tracking.status)
        assertEquals(first.mission.deliveryCode, tracking.tracking?.deliveryCode)
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
