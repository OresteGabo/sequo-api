package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@Transactional
class AdminAuthControllerStatusTest @Autowired constructor(
    private val controller: AdminAuthController,
    private val repository: AdminLoginRequestRepository,
    private val authRateLimiter: AuthRateLimiter,
) {
    @BeforeEach
    fun cleanDatabase() {
        authRateLimiter.resetForTests()
        repository.deleteAll()
    }

    @Test
    fun statusEndpointReturnsPendingWithoutSensitiveFields() {
        val start = controller.startLoginRequest(
            AdminAuthController.LoginRequestStartRequest(email = "status@sequo.test")
        ).body as AdminAuthController.LoginRequestStartResponse

        val response = controller.loginRequestStatus(start.requestId)
        val body = response.body as AdminAuthController.LoginRequestStatusResponse

        assertEquals(200, response.statusCode.value())
        assertEquals(AdminLoginRequestStatus.PENDING, body.status)
        assertEquals(start.expiresAt, body.expiresAt)
        assertNull(body.approvedAt)
        assertNull(body.rejectedAt)
    }

    @Test
    fun statusEndpointReturnsExpiredForPastPendingRequest() {
        val now = Instant.parse("2020-10-12T08:00:00Z")
        val request = repository.save(
            AdminLoginRequest(
                emailNormalized = "expired@sequo.test",
                codeHash = "h".repeat(64),
                requestedAt = now,
                expiresAt = now.plusSeconds(1),
            )
        )

        val snapshot = controller.loginRequestStatus(requireNotNull(request.id)).body as AdminAuthController.LoginRequestStatusResponse
        assertEquals(AdminLoginRequestStatus.EXPIRED, snapshot.status)
    }

    @Test
    fun statusEndpointReturnsApprovedAndRejectedTimestamps() {
        val now = Instant.now()
        val approved = repository.save(
            AdminLoginRequest(
                emailNormalized = "approved@sequo.test",
                codeHash = "a".repeat(64),
                requestedAt = now,
                expiresAt = now.plusSeconds(180),
                status = AdminLoginRequestStatus.APPROVED,
                approvedAt = now.plusSeconds(5),
            )
        )
        val rejected = repository.save(
            AdminLoginRequest(
                emailNormalized = "rejected@sequo.test",
                codeHash = "b".repeat(64),
                requestedAt = now,
                expiresAt = now.plusSeconds(180),
                status = AdminLoginRequestStatus.REJECTED,
                rejectedAt = now.plusSeconds(6),
            )
        )

        val approvedStatus = controller.loginRequestStatus(requireNotNull(approved.id)).body as AdminAuthController.LoginRequestStatusResponse
        val rejectedStatus = controller.loginRequestStatus(requireNotNull(rejected.id)).body as AdminAuthController.LoginRequestStatusResponse

        assertEquals(AdminLoginRequestStatus.APPROVED, approvedStatus.status)
        assertEquals(now.plusSeconds(5), approvedStatus.approvedAt)
        assertEquals(AdminLoginRequestStatus.REJECTED, rejectedStatus.status)
        assertEquals(now.plusSeconds(6), rejectedStatus.rejectedAt)
    }

    @Test
    fun statusEndpointReturnsNotFoundForUnknownRequest() {
        val response = controller.loginRequestStatus("missing-request")

        assertEquals(404, response.statusCode.value())
    }
}
