package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import java.time.LocalTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification_preference_controller;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
    ]
)
class NotificationPreferenceControllerTest @Autowired constructor(
    private val controller: NotificationPreferenceController,
    private val preferenceRepository: NotificationPreferenceRepository,
    private val userRepository: UserRepository,
) {
    @BeforeTest
    fun cleanDatabase() {
        preferenceRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun effectivePreferenceRequiresAuthentication() {
        val response = controller.effectivePreference(
            userId = null,
            appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
            eventType = NotificationEventType.ORDER_CREATED,
        )

        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun returnsEffectivePreferenceForAuthenticatedUser() {
        val userId = createUser("preference-controller@sequo.test")
        preferenceRepository.save(
            NotificationPreference(
                userId = userId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationPreferenceEventType.ALL,
                pushEnabled = false,
            )
        )

        val response = controller.effectivePreference(
            userId = userId,
            appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
            eventType = NotificationEventType.ORDER_CREATED,
        )

        val body = response.body as NotificationPreferenceController.PreferenceResponse
        assertEquals(200, response.statusCode.value())
        assertEquals(NotificationPreferenceEventType.ALL, body.eventType)
        assertEquals(false, body.pushEnabled)
    }

    @Test
    fun savesPreferenceForAuthenticatedUser() {
        val userId = createUser("preference-controller-save@sequo.test")

        val response = controller.savePreference(
            userId = userId,
            appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
            request = NotificationPreferenceController.SavePreferenceRequest(
                eventType = NotificationPreferenceEventType.ORDER_CREATED,
                pushEnabled = false,
                inAppEnabled = true,
                smsEnabled = false,
                quietHoursStart = LocalTime.of(22, 0),
                quietHoursEnd = LocalTime.of(7, 0),
            ),
        )

        val body = response.body as NotificationPreferenceController.PreferenceResponse
        assertEquals(200, response.statusCode.value())
        assertEquals(NotificationPreferenceEventType.ORDER_CREATED, body.eventType)
        assertEquals(false, body.pushEnabled)
        assertEquals(false, body.smsEnabled)
        assertEquals("22:00", body.quietHoursStart)
        assertEquals(1, preferenceRepository.count())
    }

    @Test
    fun rejectsInvalidPreferenceUpdate() {
        val userId = createUser("preference-controller-invalid@sequo.test")

        val response = controller.savePreference(
            userId = userId,
            appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
            request = NotificationPreferenceController.SavePreferenceRequest(
                quietHoursStart = LocalTime.of(22, 0),
                quietHoursEnd = null,
            ),
        )

        assertEquals(400, response.statusCode.value())
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
