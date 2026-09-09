package dev.orestegabo.sequo_api.domain.notification

import dev.orestegabo.sequo_api.domain.bargaining.BargainingOffer
import dev.orestegabo.sequo_api.domain.bargaining.BargainingOfferStatus
import dev.orestegabo.sequo_api.domain.bargaining.BargainingOfferType
import dev.orestegabo.sequo_api.domain.bargaining.BargainingSession
import dev.orestegabo.sequo_api.domain.bargaining.BargainingSessionScope
import dev.orestegabo.sequo_api.domain.bargaining.BargainingStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

class BargainingRealtimeServiceTest {
    private val realtimePublisher = mock(RealtimeNotificationPublisher::class.java)
    private val service = BargainingRealtimeService(realtimePublisher)

    @Test
    fun publishesBargainingUpdateToCustomerAndMerchant() {
        val session = sampleSession()

        val result = service.publishUpdate(session, Instant.parse("2026-09-09T11:00:00Z"))

        val invocations = mockingDetails(realtimePublisher).invocations.toList()
        assertEquals("bargaining-1", result.sessionId)
        assertEquals(listOf("customer-1", "merchant-1"), result.participantUserIds)
        assertEquals(2, invocations.size)
        assertEquals(listOf("customer-1", "merchant-1"), invocations.map { it.arguments[0] })
        assertEquals(listOf(RealtimeUserQueue.BARGAINING, RealtimeUserQueue.BARGAINING), invocations.map { it.arguments[1] })
    }

    private fun sampleSession(): BargainingSession =
        BargainingSession(
            id = "bargaining-1",
            scope = BargainingSessionScope(
                customerId = "customer-1",
                merchantId = "merchant-1",
                productId = "product-1",
                variantId = null,
                catalogPriceCfa = 10_000,
                bargainingEnabled = true,
            ),
            status = BargainingStatus.CustomerOffered,
            customerAttemptsUsed = 1,
            offers = listOf(
                BargainingOffer(
                    id = "offer-1",
                    offeredByUserId = "customer-1",
                    type = BargainingOfferType.CustomerOffer,
                    amountCfa = 9_000,
                    status = BargainingOfferStatus.Pending,
                    createdAt = Instant.parse("2026-09-09T10:59:00Z"),
                )
            ),
            createdAt = Instant.parse("2026-09-09T10:58:00Z"),
            updatedAt = Instant.parse("2026-09-09T10:59:00Z"),
        )
}
