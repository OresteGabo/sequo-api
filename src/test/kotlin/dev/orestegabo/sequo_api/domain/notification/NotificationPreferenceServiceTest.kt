package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification_preference_service;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class NotificationPreferenceServiceTest @Autowired constructor(
    private val service: NotificationPreferenceService,
    private val preferenceRepository: NotificationPreferenceRepository,
    private val userRepository: UserRepository,
) {
    @BeforeTest
    fun cleanDatabase() {
        preferenceRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun resolvesPermissiveDefaultsWhenUserHasNoPreference() {
        val userId = createUser("preference-default@sequo.test")

        val preference = service.resolve(
            userId,
            NotificationAppFamily.SEQUO_CUSTOMER,
            NotificationEventType.ORDER_CREATED,
        )

        assertEquals(NotificationPreferenceEventType.ALL, preference.eventType)
        assertTrue(preference.pushEnabled)
        assertTrue(preference.inAppEnabled)
        assertTrue(preference.smsEnabled)
    }

    @Test
    fun resolvesGlobalPreferenceWhenSpecificPreferenceIsMissing() {
        val userId = createUser("preference-global@sequo.test")
        preferenceRepository.save(
            NotificationPreference(
                userId = userId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationPreferenceEventType.ALL,
                pushEnabled = false,
            )
        )

        val preference = service.resolve(
            userId,
            NotificationAppFamily.SEQUO_CUSTOMER,
            NotificationEventType.ORDER_CREATED,
        )

        assertEquals(NotificationPreferenceEventType.ALL, preference.eventType)
        assertEquals(false, preference.pushEnabled)
    }

    @Test
    fun eventSpecificPreferenceOverridesGlobalPreference() {
        val userId = createUser("preference-specific@sequo.test")
        preferenceRepository.save(
            NotificationPreference(
                userId = userId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationPreferenceEventType.ALL,
                pushEnabled = false,
                smsEnabled = false,
            )
        )
        preferenceRepository.save(
            NotificationPreference(
                userId = userId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationPreferenceEventType.RELAY_PICKUP_CODE_CREATED,
                pushEnabled = true,
                smsEnabled = true,
            )
        )

        val preference = service.resolve(
            userId,
            NotificationAppFamily.SEQUO_CUSTOMER,
            NotificationEventType.RELAY_PICKUP_CODE_CREATED,
        )

        assertEquals(NotificationPreferenceEventType.RELAY_PICKUP_CODE_CREATED, preference.eventType)
        assertTrue(preference.pushEnabled)
        assertTrue(preference.smsEnabled)
    }

    @Test
    fun savePreferenceCreatesAndUpdatesWithoutDuplicates() {
        val userId = createUser("preference-save@sequo.test")
        val first = service.savePreference(
            SaveNotificationPreferenceCommand(
                userId = userId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationPreferenceEventType.ORDER_CREATED,
                pushEnabled = false,
            )
        )

        val second = service.savePreference(
            SaveNotificationPreferenceCommand(
                userId = userId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationPreferenceEventType.ORDER_CREATED,
                pushEnabled = true,
                smsEnabled = false,
            )
        )

        assertEquals(first.id, second.id)
        assertTrue(second.pushEnabled)
        assertEquals(false, second.smsEnabled)
        assertEquals(1, preferenceRepository.count())
    }

    private fun createUser(email: String): String =
        requireNotNull(
            userRepository.save(
                User(
                    email = email,
                    passwordHash = "hash",
                    name = "Notification Preferences",
                    provider = AuthProvider.EMAIL,
                )
            ).id
        )
}
