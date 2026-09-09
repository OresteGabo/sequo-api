package dev.orestegabo.sequo_api.domain.relay

import java.time.Instant
import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.settlement.SettlementPersistenceService
import dev.orestegabo.sequo_api.domain.settlement.SettlementSourceType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RelayParcelPersistenceServiceTest @Autowired constructor(
    private val persistence: RelayParcelPersistenceService,
    private val application: RelayParcelApplicationService,
    private val parcelRepository: RelayParcelRecordRepository,
    private val pickupCodeRepository: RelayPickupCodeRecordRepository,
    private val eventRepository: RelayCustodyEventRecordRepository,
    private val settlements: SettlementPersistenceService,
    private val userRepository: UserRepository,
) {
    private val now = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun persistsParcelAndDepositEvent() {
        val accepted = RelayParcelService().createParcel(createCommand()) as RelayParcelServiceResult.Accepted

        val saved = persistence.saveCreated(accepted)
        val record = parcelRepository.findById("parcel-persistence-1").orElseThrow()
        val event = eventRepository.findById("parcel-persistence-1:deposit").orElseThrow()

        assertEquals(saved.id, record.id)
        assertEquals(RelayParcelStatus.Deposited, record.status)
        assertEquals(RelayParcelCategory.GeneralGoods, record.category)
        assertEquals("locker-persistence", record.lockerId)
        assertEquals(RelayCustodyEventType.Deposit, event.type)
        assertEquals("deposit-persistence-1", event.idempotencyKey)
    }

    @Test
    fun persistsPickupCredentialWithoutRawSecrets() {
        val service = RelayParcelService()
        val parcel = (service.createParcel(createCommand()) as RelayParcelServiceResult.Accepted).value.parcel
        val accepted = service.createPickupCode(
            RelayPickupCodeCreateCommand(
                codeId = "pickup-persistence-1",
                parcel = parcel,
                rawNumericCode = "123456",
                rawQrNonce = "qr-persistence-nonce",
                expiresAt = now.plusSeconds(3600),
                createdAt = now,
            )
        ) as RelayParcelServiceResult.Accepted

        val saved = persistence.savePickupCode(accepted)
        val record = pickupCodeRepository.findById(saved.id).orElseThrow()

        assertNotNull(record)
        assertNotEquals("123456", record.codeHash)
        assertNotEquals("qr-persistence-nonce", record.qrNonceHash)
        assertTrue(record.codeHash.startsWith("sha256:"))
    }

    @Test
    fun applicationServicePersistsOnlyAcceptedParcelCreation() {
        val rejected = application.createParcel(createCommand(category = RelayParcelCategory.Food))
        val accepted = application.createParcel(createCommand(parcelId = "parcel-application-1", depositCode = "deposit-application-1"))

        assertTrue(rejected is RelayParcelServiceResult.Rejected)
        assertTrue(accepted is RelayParcelServiceResult.Accepted)
        assertTrue(parcelRepository.findById("parcel-application-1").isPresent)
        assertTrue(parcelRepository.findById("parcel-persistence-1").isEmpty)
    }

    @Test
    fun listsOnlyParcelsFromRequestedRelayPointAndStatus() {
        application.createParcel(createCommand(parcelId = "parcel-list-1", depositCode = "deposit-list-1"))
        application.createParcel(
            createCommand(
                parcelId = "parcel-list-2",
                depositCode = "deposit-list-2",
            ).copy(
                relayPointId = "relay-other",
                availableLockers = listOf(RelayLocker("locker-other", "relay-other", active = true, occupied = false)),
            ),
        )

        val listed = application.listParcels("relay-persistence-1", RelayParcelStatus.Deposited)

        assertEquals(listOf("parcel-list-1"), listed.map { it.id })
    }

    @Test
    fun evaluatesAndPersistsDelayedParcelStatus() {
        application.createParcel(
            createCommand(
                parcelId = "parcel-delayed-1",
                depositCode = "deposit-delayed-1",
                createdAt = now.minusSeconds(15 * 24 * 60 * 60),
            )
        )

        val evaluated = application.evaluateDelayed("relay-persistence-1", now)

        assertEquals(RelayParcelStatus.Delayed, evaluated.single().status)
        assertEquals(RelayParcelStatus.Delayed, parcelRepository.findById("parcel-delayed-1").orElseThrow().status)
    }

    @Test
    fun evaluatesDelayedParcelsAcrossAllRelayPoints() {
        application.createParcel(
            createCommand(
                parcelId = "parcel-delayed-all-1",
                depositCode = "deposit-delayed-all-1",
                createdAt = now.minusSeconds(15 * 24 * 60 * 60),
            )
        )
        application.createParcel(
            createCommand(
                parcelId = "parcel-delayed-all-2",
                depositCode = "deposit-delayed-all-2",
                createdAt = now.minusSeconds(29 * 24 * 60 * 60),
            ).copy(
                relayPointId = "relay-persistence-2",
                availableLockers = listOf(RelayLocker("locker-persistence-2", "relay-persistence-2", active = true, occupied = false)),
            )
        )

        val evaluated = application.evaluateDelayedForAllRelayPoints(now)

        assertEquals(
            setOf("parcel-delayed-all-1", "parcel-delayed-all-2"),
            evaluated.map { it.id }.toSet(),
        )
        assertEquals(RelayParcelStatus.Delayed, parcelRepository.findById("parcel-delayed-all-1").orElseThrow().status)
        assertEquals(RelayParcelStatus.ReturnToSellerReview, parcelRepository.findById("parcel-delayed-all-2").orElseThrow().status)
    }

    @Test
    fun adminCanResolveReturnToSellerReviewExactlyOnce() {
        application.createParcel(
            createCommand(
                parcelId = "parcel-return-review-1",
                depositCode = "deposit-return-review-1",
                createdAt = now.minusSeconds(29 * 24 * 60 * 60),
            )
        )
        application.evaluateDelayed("relay-persistence-1", now)
        val actorId = requireNotNull(
            userRepository.save(User(email = "admin-returns@sequo.test", provider = AuthProvider.EMAIL)).id
        )

        val first = application.returnToSeller(
            parcelId = "parcel-return-review-1",
            actorUserId = actorId,
            eventId = "return-dropoff-1",
            idempotencyKey = "return-review-1",
            metadata = "Seller received the parcel.",
            returnedAt = now.plusSeconds(60),
        )
        val replay = application.returnToSeller(
            parcelId = "parcel-return-review-1",
            actorUserId = actorId,
            eventId = "different-event",
            idempotencyKey = "return-review-1",
            metadata = "Replay.",
            returnedAt = now.plusSeconds(120),
        )

        assertTrue(first is RelayParcelServiceResult.Accepted)
        assertEquals(RelayParcelStatus.ReturnedToSeller, first.value.parcel.status)
        assertTrue(replay is RelayParcelServiceResult.Accepted)
        assertEquals("return-dropoff-1", replay.value.event?.id)
        assertEquals(RelayCustodyEventType.ReturnDropoff, eventRepository.findById("return-dropoff-1").orElseThrow().type)
    }

    @Test
    fun assessesStorageFeesCumulativelyAndPostsOnlyLedgerDeltas() {
        application.createParcel(
            createCommand(
                parcelId = "parcel-storage-fee-1",
                depositCode = "deposit-storage-fee-1",
                createdAt = now.minusSeconds(16 * 24 * 60 * 60),
            )
        )

        val first = application.assessStorageFees("relay-persistence-1", dailyFeeCfa = 250, evaluatedAt = now)
        val repeated = application.assessStorageFees("relay-persistence-1", dailyFeeCfa = 250, evaluatedAt = now)
        val nextDay = application.assessStorageFees("relay-persistence-1", dailyFeeCfa = 250, evaluatedAt = now.plusSeconds(24 * 60 * 60))
        val ledgerEntries = settlements.listLedgerEntries(SettlementSourceType.RelayParcel, "parcel-storage-fee-1")

        assertEquals(1, first.size)
        assertEquals(2, first.single().chargeableDays)
        assertEquals(500, first.single().totalFeeCfa)
        assertEquals(500, first.single().lastIncrementCfa)
        assertEquals(500, repeated.single().totalFeeCfa)
        assertEquals(0, repeated.single().lastIncrementCfa)
        assertEquals(3, nextDay.single().chargeableDays)
        assertEquals(750, nextDay.single().totalFeeCfa)
        assertEquals(250, nextDay.single().lastIncrementCfa)
        assertEquals(listOf(500, 250), ledgerEntries.map { it.amountCfa })
    }

    private fun createCommand(
        parcelId: String = "parcel-persistence-1",
        depositCode: String = "deposit-persistence-1",
        category: RelayParcelCategory = RelayParcelCategory.GeneralGoods,
        createdAt: Instant = now,
    ) = RelayParcelCreateCommand(
        parcelId = parcelId,
        relayPointId = "relay-persistence-1",
        orderId = "order-persistence-1",
        category = category,
        depositCode = depositCode,
        availableLockers = listOf(RelayLocker("locker-persistence", "relay-persistence-1", active = true, occupied = false)),
        createdAt = createdAt,
    )
}
