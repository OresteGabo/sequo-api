package dev.orestegabo.sequo_api.domain.auth

import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
class AdminEmailConcurrencyTest @Autowired constructor(
    private val controller: AdminAuthController,
    private val codes: AdminEmailCodeRepository,
    private val users: UserRepository,
    private val devices: UserDeviceRepository,
    private val limiter: AuthRateLimiter,
) {
    @MockitoBean private lateinit var sender: AdminEmailCodeSender

    @BeforeEach
    fun reset() {
        limiter.resetForTests(); Mockito.reset(sender)
        devices.deleteAll(); codes.deleteAll(); users.deleteAll()
    }

    @Test
    fun simultaneousVerificationConsumesTheEmailCodeOnlyOnce() {
        users.save(User(email = "concurrent-email@sequo.test", roles = mutableSetOf(RoleCode.ADMIN)))
        controller.requestEmailCode(AdminAuthController.EmailCodeRequest("concurrent-email@sequo.test"))
        val captor = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(sender).send(Mockito.anyString(), captor.capture() ?: "", Mockito.any(Instant::class.java) ?: Instant.EPOCH)
        val code = captor.value
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val responses = (1..2).map { i -> executor.submit<Int> {
                check(start.await(10, TimeUnit.SECONDS))
                controller.verifyEmailCode(AdminAuthController.EmailCodeVerifyRequest("concurrent-email@sequo.test", code, "concurrent-email-device-$i")).statusCode.value()
            } }
            start.countDown()
            assertEquals(listOf(200, 401), responses.map { it.get(20, TimeUnit.SECONDS) }.sorted())
            assertEquals(1, devices.count())
            assertEquals(listOf(AdminEmailCodeStatus.CONSUMED), codes.findAll().map { it.status })
        } finally { executor.shutdownNow() }
    }
}
