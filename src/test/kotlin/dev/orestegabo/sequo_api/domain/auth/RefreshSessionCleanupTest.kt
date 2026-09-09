package dev.orestegabo.sequo_api.domain.auth

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
class RefreshSessionCleanupTest {
    @Autowired private lateinit var refreshSessionService: RefreshSessionService
    @Autowired private lateinit var refreshSessionRepository: RefreshSessionRepository
    @Autowired private lateinit var tokenService: PasswordResetTokenService

    @BeforeEach
    fun cleanDatabase() {
        refreshSessionRepository.deleteAll()
    }

    @Test
    fun cleanupDeletesOnlySessionsThatAlreadyExpired() {
        val now = Instant.parse("2026-09-09T07:00:00Z")
        refreshSessionService.create("user-1", tokenService.generate().rawToken, now.minusSeconds(1), now.minusSeconds(30))
        refreshSessionService.create("user-1", tokenService.generate().rawToken, now.plusSeconds(60), now)

        assertEquals(1, refreshSessionService.deleteExpired(now))
        assertEquals(1, refreshSessionRepository.count())
    }
}
