package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:device_token_service;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class DeviceTokenServiceTest @Autowired constructor(
    private val service: DeviceTokenService,
    private val tokenRepository: DeviceFcmTokenRepository,
    private val userRepository: UserRepository,
    private val tokenProtector: FcmTokenProtector,
) {
    @BeforeTest
    fun cleanDatabase() {
        tokenRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun registersActiveFcmTokenWithoutStoringRawToken() {
        val userId = createUser("notification-customer@sequo.test")
        val rawToken = "fcm_" + "a".repeat(64)
        val occurredAt = Instant.parse("2026-08-02T12:00:00Z")

        val snapshot = service.registerOrRotate(
            sampleCommand(userId = userId, fcmToken = rawToken),
            occurredAt = occurredAt,
        )

        val persisted = tokenRepository.findById(snapshot.id).orElseThrow()
        assertEquals(DeviceFcmTokenStatus.ACTIVE, persisted.status)
        assertEquals(occurredAt, persisted.lastSeenAt)
        assertEquals(snapshot.tokenHash, persisted.fcmTokenHash)
        assertNotEquals(rawToken, persisted.fcmTokenHash)
        assertNotEquals(rawToken, persisted.fcmTokenCiphertext)
        assertEquals(rawToken, tokenProtector.reveal(persisted.fcmTokenCiphertext))
    }

    @Test
    fun rotatesDeviceTokenAndKeepsRevokedHistoryForSameDevice() {
        val userId = createUser("rotation-customer@sequo.test")
        val appFamily = NotificationAppFamily.SEQUO_CUSTOMER

        val first = service.registerOrRotate(
            sampleCommand(userId = userId, deviceId = "device-1", appFamily = appFamily, fcmToken = "fcm_" + "o".repeat(64)),
            occurredAt = Instant.parse("2026-08-02T12:00:00Z"),
        )
        val second = service.registerOrRotate(
            sampleCommand(userId = userId, deviceId = "device-1", appFamily = appFamily, fcmToken = "fcm_" + "n".repeat(64)),
            occurredAt = Instant.parse("2026-08-02T12:05:00Z"),
        )

        val oldToken = tokenRepository.findById(first.id).orElseThrow()
        val newToken = tokenRepository.findById(second.id).orElseThrow()
        assertEquals(DeviceFcmTokenStatus.REVOKED, oldToken.status)
        assertEquals(DeviceFcmTokenStatus.ACTIVE, newToken.status)
        assertEquals(listOf(second.id), service.activeTokens(userId, appFamily).map { it.id })
    }

    @Test
    fun reusesExistingTokenHashWhenTheSameTokenChecksInAgain() {
        val userId = createUser("same-token@sequo.test")
        val first = service.registerOrRotate(
            sampleCommand(userId = userId, appVersion = "1.0.0", locale = "fr-TG"),
            occurredAt = Instant.parse("2026-08-02T12:00:00Z"),
        )

        val second = service.registerOrRotate(
            sampleCommand(userId = userId, appVersion = "1.0.1", locale = "en-US"),
            occurredAt = Instant.parse("2026-08-02T12:10:00Z"),
        )

        assertEquals(first.id, second.id)
        assertEquals("1.0.1", second.appVersion)
        assertEquals("en-US", second.locale)
        assertEquals(1, tokenRepository.count())
    }

    @Test
    fun rejectsUnboundedOrTinyMobileTokenInputs() {
        val userId = createUser("invalid-token@sequo.test")

        assertFailsWith<IllegalArgumentException> { sampleCommand(userId = userId, fcmToken = "short") }
        assertFailsWith<IllegalArgumentException> { sampleCommand(userId = userId, fcmToken = "x".repeat(4097)) }
        assertFailsWith<IllegalArgumentException> { sampleCommand(userId = userId, deviceId = "device-" + "x".repeat(128)) }
    }

    @Test
    fun normalizesOptionalMobileMetadataBeforePersistence() {
        val userId = createUser("metadata-normalized@sequo.test")

        val snapshot = service.registerOrRotate(
            sampleCommand(
                userId = userId,
                appVersion = "  1.2.3  ",
                locale = "  fr-TG  ",
                timezone = "  Africa/Lome  ",
            )
        )

        assertEquals("1.2.3", snapshot.appVersion)
        assertEquals("fr-TG", snapshot.locale)
        assertEquals("Africa/Lome", snapshot.timezone)
    }

    @Test
    fun rejectsMalformedMobileMetadata() {
        val userId = createUser("metadata-invalid@sequo.test")

        assertFailsWith<IllegalArgumentException> { sampleCommand(userId = userId, locale = "not a locale") }
        assertFailsWith<IllegalArgumentException> { sampleCommand(userId = userId, timezone = "Moon/Base") }
    }

    @Test
    fun revokeDeviceMarksActiveTokenRevoked() {
        val userId = createUser("revoke-customer@sequo.test")
        val snapshot = service.registerOrRotate(sampleCommand(userId = userId))

        val revoked = service.revokeDevice(
            userId = userId,
            deviceId = "device-1",
            appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
            occurredAt = Instant.parse("2026-08-02T13:00:00Z"),
        )

        val persisted = tokenRepository.findById(snapshot.id).orElseThrow()
        assertTrue(revoked)
        assertEquals(DeviceFcmTokenStatus.REVOKED, persisted.status)
        assertEquals(Instant.parse("2026-08-02T13:00:00Z"), persisted.revokedAt)
        assertTrue(service.activeTokens(userId, NotificationAppFamily.SEQUO_CUSTOMER).isEmpty())
    }

    private fun createUser(email: String): String =
        requireNotNull(
            userRepository.save(
                User(
                    email = email,
                    passwordHash = "hash",
                    name = "Notification Customer",
                    provider = AuthProvider.EMAIL,
                )
            ).id
        )

    private fun sampleCommand(
        userId: String,
        deviceId: String = "device-1",
        appFamily: NotificationAppFamily = NotificationAppFamily.SEQUO_CUSTOMER,
        platform: NotificationPlatform = NotificationPlatform.ANDROID,
        fcmToken: String = "fcm_" + "a".repeat(64),
        appVersion: String? = "1.0.0",
        locale: String? = "fr-TG",
        timezone: String? = "Africa/Lome",
    ): RegisterFcmTokenCommand =
        RegisterFcmTokenCommand(
            userId = userId,
            deviceId = deviceId,
            fcmToken = fcmToken,
            appFamily = appFamily,
            platform = platform,
            appVersion = appVersion,
            locale = locale,
            timezone = timezone,
        )
}
