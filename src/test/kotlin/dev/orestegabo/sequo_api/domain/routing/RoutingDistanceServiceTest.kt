package dev.orestegabo.sequo_api.domain.routing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoutingDistanceServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun providerEstimateIsCachedToProtectRoutingQuotaAndCost() {
        val provider = RecordingProvider(distanceMeters = 3_420)
        val auditTrail = RoutingAuditTrail()
        val service = service(provider = provider, auditTrail = auditTrail)
        val request = sampleRequest()

        val first = service.estimateDistance(request)
        val second = service.estimateDistance(request)

        assertEquals(3_420, first.distanceMeters)
        assertEquals(DistanceEstimateSource.Provider, first.source)
        assertEquals(3_420, second.distanceMeters)
        assertEquals(DistanceEstimateSource.Cache, second.source)
        assertEquals(1, provider.calls)
        assertTrue(auditTrail.events.any { it.type == RoutingAuditType.EstimateCreated })
        assertTrue(auditTrail.events.any { it.type == RoutingAuditType.CacheHit })
    }

    @Test
    fun manualFallbackIsUsedWhenQuotaIsExhausted() {
        val provider = RecordingProvider(distanceMeters = 4_000)
        val auditTrail = RoutingAuditTrail()
        val service = service(
            provider = provider,
            quota = RoutingQuota(maxProviderCallsPerWindow = 0, window = Duration.ofHours(1), clock = clock),
            auditTrail = auditTrail,
        )

        val estimate = service.estimateDistance(sampleRequest(manualFallbackMeters = 5_200))

        assertEquals(5_200, estimate.distanceMeters)
        assertEquals(DistanceEstimateSource.ManualFallback, estimate.source)
        assertEquals(0, provider.calls)
        assertTrue(auditTrail.events.any { it.type == RoutingAuditType.QuotaBlocked })
        assertTrue(auditTrail.events.any { it.type == RoutingAuditType.ManualFallbackUsed })
    }

    @Test
    fun quotaFailureWithoutManualFallbackIsRejected() {
        val service = service(
            quota = RoutingQuota(maxProviderCallsPerWindow = 0, window = Duration.ofHours(1), clock = clock),
        )

        assertFailsWith<RoutingQuotaExceededException> {
            service.estimateDistance(sampleRequest())
        }
    }

    @Test
    fun actualDistanceAuditStoresVarianceAgainstEstimate() {
        val auditTrail = RoutingAuditTrail()
        val service = service(auditTrail = auditTrail)
        val request = sampleRequest()
        val estimate = service.estimateDistance(request)

        val event = service.recordActualDistance(request, estimate, actualMeters = 4_250)

        assertEquals(RoutingAuditType.ActualDistanceRecorded, event.type)
        assertEquals(3_000, event.estimatedMeters)
        assertEquals(4_250, event.actualMeters)
        assertEquals(1_250, event.varianceMeters)
        assertTrue(auditTrail.events.any { it.type == RoutingAuditType.ActualDistanceRecorded })
    }

    @Test
    fun straightLineProviderReturnsPositiveDistanceInMeters() {
        val provider = StraightLineRoutingDistanceProvider()

        val meters = provider.estimate(sampleRequest())

        assertTrue(meters > 0)
    }

    private fun service(
        provider: RoutingDistanceProvider = RecordingProvider(distanceMeters = 3_000),
        quota: RoutingQuota = RoutingQuota(maxProviderCallsPerWindow = 10, window = Duration.ofHours(1), clock = clock),
        auditTrail: RoutingAuditTrail = RoutingAuditTrail(),
    ): RoutingDistanceService =
        RoutingDistanceService(
            provider = provider,
            cache = InMemoryRoutingDistanceCache(ttl = Duration.ofHours(12)),
            quota = quota,
            auditTrail = auditTrail,
            clock = clock,
        )

    private fun sampleRequest(
        manualFallbackMeters: Int? = null,
    ): RoutingDistanceRequest =
        RoutingDistanceRequest(
            origin = GeoPoint(latitude = 6.1319, longitude = 1.2228),
            destination = GeoPoint(latitude = 6.1725, longitude = 1.2314),
            countryCode = "TG",
            manualFallbackMeters = manualFallbackMeters,
        )

    private class RecordingProvider(
        private val distanceMeters: Int,
    ) : RoutingDistanceProvider {
        var calls: Int = 0

        override val name: String = "recording-provider"

        override fun estimate(request: RoutingDistanceRequest): Int {
            calls += 1
            return distanceMeters
        }
    }
}
