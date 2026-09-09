package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import java.time.LocalTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification_preference_repository;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class NotificationPreferenceRepositoryTest @Autowired constructor(
    private val preferenceRepository: NotificationPreferenceRepository,
    private val userRepository: UserRepository,
) {
    @BeforeTest
    fun cleanDatabase() {
        preferenceRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun persistsDefaultAndEventSpecificPreferences() {
        val userId = createUser("notification-preferences@sequo.test")

        preferenceRepository.save(
            NotificationPreference(
                userId = userId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationPreferenceEventType.ALL,
                pushEnabled = false,
                quietHoursStart = LocalTime.of(22, 0),
                quietHoursEnd = LocalTime.of(7, 0),
            )
        )
        preferenceRepository.save(
            NotificationPreference(
                userId = userId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationPreferenceEventType.RELAY_PICKUP_CODE_CREATED,
                smsEnabled = false,
            )
        )

        val defaultPreference = assertNotNull(
            preferenceRepository.findByUserIdAndAppFamilyAndEventType(
                userId,
                NotificationAppFamily.SEQUO_CUSTOMER,
                NotificationPreferenceEventType.ALL,
            )
        )
        val relayPreference = assertNotNull(
            preferenceRepository.findByUserIdAndAppFamilyAndEventType(
                userId,
                NotificationAppFamily.SEQUO_CUSTOMER,
                NotificationPreferenceEventType.RELAY_PICKUP_CODE_CREATED,
            )
        )

        assertEquals(false, defaultPreference.pushEnabled)
        assertEquals(LocalTime.of(22, 0), defaultPreference.quietHoursStart)
        assertEquals(false, relayPreference.smsEnabled)
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
