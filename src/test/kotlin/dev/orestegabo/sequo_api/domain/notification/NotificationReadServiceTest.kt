package dev.orestegabo.sequo_api.domain.notification

import java.time.Instant
import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class NotificationReadServiceTest @Autowired constructor(
    private val readService: NotificationReadService,
    private val messageRepository: NotificationMessageRepository,
    private val userRepository: UserRepository,
) {
    @Test
    fun listsOnlyOwnedNonArchivedMessagesByDefault() {
        val first = save("user-inbox", "event-1", "First")
        save("other-user", "event-2", "Other")
        val archived = save("user-inbox", "event-3", "Archived")
        archived.archivedAt = Instant.parse("2026-09-08T10:00:00Z")
        messageRepository.save(archived)

        val messages = readService.listInbox(NotificationInboxQuery(first.recipientUserId))

        assertEquals(listOf(first.id), messages.map { it.id })
        assertEquals("First", messages.single().title)
    }

    @Test
    fun marksReadArchivesAndRestoresOnlyOwnedMessage() {
        val message = save("user-inbox", "event-4", "Read me")
        val ownerId = message.recipientUserId
        val read = readService.markRead(ownerId, requireNotNull(message.id))
        val archived = readService.archive(ownerId, requireNotNull(message.id))
        val hidden = readService.listInbox(NotificationInboxQuery(ownerId))
        val all = readService.listInbox(NotificationInboxQuery(ownerId, includeArchived = true))
        val restored = readService.unarchive(ownerId, requireNotNull(message.id))

        assertTrue(read?.readAt != null)
        assertTrue(archived?.archivedAt != null)
        assertTrue(hidden.isEmpty())
        assertEquals(1, all.size)
        assertNull(restored?.archivedAt)
    }

    @Test
    fun cannotModifyAnotherUsersMessage() {
        val message = save("owner", "event-5", "Private")
        val result = readService.markRead(ensureUser("intruder"), requireNotNull(message.id))

        assertNull(result)
        assertFalse(readService.listInbox(NotificationInboxQuery(ensureUser("intruder"))).any())
    }

    private fun save(userId: String, eventId: String, title: String): NotificationMessage {
        val recipientId = ensureUser(userId)
        return messageRepository.save(
            NotificationMessage(
                eventId = eventId,
                recipientUserId = recipientId,
                appFamily = NotificationAppFamily.SEQUO_CUSTOMER,
                eventType = NotificationEventType.ORDER_CREATED,
                severity = NotificationSeverity.INFO,
                title = title,
                body = "Body",
            )
        )
    }

    private fun ensureUser(userId: String): String {
        val email = "$userId@sequo.test"
        return userRepository.findByEmail(email)?.id ?: requireNotNull(
            userRepository.save(
                User(
                    email = email,
                    passwordHash = "hash",
                    name = userId,
                    provider = AuthProvider.EMAIL,
                )
            ).id
        )
    }
}
