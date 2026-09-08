package dev.orestegabo.sequo_api.domain.relay

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelayParcelServiceTest {

    private val service = RelayParcelService()
    private val now = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun createsEligibleRelayParcelWithLockerAndDepositEvent() {
        val result = service.createParcel(createCommand())

        assertTrue(result is RelayParcelServiceResult.Accepted)
        assertEquals(RelayParcelStatus.Deposited, result.value.parcel.status)
        assertEquals("locker-2", result.value.parcel.lockerId)
        assertEquals(RelayCustodyEventType.Deposit, result.value.event?.type)
        assertEquals("deposit-1", result.value.event?.idempotencyKey)
    }

    @Test
    fun rejectsFoodAndPerishableRelayParcels() {
        val food = service.createParcel(createCommand(category = RelayParcelCategory.Food))
        val perishable = service.createParcel(createCommand(category = RelayParcelCategory.Perishable))

        assertTrue(food is RelayParcelServiceResult.Rejected)
        assertEquals("relay_not_allowed_for_category", food.rejection.code)
        assertTrue(perishable is RelayParcelServiceResult.Rejected)
        assertEquals("relay_not_allowed_for_category", perishable.rejection.code)
    }

    @Test
    fun createsHashedPickupCodeAndQrNonce() {
        val parcel = depositedParcel()

        val result = service.createPickupCode(
            RelayPickupCodeCreateCommand(
                codeId = "pickup-code-1",
                parcel = parcel,
                rawNumericCode = "123456",
                rawQrNonce = "qr-nonce-value-2026",
                expiresAt = now.plusSeconds(3600),
                createdAt = now,
            )
        )

        assertTrue(result is RelayParcelServiceResult.Accepted)
        val pickupCode = assertNotNull(result.value.pickupCode)
        assertEquals("pickup-code-1", pickupCode.id)
        assertNotEquals("123456", pickupCode.codeHash)
        assertNotEquals("qr-nonce-value-2026", pickupCode.qrNonceHash)
        assertTrue(pickupCode.codeHash.startsWith("sha256:"))
        assertTrue(pickupCode.qrNonceHash?.startsWith("sha256:") == true)
    }

    @Test
    fun releasesParcelOnlyAfterCredentialAndIdentityValidation() {
        val parcel = depositedParcel()
        val pickupCode = pickupCode(parcel)

        val result = service.verifyPickup(
            verificationCommand(
                parcel = parcel,
                pickupCode = pickupCode,
                rawNumericCode = "123456",
                identityDocumentMatched = true,
            )
        )

        assertTrue(result is RelayParcelServiceResult.Accepted)
        assertEquals(RelayParcelStatus.PickedUp, result.value.parcel.status)
        assertEquals(now.plusSeconds(120), result.value.parcel.pickedUpAt)
        assertEquals(now.plusSeconds(120), result.value.pickupCode?.usedAt)
        assertEquals(1, result.value.pickupCode?.attemptCount)
        assertEquals(RelayCustodyEventType.RelayRelease, result.value.event?.type)
    }

    @Test
    fun qrNonceCanReleaseParcelWhenNumericCodeIsAbsent() {
        val parcel = depositedParcel()
        val pickupCode = pickupCode(parcel)

        val result = service.verifyPickup(
            verificationCommand(
                parcel = parcel,
                pickupCode = pickupCode,
                rawNumericCode = null,
                rawQrNonce = "qr-nonce-value-2026",
                identityDocumentMatched = true,
            )
        )

        assertTrue(result is RelayParcelServiceResult.Accepted)
        assertEquals(RelayParcelStatus.PickedUp, result.value.parcel.status)
    }

    @Test
    fun invalidPickupAttemptIncrementsAttemptCountWithoutReleasingParcel() {
        val parcel = depositedParcel()
        val pickupCode = pickupCode(parcel)

        val result = service.verifyPickup(
            verificationCommand(
                parcel = parcel,
                pickupCode = pickupCode,
                rawNumericCode = "000000",
                identityDocumentMatched = true,
            )
        )

        assertTrue(result is RelayParcelServiceResult.Rejected)
        assertEquals("invalid_pickup_credential", result.rejection.code)
        assertEquals(1, result.rejection.pickupCode?.attemptCount)
    }

    @Test
    fun relayScopeIdentityExpiryAndAttemptLimitAreEnforced() {
        val parcel = depositedParcel()
        val pickupCode = pickupCode(parcel)
        val wrongRelay = service.verifyPickup(
            verificationCommand(
                parcel = parcel,
                pickupCode = pickupCode,
                relayPointId = "relay-other",
                rawNumericCode = "123456",
                identityDocumentMatched = true,
            )
        )
        val identityFailed = service.verifyPickup(
            verificationCommand(
                parcel = parcel,
                pickupCode = pickupCode,
                rawNumericCode = "123456",
                identityDocumentMatched = false,
            )
        )
        val expired = service.verifyPickup(
            verificationCommand(
                parcel = parcel,
                pickupCode = pickupCode.copy(expiresAt = now.plusSeconds(1)),
                rawNumericCode = "123456",
                identityDocumentMatched = true,
            )
        )
        val exhausted = service.verifyPickup(
            verificationCommand(
                parcel = parcel,
                pickupCode = pickupCode.copy(attemptCount = 5),
                rawNumericCode = "123456",
                identityDocumentMatched = true,
            )
        )

        assertTrue(wrongRelay is RelayParcelServiceResult.Rejected)
        assertEquals("relay_scope_mismatch", wrongRelay.rejection.code)
        assertTrue(identityFailed is RelayParcelServiceResult.Rejected)
        assertEquals("identity_check_failed", identityFailed.rejection.code)
        assertTrue(expired is RelayParcelServiceResult.Rejected)
        assertEquals("pickup_code_expired", expired.rejection.code)
        assertTrue(exhausted is RelayParcelServiceResult.Rejected)
        assertEquals("pickup_attempts_exhausted", exhausted.rejection.code)
    }

    @Test
    fun duplicateReleaseWithSameIdempotencyKeyReturnsExistingRelease() {
        val released = service.verifyPickup(
            verificationCommand(
                parcel = depositedParcel(),
                pickupCode = pickupCode(depositedParcel()),
                rawNumericCode = "123456",
                identityDocumentMatched = true,
            )
        ) as RelayParcelServiceResult.Accepted

        val duplicate = service.verifyPickup(
            verificationCommand(
                parcel = released.value.parcel,
                pickupCode = released.value.pickupCode!!,
                rawNumericCode = "000000",
                identityDocumentMatched = false,
            )
        )

        assertTrue(duplicate is RelayParcelServiceResult.Accepted)
        assertEquals(released.value.parcel, duplicate.value.parcel)
        assertEquals(released.value.event, duplicate.value.event)
    }

    @Test
    fun delayedParcelStatusUsesRelayPolicyThresholds() {
        val parcel = depositedParcel()

        val delayed = service.markDelayedIfNeeded(parcel, now.plusSeconds(15 * 24 * 60 * 60))
        val returnReview = service.markDelayedIfNeeded(parcel, now.plusSeconds(29 * 24 * 60 * 60))

        assertEquals(RelayParcelStatus.Delayed, delayed.status)
        assertEquals(RelayParcelStatus.ReturnToSellerReview, returnReview.status)
    }

    private fun depositedParcel(): RelayParcel =
        (service.createParcel(createCommand()) as RelayParcelServiceResult.Accepted).value.parcel

    private fun pickupCode(parcel: RelayParcel): RelayPickupCode =
        (
            service.createPickupCode(
                RelayPickupCodeCreateCommand(
                    codeId = "pickup-code-1",
                    parcel = parcel,
                    rawNumericCode = "123456",
                    rawQrNonce = "qr-nonce-value-2026",
                    expiresAt = now.plusSeconds(3600),
                    createdAt = now,
                )
            ) as RelayParcelServiceResult.Accepted
            ).value.pickupCode!!

    private fun verificationCommand(
        parcel: RelayParcel,
        pickupCode: RelayPickupCode,
        relayPointId: String = "relay-1",
        rawNumericCode: String? = "123456",
        rawQrNonce: String? = null,
        identityDocumentMatched: Boolean,
    ): RelayPickupVerificationCommand =
        RelayPickupVerificationCommand(
            parcel = parcel,
            pickupCode = pickupCode,
            relayPointId = relayPointId,
            actorUserId = "relay-user-1",
            rawNumericCode = rawNumericCode,
            rawQrNonce = rawQrNonce,
            identityDocumentMatched = identityDocumentMatched,
            eventId = "event-release-1",
            idempotencyKey = "release-parcel-1",
            verifiedAt = now.plusSeconds(120),
        )

    private fun createCommand(category: RelayParcelCategory = RelayParcelCategory.GeneralGoods): RelayParcelCreateCommand =
        RelayParcelCreateCommand(
            parcelId = "parcel-1",
            relayPointId = "relay-1",
            orderId = "order-1",
            category = category,
            depositCode = "deposit-1",
            availableLockers = listOf(
                RelayLocker(id = "locker-1", relayPointId = "relay-1", active = true, occupied = true),
                RelayLocker(id = "locker-2", relayPointId = "relay-1", active = true, occupied = false),
                RelayLocker(id = "locker-3", relayPointId = "relay-2", active = true, occupied = false),
            ),
            createdAt = now,
        )
}
