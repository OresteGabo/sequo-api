package dev.orestegabo.sequo_api.domain.delivery

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequoConsolidationServiceTest {
    private val service = SequoConsolidationService()
    private val custodyTime = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun waitsForEverySellerThenCreatesOneCustomerPackage() {
        val waiting = manifest(readySecondSeller = false)
        val notReady = service.transition(ConsolidationTransitionRequest(waiting, ConsolidationEvent.SellerPackageReady))
        assertFalse(notReady.accepted)

        val ready = service.transition(ConsolidationTransitionRequest(manifest(readySecondSeller = true), ConsolidationEvent.SellerPackageReady))
        val custody = service.transition(ConsolidationTransitionRequest(ready.manifest, ConsolidationEvent.SequoCollectsAllPackages, custodyTime))
        val consolidated = service.transition(ConsolidationTransitionRequest(custody.manifest, ConsolidationEvent.SequoCreatesFinalPackage, finalPackageId = "SEQ-PKG-001"))

        assertTrue(ready.accepted)
        assertEquals(ConsolidationStatus.ReadyForSequoPickup, ready.manifest.status)
        assertEquals(ConsolidationStatus.InSequoCustody, custody.manifest.status)
        assertEquals(custodyTime, custody.manifest.sequoCustodyAt)
        assertEquals(ConsolidationStatus.Consolidated, consolidated.manifest.status)
        assertEquals("SEQ-PKG-001", consolidated.manifest.finalPackageId)
        assertEquals(3, consolidated.manifest.totalPackageCount)
    }

    @Test
    fun dispatchRequiresFinalPackageIdAndCustody() {
        val ready = service.transition(ConsolidationTransitionRequest(manifest(true), ConsolidationEvent.SellerPackageReady))
        val custody = service.transition(ConsolidationTransitionRequest(ready.manifest, ConsolidationEvent.SequoCollectsAllPackages, custodyTime))
        val missingId = service.transition(ConsolidationTransitionRequest(custody.manifest, ConsolidationEvent.SequoCreatesFinalPackage))

        assertFalse(missingId.accepted)
        assertEquals(ConsolidationStatus.InSequoCustody, missingId.manifest.status)
    }

    @Test
    fun rejectsDuplicateSellerEntries() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SequoConsolidationManifest(
                manifestId = "MAN-1",
                orderId = "ORDER-1",
                customerId = "CUSTOMER-1",
                sellerPackages = listOf(
                    ConsolidationSellerPackage("SUB-1", "SELLER-1", 1, true),
                    ConsolidationSellerPackage("SUB-2", "SELLER-1", 1, true),
                ),
            )
        }
    }

    @Test
    fun cancellationStopsFurtherOperations() {
        val manifest = manifest(true)
        val cancelled = service.transition(ConsolidationTransitionRequest(manifest, ConsolidationEvent.Cancel))
        val afterCancellation = service.transition(ConsolidationTransitionRequest(cancelled.manifest, ConsolidationEvent.SellerPackageReady))

        assertTrue(cancelled.accepted)
        assertFalse(afterCancellation.accepted)
        assertEquals(ConsolidationStatus.Cancelled, afterCancellation.manifest.status)
    }

    private fun manifest(readySecondSeller: Boolean): SequoConsolidationManifest =
        SequoConsolidationManifest(
            manifestId = "MAN-1",
            orderId = "ORDER-1",
            customerId = "CUSTOMER-1",
            sellerPackages = listOf(
                ConsolidationSellerPackage("SUB-1", "SELLER-1", 1, true),
                ConsolidationSellerPackage("SUB-2", "SELLER-2", 2, readySecondSeller),
            ),
        )
}
