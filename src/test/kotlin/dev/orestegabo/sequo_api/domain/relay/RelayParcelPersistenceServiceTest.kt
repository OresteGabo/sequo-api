package dev.orestegabo.sequo_api.domain.relay

import java.time.Instant
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
