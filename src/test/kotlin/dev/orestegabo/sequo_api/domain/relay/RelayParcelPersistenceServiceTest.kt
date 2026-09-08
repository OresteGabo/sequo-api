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

    private fun createCommand() = RelayParcelCreateCommand(
        parcelId = "parcel-persistence-1",
        relayPointId = "relay-persistence-1",
        orderId = "order-persistence-1",
        category = RelayParcelCategory.GeneralGoods,
        depositCode = "deposit-persistence-1",
        availableLockers = listOf(RelayLocker("locker-persistence", "relay-persistence-1", active = true, occupied = false)),
        createdAt = now,
    )
}
