package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@Transactional
class AdminAuthControllerExchangeTest @Autowired constructor(
    private val controller: AdminAuthController,
    private val requestRepository: AdminLoginRequestRepository,
    private val userRepository: UserRepository,
    private val userDeviceRepository: UserDeviceRepository,
    private val authRateLimiter: AuthRateLimiter,
    private val jwtService: JwtService,
) {
    @MockitoBean
    private lateinit var auditService: AdminAuthAuditService

    @BeforeEach
    fun cleanDatabase() {
        Mockito.reset(auditService)
        authRateLimiter.resetForTests()
        userDeviceRepository.deleteAll()
        requestRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun approvedRequestExchangesOnceForAdminWebTokens() {
        val start = controller.startLoginRequest(AdminAuthController.LoginRequestStartRequest("exchange@sequo.test")).body as AdminAuthController.LoginRequestStartResponse
        val admin = userRepository.save(User(email = "exchange@sequo.test", provider = AuthProvider.GOOGLE, roles = mutableSetOf(RoleCode.ADMIN)))
        assertEquals(200, controller.approveMobileLoginRequest(requireNotNull(admin.id), start.requestId).statusCode.value())

        val response = controller.exchangeLoginRequest(start.requestId, AdminAuthController.ExchangeLoginRequest(start.code, "admin-browser-1"))
        val body = response.body as AdminAuthController.ExchangeLoginResponse
        val access = requireNotNull(jwtService.parseAccessToken(body.accessToken))
        val saved = requestRepository.findById(start.requestId).orElseThrow()

        assertEquals(200, response.statusCode.value())
        assertNotNull(body.refreshToken)
        assertEquals(setOf(RoleCode.ADMIN), access.roles)
        assertEquals(AppSource.SEQUO_ADMIN, requireNotNull(userDeviceRepository.findByDeviceId("admin-browser-1")).appSource)
        assertEquals(AdminLoginRequestStatus.CONSUMED, saved.status)
        assertNotNull(saved.consumedAt)
        assertEquals(401, controller.exchangeLoginRequest(start.requestId, AdminAuthController.ExchangeLoginRequest(start.code, "admin-browser-2")).statusCode.value())

        val auditEvents = capturedAuditEvents()
        assertEquals(
            listOf(
                AdminAuthAuditEventType.LOGIN_REQUEST_CREATED,
                AdminAuthAuditEventType.LOGIN_REQUEST_APPROVED,
                AdminAuthAuditEventType.LOGIN_REQUEST_EXCHANGED,
            ),
            auditEvents.map { it.type },
        )
        assertEquals(listOf(start.requestId, start.requestId, start.requestId), auditEvents.map { it.requestId })
        assertNull(auditEvents.first().userId)
        assertEquals(requireNotNull(admin.id), auditEvents[1].userId)
        assertEquals(requireNotNull(admin.id), auditEvents[2].userId)
    }

    @Test
    fun wrongCodeCannotExchange() {
        val start = controller.startLoginRequest(AdminAuthController.LoginRequestStartRequest("wrong-code@sequo.test")).body as AdminAuthController.LoginRequestStartResponse
        val admin = userRepository.save(User(email = "wrong-code@sequo.test", provider = AuthProvider.GOOGLE, roles = mutableSetOf(RoleCode.ADMIN)))
        controller.approveMobileLoginRequest(requireNotNull(admin.id), start.requestId)

        val response = controller.exchangeLoginRequest(start.requestId, AdminAuthController.ExchangeLoginRequest("ABC-DEF", "admin-browser-wrong"))

        assertEquals(401, response.statusCode.value())
        assertNull(userDeviceRepository.findByDeviceId("admin-browser-wrong"))
    }

    @Test
    fun pendingAndRejectedRequestsCannotExchange() {
        val pending = controller.startLoginRequest(AdminAuthController.LoginRequestStartRequest("pending-exchange@sequo.test")).body as AdminAuthController.LoginRequestStartResponse
        val rejected = controller.startLoginRequest(AdminAuthController.LoginRequestStartRequest("rejected-exchange@sequo.test")).body as AdminAuthController.LoginRequestStartResponse
        val admin = userRepository.save(User(email = "rejected-exchange@sequo.test", provider = AuthProvider.GOOGLE, roles = mutableSetOf(RoleCode.ADMIN)))
        controller.rejectMobileLoginRequest(requireNotNull(admin.id), rejected.requestId, AdminAuthController.RejectLoginRequest("not_me"))

        assertEquals(401, controller.exchangeLoginRequest(pending.requestId, AdminAuthController.ExchangeLoginRequest(pending.code)).statusCode.value())
        assertEquals(401, controller.exchangeLoginRequest(rejected.requestId, AdminAuthController.ExchangeLoginRequest(rejected.code)).statusCode.value())
    }

    private fun capturedAuditEvents(): List<AdminAuthAuditEvent> {
        val captor = ArgumentCaptor.forClass(AdminAuthAuditEvent::class.java)
        Mockito.verify(auditService, Mockito.atLeastOnce()).record(
            captor.capture() ?: AdminAuthAuditEvent(AdminAuthAuditEventType.LOGIN_REQUEST_CREATED)
        )
        return captor.allValues
    }
}
