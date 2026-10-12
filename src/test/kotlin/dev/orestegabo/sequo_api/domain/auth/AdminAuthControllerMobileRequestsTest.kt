package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Transactional
class AdminAuthControllerMobileRequestsTest @Autowired constructor(
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
    fun adminUserSeesPendingRequestsForTheirEmailOnly() {
        val admin = userRepository.save(
            User(
                email = "admin@sequo.test",
                provider = AuthProvider.GOOGLE,
                roles = mutableSetOf(RoleCode.ADMIN),
            )
        )
        val now = Instant.now()
        requestRepository.save(
            AdminLoginRequest(
                emailNormalized = "admin@sequo.test",
                codeHash = "a".repeat(64),
                requestedAt = now,
                expiresAt = now.plusSeconds(180),
                deviceLabel = "Chrome on Mac",
                browserInfo = "Chrome 141",
            )
        )
        requestRepository.save(
            AdminLoginRequest(
                emailNormalized = "other@sequo.test",
                codeHash = "b".repeat(64),
                requestedAt = now,
                expiresAt = now.plusSeconds(180),
            )
        )
        requestRepository.save(
            AdminLoginRequest(
                emailNormalized = "admin@sequo.test",
                codeHash = "c".repeat(64),
                requestedAt = now.minusSeconds(300),
                expiresAt = now.minusSeconds(1),
            )
        )

        val response = controller.mobileLoginRequests(requireNotNull(admin.id))
        val body = response.body as List<*>
        val item = body.single() as AdminAuthController.MobileLoginRequestSummaryResponse

        assertEquals(200, response.statusCode.value())
        assertEquals("***-***", item.code)
        assertEquals("Chrome on Mac", item.deviceLabel)
        assertEquals("Chrome 141", item.browserInfo)
    }

    @Test
    fun superAdminCanSeePendingRequestsForTheirEmail() {
        val superAdmin = userRepository.save(
            User(
                email = "super@sequo.test",
                provider = AuthProvider.APPLE,
                roles = mutableSetOf(RoleCode.SUPER_ADMIN),
            )
        )
        val now = Instant.now()
        requestRepository.save(
            AdminLoginRequest(
                emailNormalized = "super@sequo.test",
                codeHash = "d".repeat(64),
                requestedAt = now,
                expiresAt = now.plusSeconds(180),
            )
        )

        val response = controller.mobileLoginRequests(requireNotNull(superAdmin.id))
        val body = response.body as List<*>

        assertEquals(200, response.statusCode.value())
        assertEquals(1, body.size)
    }

    @Test
    fun normalUserCannotListAdminLoginRequests() {
        val customer = userRepository.save(
            User(
                email = "customer@sequo.test",
                provider = AuthProvider.GOOGLE,
                roles = mutableSetOf(RoleCode.CUSTOMER),
            )
        )

        val response = controller.mobileLoginRequests(requireNotNull(customer.id))
        val body = response.body as AdminAuthController.AdminAuthErrorResponse

        assertEquals(403, response.statusCode.value())
        assertEquals("admin_login_approval_forbidden", body.code)
    }

    @Test
    fun missingPrincipalIsUnauthorized() {
        val response = controller.mobileLoginRequests(null)

        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun adminWithoutEmailGetsEmptyList() {
        val admin = userRepository.save(
            User(
                provider = AuthProvider.GOOGLE,
                roles = mutableSetOf(RoleCode.ADMIN),
            )
        )

        val response = controller.mobileLoginRequests(requireNotNull(admin.id))
        val body = response.body as List<*>

        assertEquals(200, response.statusCode.value())
        assertTrue(body.isEmpty())
    }
}
