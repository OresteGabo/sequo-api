package dev.orestegabo.sequo_api.domain.relay

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

enum class RelayParcelStatus {
    Created,
    Deposited,
    PickedUp,
    CollectedBySequo,
    Delayed,
    ReturnToSellerReview,
    ReturnedToSeller,
    Problem,
}

enum class RelayCustodyEventType {
    Deposit,
    Pickup,
    RelayRelease,
    SequoCollection,
    ReturnDropoff,
    Problem,
}

data class RelayLocker(
    val id: String,
    val relayPointId: String,
    val active: Boolean,
    val occupied: Boolean,
) {
    init {
        require(id.isNotBlank()) { "id is required." }
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
    }
}

data class RelayParcel(
    val id: String,
    val relayPointId: String,
    val lockerId: String?,
    val orderId: String?,
    val deliveryMissionId: String?,
    val returnId: String?,
    val category: RelayParcelCategory,
    val depositCode: String,
    val status: RelayParcelStatus,
    val depositedAt: Instant?,
    val pickedUpAt: Instant? = null,
    val collectedAt: Instant? = null,
    val custodyEvents: List<RelayCustodyEvent> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "id is required." }
        require(relayPointId.isNotBlank()) { "relayPointId is required." }
        require(depositCode.isNotBlank()) { "depositCode is required." }
    }
}

data class RelayPickupCode(
    val id: String,
    val relayParcelId: String,
    val codeHash: String,
    val qrNonceHash: String?,
    val identityCheckRequired: Boolean,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val attemptCount: Int = 0,
    val createdAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "id is required." }
        require(relayParcelId.isNotBlank()) { "relayParcelId is required." }
        require(codeHash.isNotBlank()) { "codeHash is required." }
        require(attemptCount >= 0) { "attemptCount cannot be negative." }
    }
}

data class RelayCustodyEvent(
    val id: String,
    val relayParcelId: String,
    val actorUserId: String?,
    val type: RelayCustodyEventType,
    val metadata: String,
    val idempotencyKey: String?,
    val createdAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "id is required." }
        require(relayParcelId.isNotBlank()) { "relayParcelId is required." }
    }
}

data class RelayParcelCreateCommand(
    val parcelId: String,
    val relayPointId: String,
    val orderId: String? = null,
    val deliveryMissionId: String? = null,
    val returnId: String? = null,
    val category: RelayParcelCategory,
    val depositCode: String,
    val availableLockers: List<RelayLocker>,
    val createdAt: Instant,
)

data class RelayPickupCodeCreateCommand(
    val codeId: String,
    val parcel: RelayParcel,
    val rawNumericCode: String,
    val rawQrNonce: String?,
    val identityCheckRequired: Boolean = true,
    val expiresAt: Instant,
    val createdAt: Instant,
)

data class RelayPickupVerificationCommand(
    val parcel: RelayParcel,
    val pickupCode: RelayPickupCode,
    val relayPointId: String,
    val actorUserId: String,
    val rawNumericCode: String?,
    val rawQrNonce: String?,
    val identityDocumentMatched: Boolean,
    val eventId: String,
    val idempotencyKey: String,
    val verifiedAt: Instant,
)

data class RelayParcelAccepted(
    val parcel: RelayParcel,
    val pickupCode: RelayPickupCode? = null,
    val event: RelayCustodyEvent? = null,
)

data class RelayParcelRejection(
    val code: String,
    val message: String,
    val pickupCode: RelayPickupCode? = null,
)

sealed class RelayParcelServiceResult {
    data class Accepted(val value: RelayParcelAccepted) : RelayParcelServiceResult()
    data class Rejected(val rejection: RelayParcelRejection) : RelayParcelServiceResult()
}

class RelayParcelService(
    private val policy: RelayParcelPolicy = RelayParcelPolicy(),
    private val credentialHasher: RelayCredentialHasher = Sha256RelayCredentialHasher(),
    private val maxPickupAttempts: Int = 5,
) {
    init {
        require(maxPickupAttempts > 0) { "maxPickupAttempts must be positive." }
    }

    fun createParcel(command: RelayParcelCreateCommand): RelayParcelServiceResult {
        validateCreate(command)?.let { return RelayParcelServiceResult.Rejected(it) }
        if (!policy.relayPickupAllowed(command.category)) {
            return rejected("relay_not_allowed_for_category", "Food and perishable parcels cannot use relay pickup.")
        }

        val locker = command.availableLockers
            .firstOrNull { it.relayPointId == command.relayPointId && it.active && !it.occupied }
            ?: return rejected("no_available_locker", "No active free locker is available at this relay point.")
        val event = RelayCustodyEvent(
            id = "${command.parcelId}:deposit",
            relayParcelId = command.parcelId,
            actorUserId = null,
            type = RelayCustodyEventType.Deposit,
            metadata = "Parcel deposited at relay ${command.relayPointId} in locker ${locker.id}.",
            idempotencyKey = command.depositCode,
            createdAt = command.createdAt,
        )

        return RelayParcelServiceResult.Accepted(
            RelayParcelAccepted(
                parcel = RelayParcel(
                    id = command.parcelId,
                    relayPointId = command.relayPointId,
                    lockerId = locker.id,
                    orderId = command.orderId,
                    deliveryMissionId = command.deliveryMissionId,
                    returnId = command.returnId,
                    category = command.category,
                    depositCode = command.depositCode,
                    status = RelayParcelStatus.Deposited,
                    depositedAt = command.createdAt,
                    custodyEvents = listOf(event),
                    createdAt = command.createdAt,
                    updatedAt = command.createdAt,
                ),
                event = event,
            )
        )
    }

    fun createPickupCode(command: RelayPickupCodeCreateCommand): RelayParcelServiceResult {
        if (command.codeId.isBlank()) return rejected("missing_code_id", "Pickup code id is required.")
        if (!command.rawNumericCode.matches(NUMERIC_CODE_PATTERN)) {
            return rejected("invalid_pickup_code", "Pickup code must contain exactly 6 digits.")
        }
        if (command.rawQrNonce != null && command.rawQrNonce.length < MIN_QR_NONCE_LENGTH) {
            return rejected("invalid_qr_nonce", "QR nonce is too short.")
        }
        if (!command.expiresAt.isAfter(command.createdAt)) {
            return rejected("invalid_pickup_expiry", "Pickup credential expiry must be after creation.")
        }
        if (command.parcel.status != RelayParcelStatus.Deposited) {
            return rejected("parcel_not_deposited", "Pickup credentials require a deposited relay parcel.")
        }

        return RelayParcelServiceResult.Accepted(
            RelayParcelAccepted(
                parcel = command.parcel,
                pickupCode = RelayPickupCode(
                    id = command.codeId,
                    relayParcelId = command.parcel.id,
                    codeHash = credentialHasher.hash(command.rawNumericCode),
                    qrNonceHash = command.rawQrNonce?.let(credentialHasher::hash),
                    identityCheckRequired = command.identityCheckRequired,
                    expiresAt = command.expiresAt,
                    createdAt = command.createdAt,
                ),
            )
        )
    }

    fun verifyPickup(command: RelayPickupVerificationCommand): RelayParcelServiceResult {
        if (command.idempotencyKey.isBlank()) return rejected("missing_idempotency_key", "Idempotency key is required.")
        if (command.eventId.isBlank()) return rejected("missing_event_id", "Custody event id is required.")
        if (command.actorUserId.isBlank()) return rejected("missing_actor", "Relay actor id is required.")
        val existingEvent = command.parcel.custodyEvents.firstOrNull {
            it.idempotencyKey == command.idempotencyKey && it.type == RelayCustodyEventType.RelayRelease
        }
        if (existingEvent != null && command.parcel.status == RelayParcelStatus.PickedUp) {
            return RelayParcelServiceResult.Accepted(
                RelayParcelAccepted(command.parcel, command.pickupCode, existingEvent)
            )
        }
        if (command.parcel.relayPointId != command.relayPointId) {
            return rejectedWithAttempt("relay_scope_mismatch", "Relay partner cannot release another relay point's parcel.", command)
        }
        if (command.pickupCode.relayParcelId != command.parcel.id) {
            return rejectedWithAttempt("pickup_code_parcel_mismatch", "Pickup code belongs to another parcel.", command)
        }
        if (command.parcel.status != RelayParcelStatus.Deposited) {
            return rejected("parcel_not_releasable", "Only deposited parcels can be released to customer pickup.")
        }
        if (command.pickupCode.usedAt != null) {
            return rejected("pickup_code_already_used", "Pickup code was already used.")
        }
        if (command.pickupCode.attemptCount >= maxPickupAttempts) {
            return rejected("pickup_attempts_exhausted", "Pickup credential attempts are exhausted.", command.pickupCode)
        }
        if (!command.verifiedAt.isBefore(command.pickupCode.expiresAt)) {
            return rejectedWithAttempt("pickup_code_expired", "Pickup credential is expired.", command)
        }
        if (command.pickupCode.identityCheckRequired && !command.identityDocumentMatched) {
            return rejectedWithAttempt("identity_check_failed", "Identity validation is required before relay release.", command)
        }
        if (!credentialMatches(command)) {
            return rejectedWithAttempt("invalid_pickup_credential", "Pickup code or QR credential is invalid.", command)
        }

        val usedCode = command.pickupCode.copy(
            usedAt = command.verifiedAt,
            attemptCount = command.pickupCode.attemptCount + 1,
        )
        val event = RelayCustodyEvent(
            id = command.eventId,
            relayParcelId = command.parcel.id,
            actorUserId = command.actorUserId,
            type = RelayCustodyEventType.RelayRelease,
            metadata = "Parcel released after pickup credential and identity validation.",
            idempotencyKey = command.idempotencyKey,
            createdAt = command.verifiedAt,
        )
        val releasedParcel = command.parcel.copy(
            status = RelayParcelStatus.PickedUp,
            pickedUpAt = command.verifiedAt,
            custodyEvents = command.parcel.custodyEvents + event,
            updatedAt = command.verifiedAt,
        )

        return RelayParcelServiceResult.Accepted(
            RelayParcelAccepted(releasedParcel, usedCode, event)
        )
    }

    fun markDelayedIfNeeded(parcel: RelayParcel, evaluatedAt: Instant): RelayParcel {
        val depositedAt = parcel.depositedAt ?: return parcel
        val decision = policy.evaluateCustody(
            RelayParcelCustodyInput(
                category = parcel.category,
                depositedAt = depositedAt,
                evaluatedAt = evaluatedAt,
                pickedUpAt = parcel.pickedUpAt,
                collectedAt = parcel.collectedAt,
            )
        )

        return when (decision.action) {
            RelayParcelCustodyAction.StorageFeeEligible ->
                parcel.copy(status = RelayParcelStatus.Delayed, updatedAt = evaluatedAt)
            RelayParcelCustodyAction.ReturnToSellerReviewDue ->
                parcel.copy(status = RelayParcelStatus.ReturnToSellerReview, updatedAt = evaluatedAt)
            RelayParcelCustodyAction.Active,
            RelayParcelCustodyAction.AlreadyResolved -> parcel
        }
    }

    private fun credentialMatches(command: RelayPickupVerificationCommand): Boolean {
        val numericMatches = command.rawNumericCode?.let { credentialHasher.matches(it, command.pickupCode.codeHash) } == true
        val qrMatches = command.rawQrNonce != null &&
            command.pickupCode.qrNonceHash != null &&
            credentialHasher.matches(command.rawQrNonce, command.pickupCode.qrNonceHash)

        return numericMatches || qrMatches
    }

    private fun rejectedWithAttempt(
        code: String,
        message: String,
        command: RelayPickupVerificationCommand,
    ): RelayParcelServiceResult.Rejected =
        rejected(code, message, command.pickupCode.copy(attemptCount = command.pickupCode.attemptCount + 1))

    private fun validateCreate(command: RelayParcelCreateCommand): RelayParcelRejection? {
        if (command.parcelId.isBlank()) return RelayParcelRejection("missing_parcel_id", "Parcel id is required.")
        if (command.relayPointId.isBlank()) return RelayParcelRejection("missing_relay_point", "Relay point id is required.")
        if (command.depositCode.isBlank()) return RelayParcelRejection("missing_deposit_code", "Deposit code is required.")
        if (command.orderId.isNullOrBlank() && command.deliveryMissionId.isNullOrBlank() && command.returnId.isNullOrBlank()) {
            return RelayParcelRejection("missing_source_reference", "Parcel requires an order, delivery mission, or return reference.")
        }

        return null
    }

    private fun rejected(
        code: String,
        message: String,
        pickupCode: RelayPickupCode? = null,
    ): RelayParcelServiceResult.Rejected =
        RelayParcelServiceResult.Rejected(RelayParcelRejection(code, message, pickupCode))

    private companion object {
        val NUMERIC_CODE_PATTERN = Regex("\\d{6}")
        const val MIN_QR_NONCE_LENGTH = 16
    }
}

interface RelayCredentialHasher {
    fun hash(rawCredential: String): String
    fun matches(rawCredential: String, hash: String): Boolean = hash(rawCredential) == hash
}

class Sha256RelayCredentialHasher : RelayCredentialHasher {
    override fun hash(rawCredential: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawCredential.encodeToByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
