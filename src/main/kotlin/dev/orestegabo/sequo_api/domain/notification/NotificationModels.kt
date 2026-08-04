package dev.orestegabo.sequo_api.domain.notification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

enum class NotificationAppFamily {
    SEQUO_CUSTOMER,
    SEQUO_MERCHANT,
    SEQUO_HUB,
    SEQUO_RIDER,
    SEQUO_ADMIN,
}

enum class NotificationPlatform {
    ANDROID,
    IOS,
    WEB,
}

enum class DeviceFcmTokenStatus {
    ACTIVE,
    REVOKED,
    STALE,
    FAILED,
}

enum class NotificationChannel {
    IN_APP,
    FCM,
    WEBSOCKET,
    SMS,
    EMAIL,
    WHATSAPP,
}

enum class NotificationDeliveryStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    SUPPRESSED,
}

enum class NotificationOutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED_RETRYABLE,
    FAILED_FINAL,
}

enum class NotificationSeverity {
    INFO,
    ACTION_REQUIRED,
    URGENT,
    SECURITY,
    FINANCIAL,
}

enum class NotificationEventType {
    ORDER_CREATED,
    PAYMENT_CONFIRMED,
    BARGAINING_PROPOSAL_CREATED,
    BARGAINING_COUNTERED,
    BARGAINING_ACCEPTED,
    MERCHANT_ACCEPTED_ORDER,
    MERCHANT_REJECTED_ORDER,
    ORDER_PREPARING,
    ORDER_READY_FOR_PICKUP,
    RIDER_MISSION_OFFERED,
    RIDER_ACCEPTED_MISSION,
    RIDER_PICKED_UP,
    RIDER_ARRIVED,
    DIRECT_DELIVERED,
    RELAY_PARCEL_DEPOSITED,
    RELAY_PICKUP_CODE_CREATED,
    RELAY_PARCEL_DELAYED,
    RETURN_REQUESTED,
    RETURN_PIN_CREATED,
    RETURN_RECEIVED_BY_SEQUO,
    REFUND_TRIGGERED,
    MERCHANT_PAYOUT_ELIGIBLE,
    PAYOUT_SENT,
    DELIVERY_PROBLEM_REPORTED,
    MISSING_DEPOT_BLOCKED_TOUR,
}

@Entity
@Table(name = "device_fcm_tokens")
class DeviceFcmToken(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "user_id", nullable = false)
    var userId: String,

    @Column(name = "device_id", nullable = false)
    var deviceId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "app_family", nullable = false, length = 64)
    var appFamily: NotificationAppFamily,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 32)
    var platform: NotificationPlatform,

    @Column(name = "fcm_token_hash", nullable = false, length = 128)
    var fcmTokenHash: String,

    @Column(name = "fcm_token_ciphertext", nullable = false, length = 2000)
    var fcmTokenCiphertext: String,

    @Column(name = "app_version", length = 64)
    var appVersion: String? = null,

    @Column(name = "locale", length = 32)
    var locale: String? = null,

    @Column(name = "timezone", length = 128)
    var timezone: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: DeviceFcmTokenStatus = DeviceFcmTokenStatus.ACTIVE,

    @Column(name = "last_seen_at")
    var lastSeenAt: Instant? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "notification_messages")
class NotificationMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "event_id", nullable = false)
    val eventId: String,

    @Column(name = "recipient_user_id", nullable = false)
    val recipientUserId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "app_family", nullable = false, length = 64)
    val appFamily: NotificationAppFamily,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 128)
    val eventType: NotificationEventType,

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 64)
    val severity: NotificationSeverity,

    @Column(name = "title", nullable = false, length = 255)
    val title: String,

    @Column(name = "body", nullable = false, length = 1000)
    val body: String,

    @Column(name = "action_url", length = 500)
    val actionUrl: String? = null,

    @Column(name = "payload", length = 4000)
    val payload: String? = null,

    @Column(name = "read_at")
    var readAt: Instant? = null,

    @Column(name = "archived_at")
    var archivedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "notification_deliveries")
class NotificationDelivery(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "message_id", nullable = false)
    val messageId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 64)
    val channel: NotificationChannel,

    @Column(name = "target_ref", nullable = false)
    val targetRef: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    var status: NotificationDeliveryStatus = NotificationDeliveryStatus.PENDING,

    @Column(name = "provider_reference")
    var providerReference: String? = null,

    @Column(name = "failure_code", length = 128)
    var failureCode: String? = null,

    @Column(name = "failure_message", length = 500)
    var failureMessage: String? = null,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

@Entity
@Table(name = "notification_outbox")
class NotificationOutbox(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "event_id", nullable = false, unique = true)
    val eventId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 128)
    val eventType: NotificationEventType,

    @Column(name = "aggregate_type", nullable = false, length = 128)
    val aggregateType: String,

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: String,

    @Column(name = "payload", length = 4000)
    val payload: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    var status: NotificationOutboxStatus = NotificationOutboxStatus.PENDING,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null,

    @Column(name = "locked_by", length = 128)
    var lockedBy: String? = null,

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
