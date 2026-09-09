package dev.orestegabo.sequo_api.domain.delivery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class CourierAvailabilityStatus { ACTIVE, PAUSED }

@Entity
@Table(name = "courier_availability_states")
class CourierAvailabilityRecord(
    @Id @Column(name = "courier_id", nullable = false) val courierId: String,
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 32) var status: CourierAvailabilityStatus,
    @Column(name = "paused_reason", length = 1000) var pausedReason: String? = null,
    @Column(name = "paused_by") var pausedBy: String? = null,
    @Column(name = "paused_at") var pausedAt: Instant? = null,
    @Column(name = "paused_until") var pausedUntil: Instant? = null,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
) {
    init {
        require(courierId.isNotBlank()) { "courierId cannot be blank." }
        require((pausedReason?.length ?: 0) <= 1000) { "Pause reason must be at most 1000 characters." }
    }
}

interface CourierAvailabilityRepository : JpaRepository<CourierAvailabilityRecord, String> {
    fun countByStatus(status: CourierAvailabilityStatus): Long
    fun findByStatusOrderByUpdatedAtDesc(status: CourierAvailabilityStatus): List<CourierAvailabilityRecord>
}

data class CourierAvailabilitySnapshot(
    val courierId: String,
    val status: CourierAvailabilityStatus,
    val paused: Boolean,
    val pausedReason: String?,
    val pausedBy: String?,
    val pausedAt: Instant?,
    val pausedUntil: Instant?,
    val updatedAt: Instant,
)

@Service
class CourierAvailabilityService(
    private val repository: CourierAvailabilityRepository,
) {
    @Transactional(readOnly = true)
    fun get(courierId: String, at: Instant = Instant.now()): CourierAvailabilitySnapshot {
        require(courierId.isNotBlank()) { "courierId cannot be blank." }
        return repository.findById(courierId)
            .map { it.toSnapshot(at) }
            .orElse(defaultActive(courierId, at))
    }

    @Transactional(readOnly = true)
    fun isPaused(courierId: String, at: Instant = Instant.now()): Boolean =
        get(courierId, at).paused

    @Transactional(readOnly = true)
    fun countPaused(): Long = repository.countByStatus(CourierAvailabilityStatus.PAUSED)

    @Transactional(readOnly = true)
    fun listActivePausedCouriers(
        at: Instant = Instant.now(),
        limit: Int = 20,
    ): List<CourierAvailabilitySnapshot> {
        require(limit in 1..100) { "Paused courier limit must be between 1 and 100." }
        return repository.findByStatusOrderByUpdatedAtDesc(CourierAvailabilityStatus.PAUSED)
            .map { it.toSnapshot(at) }
            .filter { it.paused }
            .take(limit)
    }

    @Transactional
    fun pause(
        courierId: String,
        actorUserId: String,
        reason: String,
        pausedUntil: Instant? = null,
        at: Instant = Instant.now(),
    ): CourierAvailabilitySnapshot {
        require(courierId.isNotBlank()) { "courierId cannot be blank." }
        require(actorUserId.isNotBlank()) { "actorUserId cannot be blank." }
        require(reason.isNotBlank() && reason.length <= 1000) { "Pause reason must be 1-1000 characters." }
        require(pausedUntil == null || pausedUntil.isAfter(at)) { "Pause end must be after pause start." }

        val record = repository.findById(courierId).orElse(
            CourierAvailabilityRecord(
                courierId = courierId,
                status = CourierAvailabilityStatus.ACTIVE,
                updatedAt = at,
            )
        )
        record.status = CourierAvailabilityStatus.PAUSED
        record.pausedReason = reason
        record.pausedBy = actorUserId
        record.pausedAt = at
        record.pausedUntil = pausedUntil
        record.updatedAt = at
        return repository.save(record).toSnapshot(at)
    }

    @Transactional
    fun unpause(
        courierId: String,
        actorUserId: String,
        at: Instant = Instant.now(),
    ): CourierAvailabilitySnapshot {
        require(courierId.isNotBlank()) { "courierId cannot be blank." }
        require(actorUserId.isNotBlank()) { "actorUserId cannot be blank." }

        val record = repository.findById(courierId).orElse(
            CourierAvailabilityRecord(
                courierId = courierId,
                status = CourierAvailabilityStatus.ACTIVE,
                updatedAt = at,
            )
        )
        record.status = CourierAvailabilityStatus.ACTIVE
        record.pausedReason = null
        record.pausedBy = null
        record.pausedAt = null
        record.pausedUntil = null
        record.updatedAt = at
        return repository.save(record).toSnapshot(at)
    }

    private fun defaultActive(courierId: String, at: Instant) =
        CourierAvailabilitySnapshot(
            courierId = courierId,
            status = CourierAvailabilityStatus.ACTIVE,
            paused = false,
            pausedReason = null,
            pausedBy = null,
            pausedAt = null,
            pausedUntil = null,
            updatedAt = at,
        )
}

private fun CourierAvailabilityRecord.toSnapshot(at: Instant): CourierAvailabilitySnapshot {
    val activePause = status == CourierAvailabilityStatus.PAUSED && (pausedUntil == null || pausedUntil!!.isAfter(at))
    return CourierAvailabilitySnapshot(
        courierId = courierId,
        status = if (activePause) CourierAvailabilityStatus.PAUSED else CourierAvailabilityStatus.ACTIVE,
        paused = activePause,
        pausedReason = if (activePause) pausedReason else null,
        pausedBy = if (activePause) pausedBy else null,
        pausedAt = if (activePause) pausedAt else null,
        pausedUntil = if (activePause) pausedUntil else null,
        updatedAt = updatedAt,
    )
}
