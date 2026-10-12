package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Transactional
class AdminAuthControllerStartRequestTest @Autowired constructor(
    private val controller: AdminAuthController,
    private val repository: AdminLoginRequestRepository,
    private val authRateLimiter: AuthRateLimiter,
    private val codeService: AdminLoginCodeService,
) {
    @BeforeEach
    fun cleanDatabase() {
        authRateLimiter.resetForTests()
        repository.deleteAll()
    }

    @Test
    fun startLoginRequestCreatesPendingRequestAndReturnsDisplayCode() {
        val response = controller.startLoginRequest(
            AdminAuthController.LoginRequestStartRequest(
                email = "Admin.User+Web@gmail.com",
                deviceLabel = "Chrome on MacBook Pro",
                browserInfo = "Chrome 141",
            )
        )

        val body = response.body as AdminAuthController.LoginRequestStartResponse
        val saved = repository.findById(body.requestId).orElseThrow()

        assertEquals(200, response.statusCode.value())
        assertTrue(body.code.matches(Regex("^[A-Z2-9]{3}-[A-Z2-9]{3}$")))
        assertEquals(2, body.pollingIntervalSeconds)
        assertEquals(AdminLoginRequestStatus.PENDING, saved.status)
        assertEquals("adminuser@gmail.com", saved.emailNormalized)
        assertNotEquals(codeService.normalize(body.code), saved.codeHash)
        assertNotNull(saved.codeCiphertext)
        assertTrue(codeService.verify(body.code, saved.codeHash))
        assertEquals("Chrome on MacBook Pro", saved.deviceLabel)
    }

    @Test
    fun invalidEmailReturnsSafeErrorWithoutCreatingRequest() {
        val response = controller.startLoginRequest(
            AdminAuthController.LoginRequestStartRequest(email = "not-an-email")
        )

        val body = response.body as AdminAuthController.AdminAuthErrorResponse
        assertEquals(400, response.statusCode.value())
        assertEquals("invalid_admin_login_request", body.code)
        assertEquals(0, repository.count())
    }

    @Test
    fun repeatedRequestsAreRateLimited() {
        val request = AdminAuthController.LoginRequestStartRequest(email = "rate-limit@sequo.test")

        repeat(5) {
            assertEquals(200, controller.startLoginRequest(request).statusCode.value())
        }
        val blocked = controller.startLoginRequest(request)

        assertEquals(429, blocked.statusCode.value())
        assertNotNull(blocked.headers["Retry-After"])
    }
}
