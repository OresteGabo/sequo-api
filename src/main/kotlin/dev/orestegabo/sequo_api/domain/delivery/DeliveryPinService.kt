package dev.orestegabo.sequo_api.domain.delivery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

@Entity
@Table(name = "delivery_pins")
class DeliveryPin(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: String? = null,
    @Column(name = "delivery_mission_id", nullable = false) val deliveryMissionId: String,
    @Column(name = "pin_hash", nullable = false, length = 255) val pinHash: String,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "used_at") var usedAt: Instant? = null,
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int = 0,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

interface DeliveryPinRepository : JpaRepository<DeliveryPin, String> {
    fun findFirstByDeliveryMissionIdAndUsedAtIsNullOrderByCreatedAtDesc(deliveryMissionId: String): DeliveryPin?
}

data class CreateDeliveryPinCommand(
    val deliveryMissionId: String,
    val rawPin: String,
    val expiresAt: Instant,
    val createdAt: Instant = Instant.now(),
) {
    init {
        require(deliveryMissionId.isNotBlank()) { "deliveryMissionId cannot be blank." }
        require(rawPin.matches(Regex("\\d{6}"))) { "Delivery PIN must contain exactly 6 digits." }
        require(expiresAt.isAfter(createdAt)) { "PIN expiry must be after creation." }
    }
}

data class DeliveryPinSnapshot(val id: String, val deliveryMissionId: String, val expiresAt: Instant, val usedAt: Instant?, val attemptCount: Int)

sealed class DeliveryPinResult {
    data class Accepted(val pin: DeliveryPinSnapshot) : DeliveryPinResult()
    data class Rejected(val code: String, val message: String, val pin: DeliveryPinSnapshot? = null) : DeliveryPinResult()
}

@Service
class DeliveryPinService(
    private val repository: DeliveryPinRepository,
    private val maxAttempts: Int = 5,
) {
    init { require(maxAttempts > 0) { "maxAttempts must be positive." } }

    @Transactional
    fun create(command: CreateDeliveryPinCommand): DeliveryPinSnapshot = repository.save(
        DeliveryPin(
            deliveryMissionId = command.deliveryMissionId,
            pinHash = hash(command.rawPin),
            expiresAt = command.expiresAt,
            createdAt = command.createdAt,
        )
    ).toSnapshot()

    @Transactional
    fun verify(deliveryMissionId: String, rawPin: String, at: Instant = Instant.now()): DeliveryPinResult {
        if (deliveryMissionId.isBlank()) return DeliveryPinResult.Rejected("missing_mission_id", "Delivery mission id is required.")
        val pin = repository.findFirstByDeliveryMissionIdAndUsedAtIsNullOrderByCreatedAtDesc(deliveryMissionId)
            ?: return DeliveryPinResult.Rejected("delivery_pin_not_found", "No active delivery PIN was found.")
        if (pin.attemptCount >= maxAttempts) return DeliveryPinResult.Rejected("delivery_pin_attempts_exhausted", "Delivery PIN attempts are exhausted.", pin.toSnapshot())
        if (!at.isBefore(pin.expiresAt)) return DeliveryPinResult.Rejected("delivery_pin_expired", "Delivery PIN is expired.", pin.toSnapshot())
        if (!rawPin.matches(Regex("\\d{6}")) || !MessageDigest.isEqual(hash(rawPin).encodeToByteArray(), pin.pinHash.encodeToByteArray())) {
            pin.attemptCount += 1
            repository.save(pin)
            return DeliveryPinResult.Rejected("invalid_delivery_pin", "Delivery PIN is invalid.", pin.toSnapshot())
        }
        pin.usedAt = at
        pin.attemptCount += 1
        return DeliveryPinResult.Accepted(repository.save(pin).toSnapshot())
    }

    private fun hash(rawPin: String): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(rawPin.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}

private fun DeliveryPin.toSnapshot() = DeliveryPinSnapshot(requireNotNull(id), deliveryMissionId, expiresAt, usedAt, attemptCount)
