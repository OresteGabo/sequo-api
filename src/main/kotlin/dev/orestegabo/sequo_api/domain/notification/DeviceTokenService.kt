package dev.orestegabo.sequo_api.domain.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId

data class RegisterFcmTokenCommand(
    val userId: String,
    val deviceId: String,
    val fcmToken: String,
    val appFamily: NotificationAppFamily,
    val platform: NotificationPlatform,
    val appVersion: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
) {
    val normalizedAppVersion: String? = appVersion?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedLocale: String? = locale?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedTimezone: String? = timezone?.trim()?.takeIf { it.isNotEmpty() }

    init {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        require(deviceId.isNotBlank()) { "deviceId cannot be blank." }
        require(deviceId.length <= 128) { "deviceId cannot exceed 128 characters." }
        require(fcmToken.isNotBlank()) { "fcmToken cannot be blank." }
        require(fcmToken.length in 32..4096) { "fcmToken must be between 32 and 4096 characters." }
        require(normalizedAppVersion == null || normalizedAppVersion.length <= 64) { "appVersion cannot exceed 64 characters." }
        require(normalizedLocale == null || normalizedLocale.length <= 32) { "locale cannot exceed 32 characters." }
        require(normalizedLocale == null || DeviceTokenMetadataPolicy.isValidLocaleTag(normalizedLocale)) {
            "locale must be a valid BCP-47 style language tag."
        }
        require(normalizedTimezone == null || normalizedTimezone.length <= 128) { "timezone cannot exceed 128 characters." }
        require(normalizedTimezone == null || DeviceTokenMetadataPolicy.isValidTimezone(normalizedTimezone)) {
            "timezone must be a valid timezone id."
        }
    }
}

object DeviceTokenMetadataPolicy {
    private val LocalePattern = Regex("^[a-zA-Z]{2,3}([_-][a-zA-Z0-9]{2,8}){0,2}$")

    fun isValidLocaleTag(value: String): Boolean =
        LocalePattern.matches(value)

    fun isValidTimezone(value: String): Boolean =
        runCatching { ZoneId.of(value) }.isSuccess
}

data class DeviceFcmTokenSnapshot(
    val id: String,
    val userId: String,
    val deviceId: String,
    val appFamily: NotificationAppFamily,
    val platform: NotificationPlatform,
    val tokenHash: String,
    val status: DeviceFcmTokenStatus,
    val appVersion: String?,
    val locale: String?,
    val timezone: String?,
    val lastSeenAt: Instant?,
    val revokedAt: Instant?,
    val updatedAt: Instant,
)

@Service
class DeviceTokenService(
    private val repository: DeviceFcmTokenRepository,
    private val tokenProtector: FcmTokenProtector,
) {
    @Transactional
    fun registerOrRotate(
        command: RegisterFcmTokenCommand,
        occurredAt: Instant = Instant.now(),
    ): DeviceFcmTokenSnapshot {
        val tokenHash = NotificationTokenHashing.sha256Base64Url(command.fcmToken)
        val existingByHash = repository.findByFcmTokenHash(tokenHash)
        if (existingByHash != null) {
            repository.findFirstByUserIdAndDeviceIdAndAppFamilyAndStatus(
                userId = command.userId,
                deviceId = command.deviceId,
                appFamily = command.appFamily,
                status = DeviceFcmTokenStatus.ACTIVE,
            )
                ?.takeIf { it.id != existingByHash.id }
                ?.let {
                    it.status = DeviceFcmTokenStatus.REVOKED
                    it.revokedAt = occurredAt
                    it.updatedAt = occurredAt
                    repository.save(it)
                }

            existingByHash.userId = command.userId
            existingByHash.deviceId = command.deviceId
            existingByHash.appFamily = command.appFamily
            existingByHash.platform = command.platform
            existingByHash.fcmTokenCiphertext = tokenProtector.protect(command.fcmToken)
            existingByHash.status = DeviceFcmTokenStatus.ACTIVE
            existingByHash.revokedAt = null
            existingByHash.touch(command, occurredAt)
            return repository.save(existingByHash).toSnapshot()
        }

        val existingDevice = repository.findFirstByUserIdAndDeviceIdAndAppFamilyAndStatus(
            userId = command.userId,
            deviceId = command.deviceId,
            appFamily = command.appFamily,
            status = DeviceFcmTokenStatus.ACTIVE,
        )
        if (existingDevice != null) {
            existingDevice.status = DeviceFcmTokenStatus.REVOKED
            existingDevice.revokedAt = occurredAt
            existingDevice.updatedAt = occurredAt
            repository.save(existingDevice)
        }

        val token = DeviceFcmToken(
            userId = command.userId,
            deviceId = command.deviceId,
            appFamily = command.appFamily,
            platform = command.platform,
            fcmTokenHash = tokenHash,
            fcmTokenCiphertext = tokenProtector.protect(command.fcmToken),
            appVersion = command.normalizedAppVersion,
            locale = command.normalizedLocale,
            timezone = command.normalizedTimezone,
            status = DeviceFcmTokenStatus.ACTIVE,
            lastSeenAt = occurredAt,
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )
        return repository.save(token).toSnapshot()
    }

    @Transactional
    fun revokeDevice(
        userId: String,
        deviceId: String,
        appFamily: NotificationAppFamily,
        occurredAt: Instant = Instant.now(),
    ): Boolean {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        require(deviceId.isNotBlank()) { "deviceId cannot be blank." }

        val token = repository.findFirstByUserIdAndDeviceIdAndAppFamilyAndStatus(
            userId,
            deviceId,
            appFamily,
            DeviceFcmTokenStatus.ACTIVE,
        )
            ?: return false
        token.status = DeviceFcmTokenStatus.REVOKED
        token.revokedAt = occurredAt
        token.updatedAt = occurredAt
        repository.save(token)
        return true
    }

    @Transactional
    fun markStaleByTokenHash(
        fcmTokenHash: String,
        occurredAt: Instant = Instant.now(),
    ): Boolean {
        require(fcmTokenHash.isNotBlank()) { "fcmTokenHash cannot be blank." }

        val token = repository.findByFcmTokenHash(fcmTokenHash) ?: return false
        token.status = DeviceFcmTokenStatus.STALE
        token.revokedAt = occurredAt
        token.updatedAt = occurredAt
        repository.save(token)
        return true
    }

    @Transactional(readOnly = true)
    fun activeTokens(
        userId: String,
        appFamily: NotificationAppFamily,
    ): List<DeviceFcmTokenSnapshot> {
        require(userId.isNotBlank()) { "userId cannot be blank." }
        return repository.findByUserIdAndAppFamilyAndStatus(userId, appFamily, DeviceFcmTokenStatus.ACTIVE)
            .map { it.toSnapshot() }
    }
}

private fun DeviceFcmToken.touch(command: RegisterFcmTokenCommand, occurredAt: Instant) {
    appVersion = command.normalizedAppVersion
    locale = command.normalizedLocale
    timezone = command.normalizedTimezone
    lastSeenAt = occurredAt
    updatedAt = occurredAt
}

private fun DeviceFcmToken.toSnapshot(): DeviceFcmTokenSnapshot =
    DeviceFcmTokenSnapshot(
        id = requireNotNull(id) { "Persisted FCM token id is required." },
        userId = userId,
        deviceId = deviceId,
        appFamily = appFamily,
        platform = platform,
        tokenHash = fcmTokenHash,
        status = status,
        appVersion = appVersion,
        locale = locale,
        timezone = timezone,
        lastSeenAt = lastSeenAt,
        revokedAt = revokedAt,
        updatedAt = updatedAt,
    )
