package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@Transactional
class AdminAuthControllerEmailCodeTest @Autowired constructor(
    private val controller: AdminAuthController,
    private val emailCodeRepository: AdminEmailCodeRepository,
    private val userRepository: UserRepository,
    private val userDeviceRepository: UserDeviceRepository,
    private val authRateLimiter: AuthRateLimiter,
    private val codeService: AdminLoginCodeService,
    private val jwtService: JwtService,
) {
    @MockitoBean
    private lateinit var emailCodeSender: AdminEmailCodeSender

    @BeforeEach
    fun cleanDatabase() {
        authRateLimiter.resetForTests()
        Mockito.reset(emailCodeSender)
        userDeviceRepository.deleteAll()
        emailCodeRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun eligibleAdminReceivesCodeAndCanVerifyForTokens() {
        userRepository.save(User(email = "admin-email@sequo.test", provider = AuthProvider.GOOGLE, roles = mutableSetOf(RoleCode.ADMIN)))

        val requestResponse = controller.requestEmailCode(AdminAuthController.EmailCodeRequest("admin-email@sequo.test"))
        val requestBody = requestResponse.body as AdminAuthController.EmailCodeRequestResponse
        val code = capturedSentCode()

        val verifyResponse = controller.verifyEmailCode(
            AdminAuthController.EmailCodeVerifyRequest(
                email = "admin-email@sequo.test",
                code = code,
                deviceId = "admin-email-browser",
            )
        )
        val verifyBody = verifyResponse.body as AdminAuthController.ExchangeLoginResponse
        val access = requireNotNull(jwtService.parseAccessToken(verifyBody.accessToken))
        val savedCode = requireNotNull(emailCodeRepository.findFirstByEmailNormalizedAndStatusOrderByRequestedAtDesc("admin-email@sequo.test", AdminEmailCodeStatus.CONSUMED))

        assertEquals(200, requestResponse.statusCode.value())
        assertEquals("SENT_IF_ELIGIBLE", requestBody.status)
        assertEquals(300, requestBody.expiresInSeconds)
        assertEquals(60, requestBody.retryAfterSeconds)
        assertEquals(200, verifyResponse.statusCode.value())
        assertEquals(setOf(RoleCode.ADMIN), access.roles)
        assertEquals(AppSource.SEQUO_ADMIN, requireNotNull(userDeviceRepository.findByDeviceId("admin-email-browser")).appSource)
        assertNotNull(savedCode.consumedAt)
    }

    @Test
    fun nonExistingEmailReturnsGenericSuccessButSendsNothing() {
        val response = controller.requestEmailCode(AdminAuthController.EmailCodeRequest("missing-admin@sequo.test"))
        val body = response.body as AdminAuthController.EmailCodeRequestResponse

        assertEquals(200, response.statusCode.value())
        assertEquals("SENT_IF_ELIGIBLE", body.status)
        assertEquals(0, emailCodeRepository.count())
        Mockito.verifyNoInteractions(emailCodeSender)
    }

    @Test
    fun repeatedEmailCodeRequestsAndWrongVerificationsAreRateLimited() {
        userRepository.save(User(email = "otp-limit@sequo.test", provider = AuthProvider.GOOGLE, roles = mutableSetOf(RoleCode.ADMIN)))
        val request = AdminAuthController.EmailCodeRequest("otp-limit@sequo.test")

        repeat(5) {
            assertEquals(200, controller.requestEmailCode(request).statusCode.value())
        }
        val blockedRequest = controller.requestEmailCode(request)

        assertEquals(429, blockedRequest.statusCode.value())
        assertNotNull(blockedRequest.headers["Retry-After"])

        authRateLimiter.resetForTests()
        repeat(8) {
            assertEquals(
                401,
                controller.verifyEmailCode(
                    AdminAuthController.EmailCodeVerifyRequest("otp-limit@sequo.test", "ABC-DEF", "otp-limit-wrong-$it")
                ).statusCode.value(),
            )
        }
        val blockedVerify = controller.verifyEmailCode(
            AdminAuthController.EmailCodeVerifyRequest("otp-limit@sequo.test", "ABC-DEF", "otp-limit-blocked")
        )

        assertEquals(429, blockedVerify.statusCode.value())
        assertNotNull(blockedVerify.headers["Retry-After"])
        assertNull(userDeviceRepository.findByDeviceId("otp-limit-blocked"))
    }

    @Test
    fun wrongExpiredAndReusedCodesDoNotIssueTokens() {
        val admin = userRepository.save(User(email = "otp-cases@sequo.test", provider = AuthProvider.GOOGLE, roles = mutableSetOf(RoleCode.ADMIN)))
        controller.requestEmailCode(AdminAuthController.EmailCodeRequest("otp-cases@sequo.test"))
        val validCode = capturedSentCode()

        val wrong = controller.verifyEmailCode(AdminAuthController.EmailCodeVerifyRequest("otp-cases@sequo.test", "ABC-DEF", "wrong-device"))
        assertEquals(401, wrong.statusCode.value())
        assertNull(userDeviceRepository.findByDeviceId("wrong-device"))

        val success = controller.verifyEmailCode(AdminAuthController.EmailCodeVerifyRequest("otp-cases@sequo.test", validCode, "first-device"))
        val reused = controller.verifyEmailCode(AdminAuthController.EmailCodeVerifyRequest("otp-cases@sequo.test", validCode, "reuse-device"))
        assertEquals(200, success.statusCode.value())
        assertEquals(401, reused.statusCode.value())
        assertNull(userDeviceRepository.findByDeviceId("reuse-device"))

        val generated = codeService.generate(now = Instant.parse("2026-10-12T10:00:00Z"))
        emailCodeRepository.save(
            AdminEmailCode(
                emailNormalized = "otp-cases@sequo.test",
                codeHash = generated.codeHash,
                requestedAt = Instant.now().minus(Duration.ofMinutes(10)),
                expiresAt = Instant.now().minus(Duration.ofMinutes(5)),
                userId = requireNotNull(admin.id),
            )
        )
        val expired = controller.verifyEmailCode(AdminAuthController.EmailCodeVerifyRequest("otp-cases@sequo.test", generated.code, "expired-device"))
        assertEquals(401, expired.statusCode.value())
        assertNull(userDeviceRepository.findByDeviceId("expired-device"))
    }

    @Test
    fun nonAdminCannotReceiveUsableCode() {
        userRepository.save(User(email = "customer-only@sequo.test", provider = AuthProvider.GOOGLE, roles = mutableSetOf(RoleCode.CUSTOMER)))

        val response = controller.requestEmailCode(AdminAuthController.EmailCodeRequest("customer-only@sequo.test"))

        assertEquals(200, response.statusCode.value())
        assertEquals(0, emailCodeRepository.count())
        Mockito.verifyNoInteractions(emailCodeSender)
    }

    private fun capturedSentCode(): String {
        val codeCaptor = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(emailCodeSender).send(
            Mockito.anyString(),
            codeCaptor.capture() ?: "",
            Mockito.any(Instant::class.java) ?: Instant.EPOCH,
        )
        return codeCaptor.value
    }
}
