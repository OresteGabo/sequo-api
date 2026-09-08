package dev.orestegabo.sequo_api.domain.notification

import org.springframework.data.jpa.repository.JpaRepository

interface DeviceFcmTokenRepository : JpaRepository<DeviceFcmToken, String> {
    fun findByFcmTokenHash(fcmTokenHash: String): DeviceFcmToken?

    fun findFirstByUserIdAndDeviceIdAndAppFamilyAndStatus(
        userId: String,
        deviceId: String,
        appFamily: NotificationAppFamily,
        status: DeviceFcmTokenStatus,
    ): DeviceFcmToken?

    fun findByUserIdAndAppFamilyAndStatus(
        userId: String,
        appFamily: NotificationAppFamily,
        status: DeviceFcmTokenStatus,
    ): List<DeviceFcmToken>
}

interface NotificationMessageRepository : JpaRepository<NotificationMessage, String> {
    fun findByEventIdAndRecipientUserIdAndAppFamilyAndEventType(
        eventId: String,
        recipientUserId: String,
        appFamily: NotificationAppFamily,
        eventType: NotificationEventType,
    ): NotificationMessage?

    fun findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId: String): List<NotificationMessage>

    fun findByIdAndRecipientUserId(id: String, recipientUserId: String): NotificationMessage?
}

interface NotificationDeliveryRepository : JpaRepository<NotificationDelivery, String> {
    fun findByMessageId(messageId: String): List<NotificationDelivery>
}

interface NotificationOutboxRepository : JpaRepository<NotificationOutbox, String> {
    fun findByEventId(eventId: String): NotificationOutbox?
    fun countByStatusIn(statuses: Collection<NotificationOutboxStatus>): Long
    fun findTop20ByStatusInOrderByUpdatedAtAsc(statuses: Collection<NotificationOutboxStatus>): List<NotificationOutbox>
}
