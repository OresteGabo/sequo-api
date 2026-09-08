package dev.orestegabo.sequo_api.domain.routing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude must be between -90 and 90." }
        require(longitude in -180.0..180.0) { "longitude must be between -180 and 180." }
    }
}

data class RoutingDistanceRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val countryCode: String = "TG",
    val manualFallbackMeters: Int? = null,
) {
    init {
        require(countryCode.isNotBlank()) { "countryCode is required." }
        require(manualFallbackMeters == null || manualFallbackMeters > 0) {
            "manualFallbackMeters must be positive when provided."
        }
    }
}

enum class DistanceEstimateSource {
    Provider,
    Cache,
    ManualFallback,
}

data class DistanceEstimate(
    val distanceMeters: Int,
    val source: DistanceEstimateSource,
    val providerName: String? = null,
    val estimatedAt: Instant,
) {
    init {
        require(distanceMeters > 0) { "distanceMeters must be positive." }
    }

    val distanceKm: Double
        get() = distanceMeters / 1000.0
}

data class RoutingAuditEvent(
    val type: RoutingAuditType,
    val cacheKey: String,
    val providerName: String?,
    val source: DistanceEstimateSource?,
    val estimatedMeters: Int? = null,
    val actualMeters: Int? = null,
    val varianceMeters: Int? = null,
    val message: String,
    val occurredAt: Instant,
)

enum class RoutingAuditType {
    EstimateCreated,
    CacheHit,
    QuotaBlocked,
    ManualFallbackUsed,
    ActualDistanceRecorded,
}

interface RoutingDistanceProvider {
    val name: String
    fun estimate(request: RoutingDistanceRequest): Int
}

interface RoutingDistanceCache {
    fun get(key: String, now: Instant): DistanceEstimate?
    fun put(key: String, estimate: DistanceEstimate, now: Instant)
}

class InMemoryRoutingDistanceCache(
    private val ttl: Duration,
) : RoutingDistanceCache {
    private val entries = mutableMapOf<String, CachedDistanceEstimate>()

    override fun get(key: String, now: Instant): DistanceEstimate? {
        val cached = entries[key] ?: return null
        if (cached.expiresAt <= now) {
            entries.remove(key)
            return null
        }
        return cached.estimate.copy(source = DistanceEstimateSource.Cache)
    }

    override fun put(key: String, estimate: DistanceEstimate, now: Instant) {
        entries[key] = CachedDistanceEstimate(estimate, now.plus(ttl))
    }

    private data class CachedDistanceEstimate(
        val estimate: DistanceEstimate,
        val expiresAt: Instant,
    )
}

class RoutingQuota(
    private val maxProviderCallsPerWindow: Int,
    private val window: Duration,
    private val clock: Clock,
) {
    private var windowStartedAt: Instant = Instant.EPOCH
    private var usedCalls: Int = 0

    init {
        require(maxProviderCallsPerWindow >= 0) { "maxProviderCallsPerWindow cannot be negative." }
        require(!window.isZero && !window.isNegative) { "window must be positive." }
    }

    fun tryConsume(): Boolean {
        val now = clock.instant()
        if (windowStartedAt == Instant.EPOCH || !now.isBefore(windowStartedAt.plus(window))) {
            windowStartedAt = now
            usedCalls = 0
        }
        if (usedCalls >= maxProviderCallsPerWindow) return false
        usedCalls += 1
        return true
    }
}

class RoutingAuditTrail {
    private val mutableEvents = mutableListOf<RoutingAuditEvent>()

    val events: List<RoutingAuditEvent>
        get() = mutableEvents.toList()

    fun record(event: RoutingAuditEvent) {
        mutableEvents += event
    }
}

class RoutingDistanceService(
    private val provider: RoutingDistanceProvider,
    private val cache: RoutingDistanceCache,
    private val quota: RoutingQuota,
    private val auditTrail: RoutingAuditTrail,
    private val clock: Clock,
) {
    fun estimateDistance(request: RoutingDistanceRequest): DistanceEstimate {
        val now = clock.instant()
        val key = cacheKey(request)

        cache.get(key, now)?.let { cached ->
            auditTrail.record(
                RoutingAuditEvent(
                    type = RoutingAuditType.CacheHit,
                    cacheKey = key,
                    providerName = cached.providerName,
                    source = DistanceEstimateSource.Cache,
                    estimatedMeters = cached.distanceMeters,
                    message = "Distance estimate reused from cache.",
                    occurredAt = now,
                )
            )
            return cached
        }

        if (!quota.tryConsume()) {
            val manualMeters = request.manualFallbackMeters
            auditTrail.record(
                RoutingAuditEvent(
                    type = RoutingAuditType.QuotaBlocked,
                    cacheKey = key,
                    providerName = provider.name,
                    source = null,
                    message = "Routing provider quota is exhausted for the current window.",
                    occurredAt = now,
                )
            )
            if (manualMeters != null) {
                return manualFallbackEstimate(key, manualMeters, now)
            }
            throw RoutingQuotaExceededException("Routing provider quota is exhausted and no manual fallback was supplied.")
        }

        val providerDistanceMeters = provider.estimate(request)
        require(providerDistanceMeters > 0) { "provider distance estimate must be positive." }
        val estimate = DistanceEstimate(
            distanceMeters = providerDistanceMeters,
            source = DistanceEstimateSource.Provider,
            providerName = provider.name,
            estimatedAt = now,
        )
        cache.put(key, estimate, now)
        auditTrail.record(
            RoutingAuditEvent(
                type = RoutingAuditType.EstimateCreated,
                cacheKey = key,
                providerName = provider.name,
                source = DistanceEstimateSource.Provider,
                estimatedMeters = providerDistanceMeters,
                message = "Distance estimate created by routing provider.",
                occurredAt = now,
            )
        )
        return estimate
    }

    fun recordActualDistance(
        request: RoutingDistanceRequest,
        estimate: DistanceEstimate,
        actualMeters: Int,
    ): RoutingAuditEvent {
        require(actualMeters > 0) { "actualMeters must be positive." }
        val event = RoutingAuditEvent(
            type = RoutingAuditType.ActualDistanceRecorded,
            cacheKey = cacheKey(request),
            providerName = estimate.providerName,
            source = estimate.source,
            estimatedMeters = estimate.distanceMeters,
            actualMeters = actualMeters,
            varianceMeters = actualMeters - estimate.distanceMeters,
            message = "Actual courier distance recorded against the original estimate.",
            occurredAt = clock.instant(),
        )
        auditTrail.record(event)
        return event
    }

    private fun manualFallbackEstimate(
        key: String,
        manualMeters: Int,
        now: Instant,
    ): DistanceEstimate {
        val estimate = DistanceEstimate(
            distanceMeters = manualMeters,
            source = DistanceEstimateSource.ManualFallback,
            providerName = null,
            estimatedAt = now,
        )
        cache.put(key, estimate, now)
        auditTrail.record(
            RoutingAuditEvent(
                type = RoutingAuditType.ManualFallbackUsed,
                cacheKey = key,
                providerName = null,
                source = DistanceEstimateSource.ManualFallback,
                estimatedMeters = manualMeters,
                message = "Manual distance fallback used after provider quota protection.",
                occurredAt = now,
            )
        )
        return estimate
    }

    private fun cacheKey(request: RoutingDistanceRequest): String =
        listOf(
            request.countryCode.trim().uppercase(),
            request.origin.latitude.normalizedCoordinate(),
            request.origin.longitude.normalizedCoordinate(),
            request.destination.latitude.normalizedCoordinate(),
            request.destination.longitude.normalizedCoordinate(),
        ).joinToString(":")

    private fun Double.normalizedCoordinate(): String = "%.5f".format(this)
}

class StraightLineRoutingDistanceProvider(
    override val name: String = "straight-line-estimator",
) : RoutingDistanceProvider {
    override fun estimate(request: RoutingDistanceRequest): Int {
        val earthRadiusMeters = 6_371_000.0
        val originLat = Math.toRadians(request.origin.latitude)
        val destinationLat = Math.toRadians(request.destination.latitude)
        val deltaLat = Math.toRadians(request.destination.latitude - request.origin.latitude)
        val deltaLon = Math.toRadians(request.destination.longitude - request.origin.longitude)

        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(originLat) * cos(destinationLat) *
            sin(deltaLon / 2) * sin(deltaLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return (earthRadiusMeters * c).roundToInt().coerceAtLeast(1)
    }
}

class RoutingQuotaExceededException(message: String) : RuntimeException(message)
