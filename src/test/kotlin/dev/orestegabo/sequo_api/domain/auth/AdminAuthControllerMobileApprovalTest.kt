package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@Transactional
class AdminAuthControllerMobileApprovalTest @Autowired constructor(
    private val controller: AdminAuthController,
    private val requestRepository: AdminLoginRequestRepository,
    private val userRepository: UserRepository,
    private val authRateLimiter: AuthRateLimiter,
) {
    @BeforeEach
    fun cleanDatabase() {
        authRateLimiter.resetForTests()
        requestRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun adminCanApproveMatchingPendingRequest() {
        val admin = admin("approve@sequo.test")
        val request = pendingRequest("approve@sequo.test")

        val response = controller.approveMobileLoginRequest(requireNotNull(admin.id), requireNotNull(request.id))
        val body = response.body as AdminAuthController.MobileLoginApprovalResponse
        val saved = requestRepository.findById(requireNotNull(request.id)).orElseThrow()

        assertEquals(200, response.statusCode.value())
        assertEquals(AdminLoginRequestStatus.APPROVED, body.status)
        assertNotNull(body.approvedAt)
        assertEquals(requireNotNull(admin.id), saved.approvedByUserId)
    }

    @Test
    fun adminCanRejectMatchingPendingRequestWithSanitizedReason() {
        val admin = admin("reject@sequo.test")
        val request = pendingRequest("reject@sequo.test")

        val response = controller.rejectMobileLoginRequest(
            requireNotNull(admin.id),
            requireNotNull(request.id),
            AdminAuthController.RejectLoginRequest("not_me"),
        )
        val body = response.body as AdminAuthController.MobileLoginApprovalResponse
        val saved = requestRepository.findById(requireNotNull(request.id)).orElseThrow()

        assertEquals(200, response.statusCode.value())
        assertEquals(AdminLoginRequestStatus.REJECTED, body.status)
        assertNotNull(body.rejectedAt)
        assertEquals("not_me", saved.rejectionReason)
    }

    @Test
    fun normalUserCannotApproveAdminLoginRequest() {
        val customer = userRepository.save(
            User(
                email = "approve-customer@sequo.test",
                provider = AuthProvider.GOOGLE,
                roles = mutableSetOf(RoleCode.CUSTOMER),
            )
        )
        val request = pendingRequest("approve-customer@sequo.test")

        val response = controller.approveMobileLoginRequest(requireNotNull(customer.id), requireNotNull(request.id))
        val body = response.body as AdminAuthController.AdminAuthErrorResponse

        assertEquals(403, response.statusCode.value())
        assertEquals("admin_login_approval_denied", body.code)
        assertEquals(AdminLoginRequestStatus.PENDING, requestRepository.findById(requireNotNull(request.id)).orElseThrow().status)
    }

    @Test
    fun adminCannotApproveDifferentEmailRequestUnlessSuperAdmin() {
        val admin = admin("admin-one@sequo.test")
        val request = pendingRequest("admin-two@sequo.test")

        val denied = controller.approveMobileLoginRequest(requireNotNull(admin.id), requireNotNull(request.id))

        assertEquals(403, denied.statusCode.value())
        assertEquals(AdminLoginRequestStatus.PENDING, requestRepository.findById(requireNotNull(request.id)).orElseThrow().status)
    }

    @Test
    fun superAdminCanApproveDifferentEmailRequest() {
        val superAdmin = userRepository.save(
            User(
                email = "super-approver@sequo.test",
                provider = AuthProvider.APPLE,
                roles = mutableSetOf(RoleCode.SUPER_ADMIN),
            )
        )
        val request = pendingRequest("target-admin@sequo.test")

        val response = controller.approveMobileLoginRequest(requireNotNull(superAdmin.id), requireNotNull(request.id))

        assertEquals(200, response.statusCode.value())
        assertEquals(AdminLoginRequestStatus.APPROVED, requestRepository.findById(requireNotNull(request.id)).orElseThrow().status)
    }

    @Test
    fun expiredRequestCannotBeApproved() {
        val admin = admin("expired-approval@sequo.test")
        val now = Instant.now().minusSeconds(300)
        val request = requestRepository.save(
            AdminLoginRequest(
                emailNormalized = "expired-approval@sequo.test",
                codeHash = "e".repeat(64),
                requestedAt = now,
                expiresAt = now.plusSeconds(1),
            )
        )

        val response = controller.approveMobileLoginRequest(requireNotNull(admin.id), requireNotNull(request.id))

        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun approvedRequestCannotBeRejectedAgain() {
        val admin = admin("already@sequo.test")
        val request = pendingRequest("already@sequo.test").apply {
            status = AdminLoginRequestStatus.APPROVED
        }
        requestRepository.saveAndFlush(request)

        val response = controller.rejectMobileLoginRequest(
            requireNotNull(admin.id),
            requireNotNull(request.id),
            AdminAuthController.RejectLoginRequest("not_me"),
        )

        assertEquals(403, response.statusCode.value())
    }

    private fun admin(email: String): User = userRepository.save(
        User(
            email = email,
            provider = AuthProvider.GOOGLE,
            roles = mutableSetOf(RoleCode.ADMIN),
        )
    )

    private fun pendingRequest(email: String): AdminLoginRequest {
        val now = Instant.now()
        return requestRepository.save(
            AdminLoginRequest(
                emailNormalized = email,
                codeHash = "h".repeat(64),
                requestedAt = now,
                expiresAt = now.plusSeconds(180),
            )
        )
    }
}
