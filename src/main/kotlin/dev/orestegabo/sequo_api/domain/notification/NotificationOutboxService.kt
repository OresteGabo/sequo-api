package dev.orestegabo.sequo_api.domain.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

data class EnqueueNotificationEventCommand(
    val eventId: String,
    val eventType: NotificationEventType,
    val aggregateType: String,
    val aggregateId: String,
    val payload: String? = null,
) {
    init {
        require(eventId.isNotBlank()) { "eventId cannot be blank." }
        require(aggregateType.isNotBlank()) { "aggregateType cannot be blank." }
        require(aggregateId.isNotBlank()) { "aggregateId cannot be blank." }
        require(payload == null || payload.length <= 4000) { "payload cannot exceed 4000 characters." }
    }
}

data class NotificationOutboxSnapshot(
    val id: String,
    val eventId: String,
    val eventType: NotificationEventType,
    val aggregateType: String,
    val aggregateId: String,
    val status: NotificationOutboxStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val lockedBy: String?,
    val lockedUntil: Instant?,
    val processedAt: Instant?,
)

@Service
class NotificationOutboxService(
    private val repository: NotificationOutboxRepository,
    private val maxAttempts: Int = 5,
    private val leaseDuration: Duration = Duration.ofMinutes(2),
    private val retryDelay: Duration = Duration.ofSeconds(30),
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive." }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive." }
        require(!retryDelay.isNegative && !retryDelay.isZero) { "retryDelay must be positive." }
    }

    @Transactional
    fun enqueue(command: EnqueueNotificationEventCommand, createdAt: Instant = Instant.now()): NotificationOutboxSnapshot {
        val existing = repository.findByEventId(command.eventId)
        if (existing != null) return existing.toSnapshot()
        return repository.save(
            NotificationOutbox(
                eventId = command.eventId,
                eventType = command.eventType,
                aggregateType = command.aggregateType,
                aggregateId = command.aggregateId,
                payload = command.payload,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        ).toSnapshot()
    }

    @Transactional
    fun claim(eventId: String, workerId: String, now: Instant = Instant.now()): NotificationOutboxSnapshot? {
        require(eventId.isNotBlank()) { "eventId cannot be blank." }
        require(workerId.isNotBlank()) { "workerId cannot be blank." }
        val event = repository.findByEventId(eventId) ?: return null
        if (event.status !in setOf(NotificationOutboxStatus.PENDING, NotificationOutboxStatus.FAILED_RETRYABLE)) return null
        if (event.nextAttemptAt?.isAfter(now) == true) return null
        if (event.lockedUntil?.isAfter(now) == true) return null
        event.status = NotificationOutboxStatus.PROCESSING
        event.attemptCount += 1
        event.lockedBy = workerId
        event.lockedUntil = now.plus(leaseDuration)
        event.updatedAt = now
        return repository.save(event).toSnapshot()
    }

    @Transactional
    fun markSent(eventId: String, workerId: String, now: Instant = Instant.now()): NotificationOutboxSnapshot? =
        ownedProcessingEvent(eventId, workerId)?.also {
            it.status = NotificationOutboxStatus.SENT
            it.lockedBy = null
            it.lockedUntil = null
            it.processedAt = now
            it.updatedAt = now
        }?.let { repository.save(it).toSnapshot() }

    @Transactional
    fun markFailed(eventId: String, workerId: String, now: Instant = Instant.now()): NotificationOutboxSnapshot? =
        ownedProcessingEvent(eventId, workerId)?.also {
            if (it.attemptCount >= maxAttempts) {
                it.status = NotificationOutboxStatus.FAILED_FINAL
                it.processedAt = now
            } else {
                it.status = NotificationOutboxStatus.FAILED_RETRYABLE
                it.nextAttemptAt = now.plus(retryDelay.multipliedBy(it.attemptCount.toLong()))
            }
            it.lockedBy = null
            it.lockedUntil = null
            it.updatedAt = now
        }?.let { repository.save(it).toSnapshot() }

    private fun ownedProcessingEvent(eventId: String, workerId: String): NotificationOutbox? {
        require(eventId.isNotBlank()) { "eventId cannot be blank." }
        require(workerId.isNotBlank()) { "workerId cannot be blank." }
        return repository.findByEventId(eventId)?.takeIf {
            it.status == NotificationOutboxStatus.PROCESSING && it.lockedBy == workerId
        }
    }
}

private fun NotificationOutbox.toSnapshot(): NotificationOutboxSnapshot =
    NotificationOutboxSnapshot(
        id = requireNotNull(id),
        eventId = eventId,
        eventType = eventType,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        status = status,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lockedBy = lockedBy,
        lockedUntil = lockedUntil,
        processedAt = processedAt,
    )
