package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Transactional
class AdminLoginRequestRepositoryTest @Autowired constructor(
    private val repository: AdminLoginRequestRepository,
) {
    @BeforeEach
    fun cleanDatabase() {
        repository.deleteAll()
    }

    @Test
    fun storesAndFindsPendingRequestsByEmailAndStatus() {
        val now = Instant.parse("2026-10-12T08:00:00Z")
        val saved = repository.save(
            AdminLoginRequest(
                emailNormalized = "admin@sequo.test",
                codeHash = "h".repeat(64),
                deviceLabel = "Chrome on MacBook Pro",
                browserInfo = "Chrome 141",
                requestedAt = now,
                expiresAt = now.plusSeconds(180),
            )
        )

        val found = repository.findByIdAndStatus(requireNotNull(saved.id), AdminLoginRequestStatus.PENDING)
        val pending = repository.findAllByEmailNormalizedAndStatusAndExpiresAtAfterOrderByRequestedAtDesc(
            "admin@sequo.test",
            AdminLoginRequestStatus.PENDING,
            now.plusSeconds(30),
        )

        assertNotNull(found)
        assertEquals(listOf(saved.id), pending.map { it.id })
    }

    @Test
    fun lifecycleFieldsCanRecordApprovalRejectionAndConsumption() {
        val now = Instant.parse("2026-10-12T08:00:00Z")
        val request = repository.save(
            AdminLoginRequest(
                emailNormalized = "admin-lifecycle@sequo.test",
                codeHash = "a".repeat(64),
                requestedAt = now,
                expiresAt = now.plusSeconds(180),
            )
        )

        request.status = AdminLoginRequestStatus.APPROVED
        request.approvedAt = now.plusSeconds(20)
        request.approvedByUserId = "admin-user-1"
        request.consumedAt = now.plusSeconds(40)
        request.status = AdminLoginRequestStatus.CONSUMED
        repository.saveAndFlush(request)

        val persisted = repository.findById(requireNotNull(request.id)).orElseThrow()
        assertEquals(AdminLoginRequestStatus.CONSUMED, persisted.status)
        assertEquals("admin-user-1", persisted.approvedByUserId)
        assertTrue(requireNotNull(persisted.consumedAt).isAfter(persisted.requestedAt))
    }

    @Test
    fun entityRejectsBlankEmailAndInvalidExpiry() {
        val now = Instant.parse("2026-10-12T08:00:00Z")

        assertFailsWith<IllegalArgumentException> {
            AdminLoginRequest(
                emailNormalized = " ",
                codeHash = "hash",
                requestedAt = now,
                expiresAt = now.plusSeconds(1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AdminLoginRequest(
                emailNormalized = "admin@sequo.test",
                codeHash = "hash",
                requestedAt = now,
                expiresAt = now,
            )
        }
    }
}
