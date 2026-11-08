package dev.orestegabo.sequo_api.domain.localfixture

import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.relay.*
import dev.orestegabo.sequo_api.domain.returns.*
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

/** Static synthetic custody scenes, never a provider transfer or an arbitrary mutation API. */
@Component
class LocalReturnRelayFixtureSeeder(
    private val policy: LocalOperationalFixturePolicy,
    private val returns: ReturnPersistenceService,
    private val returnPolicy: CustomerReturnPolicy,
    private val availability: RelayAvailabilityService,
    private val parcels: RelayParcelApplicationService,
    private val parcelPersistence: RelayParcelPersistenceService,
    private val parcelDomain: RelayParcelService,
    private val lockers: RelayLockerRepository,
    private val hubs: HubMobileService,
    transactions: PlatformTransactionManager,
) {
    private val outsideRegistry = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
    }
    private val parcelTransaction = TransactionTemplate(transactions)
    private val stages = linkedMapOf("request" to 0, "awaiting-dropoff" to 0, "dropoff" to 1,
        "collected" to 2, "received" to 3, "accepted" to 4, "refund" to 5)
    private val parcelAges = linkedMapOf("fresh" to Duration.ofHours(1),
        "after-eight-days" to Duration.ofDays(8).plusHours(1), "after-thirty-days" to Duration.ofDays(30).plusHours(1))

    fun seed(context: LocalOperationalFixtureContext): List<LocalFixtureScenario> {
        policy.requireEnabled()
        require(context.prefix == "local-ops-v1") { "Unsupported local fixture namespace." }
        return requireNotNull(outsideRegistry.execute {
            // Preflight predictable capacity failures before creating any missing return scene.
            val existing = stages.keys.associateWith { returns.get(returnId(context, it)) }
            if (stages.any { (stage, rank) -> rank > 0 && existing[stage] == null }) {
                require(availability.available(forReturns = true).any { it.id == context.relayPointIds[0] }) {
                    "The synthetic return relay is unavailable; no operator settings were changed."
                }
            }
            parcelAges.keys.forEachIndexed { index, name ->
                if (parcels.getParcel(parcelId(context, name)) == null) {
                    val slot = lockers.findById(context.id("slot-2-${index + 1}")).orElse(null)
                    require(slot?.relayPointId == context.relayPointIds[1] && slot.status == HubLockerStatus.AVAILABLE) {
                        "A synthetic relay slot is unavailable; no existing custody was overwritten."
                    }
                }
            }
            stages.map { (stage, rank) -> seedReturn(context, stage, rank, existing[stage]) } +
                parcelAges.entries.mapIndexed { index, (name, age) -> seedParcel(context, name, age, index + 1) }
        })
    }

    private fun seedReturn(context: LocalOperationalFixtureContext, stage: String, rank: Int,
        existing: ReturnRequestSnapshot?): LocalFixtureScenario {
        val id = returnId(context, stage)
        val orderId = requireNotNull(context.returnOrderIds[stage]) { "Missing synthetic delivered order." }
        val reason = "[TEST] Local return scenario: $stage"
        val actual = if (existing != null) {
            require(existing.customerId == context.customerId && existing.orderId == orderId && existing.reason == reason) {
                "A reserved return ID belongs to different data; nothing was reset."
            }
            existing // Never re-advance a record after an operator has inspected/rejected/changed it.
        } else {
            var result = accepted(returns.requestForCustomer(context.customerId, id, orderId, reason))
            require(result.id == id) { "This order already has a different return; nothing was replaced." }
            if (rank >= 1) result = accepted(returns.recordRelayDropoff(PersistedRelayDropoffCommand(
                id, context.relayPointIds[0], returnPolicy.credential(context.customerId, orderId, id),
                operatorId = context.relayOperatorIds[0], idempotencyKey = context.id("$stage-return-dropoff"),
                metadata = "[TEST] Synthetic parcel received at the logical return relay; no ID image collected.",
            )))
            if (rank >= 2) result = accepted(returns.recordCollection(PersistedReturnCollectionCommand(
                id, context.adminId, context.id("$stage-return-collection"), "[TEST] Synthetic SEQUO collection recorded.",
            )))
            if (rank >= 3) result = accepted(returns.recordPhysicalReceipt(PersistedPhysicalReceiptCommand(
                id, context.adminId, receivedAt = returns.currentTime(), conditionAssessment = "[TEST] Package received; inspection is pending.",
                responsibility = RefundResponsibility.Merchant, idempotencyKey = context.id("$stage-return-receipt"),
            )))
            if (rank >= 4) result = accepted(returns.recordInspection(PersistedReturnInspectionCommand(
                id, context.adminId, ReturnInspectionDecision.ACCEPTED, "[TEST] Synthetic condition inspection accepted.",
                RefundResponsibility.Merchant, context.id("$stage-return-inspection"),
            )))
            if (rank >= 5) {
                result = accepted(returns.triggerRefund(PersistedRefundTriggerCommand(
                    id, result.requestedRefundCfa, context.id("$stage-return-instruction"), context.adminId,
                )))
                check(result.refundInstruction?.paymentStatus == "NOT_SUBMITTED") { "Local fixtures cannot submit a provider refund." }
            }
            result
        }
        val credentials = if (actual.status == ReturnStatus.AwaitingRelayDropoff) {
            mapOf("returnPin" to returnPolicy.credential(context.customerId, orderId, actual.id))
        } else emptyMap()
        val next = when {
            actual.refundInstruction != null -> "Inspect the recorded refund instruction; no money was sent to a provider."
            actual.inspectionDecision == ReturnInspectionDecision.ACCEPTED -> "Review an explicit refund instruction amount in Returns."
            actual.status == ReturnStatus.ReceivedBySequo -> "Record the actual condition inspection before authorizing an instruction."
            actual.status == ReturnStatus.CollectedBySequo -> "Confirm physical receipt, then inspect the synthetic package."
            actual.status == ReturnStatus.DroppedAtRelay -> "Record SEQUO collection through the normal Returns workflow."
            actual.status == ReturnStatus.AwaitingRelayDropoff -> "Use the test return PIN at the assigned relay's return-intake form."
            else -> "Read current return status and routing instructions; existing operator changes are retained."
        }
        return LocalFixtureScenario(context.id("scene-return-$stage"), "[TEST] Return: $stage",
            "Current persisted status: ${actual.status}. Inspection: ${actual.inspectionDecision ?: "not recorded"}. Refund provider: ${actual.refundInstruction?.paymentStatus ?: "not instructed"}. No funds move.",
            "returns", mapOf("returnId" to actual.id, "orderId" to orderId, "customerId" to context.customerId,
                "relayPointId" to (actual.relayPointId ?: context.relayPointIds[0])), listOf(next), credentials)
    }

    private fun seedParcel(context: LocalOperationalFixtureContext, name: String, age: Duration, slotNumber: Int): LocalFixtureScenario {
        val id = parcelId(context, name)
        val site = context.relayPointIds[1]
        val slotId = context.id("slot-2-$slotNumber")
        val depositCode = context.id("parcel-$name-deposit")
        val orderId = requireNotNull(context.relayOrderIds[name]) { "Missing synthetic relay source order." }
        var actual = parcels.getParcel(id)
        if (actual == null) actual = requireNotNull(parcelTransaction.execute {
            val slot = requireNotNull(lockers.lockForDeposit(slotId)) { "Synthetic logical slot is missing." }
            require(slot.relayPointId == site && slot.status == HubLockerStatus.AVAILABLE) { "Synthetic slot is occupied; existing custody was retained." }
            val depositedAt = context.createdAt.minus(age)
            val created = relayAccepted(parcels.createParcel(RelayParcelCreateCommand(id, site,
                orderId = orderId,
                category = RelayParcelCategory.GeneralGoods, depositCode = depositCode,
                availableLockers = listOf(RelayLocker(slotId, site, active = true, occupied = false)), createdAt = depositedAt)))
            hubs.updateLockerAvailability(LockerAvailabilityCommand(slotId, site, HubLockerStatus.OCCUPIED,
                HubLockerAvailabilityReason.SYSTEM_OCCUPIED, updatedByUserId = context.relayOperatorIds[1]), context.createdAt)
            // Only this newly-created synthetic parcel is evaluated. Site-wide evaluation could touch operator records.
            val updated = parcelDomain.markDelayedIfNeeded(created, context.createdAt)
            if (updated.status != created.status) parcelPersistence.saveProblem(updated, RelayCustodyEvent(
                context.id("parcel-$name-deadline"), id, null,
                if (updated.status == RelayParcelStatus.ReturnToSellerReview) RelayCustodyEventType.Review else RelayCustodyEventType.Delay,
                "[TEST] Synthetic custody age evaluated. No tariff was approved and no fee was assessed.",
                context.id("parcel-$name-deadline"), context.createdAt,
            ))
            if (name == "fresh") relayAccepted(parcels.createPickupCode(id, context.id("parcel-fresh-code"),
                FreshPickupCode, context.id("parcel-fresh-qr"), true,
                context.createdAt.plus(Duration.ofDays(1)), context.createdAt))
            requireNotNull(parcels.getParcel(id))
        })
        require(actual.relayPointId == site && actual.lockerId == slotId && actual.depositCode == depositCode &&
            actual.orderId == orderId && actual.returnId == null && actual.deliveryMissionId == null) {
            "Reserved parcel ID contains different data; nothing was reset."
        }
        val code = parcelPersistence.findPickupCode(id)
        val usable = name == "fresh" && actual.status.awaitingPickup && code?.id == context.id("parcel-fresh-code") &&
            code.usedAt == null && code.attemptCount < 5 && Instant.now().isBefore(code.expiresAt) &&
            Sha256RelayCredentialHasher().matches(FreshPickupCode, code.codeHash)
        val credentials = if (usable) mapOf("pickupCode" to FreshPickupCode, "qrNonce" to context.id("parcel-fresh-qr"),
            "expiresAt" to requireNotNull(code).expiresAt.toString()) else emptyMap()
        return LocalFixtureScenario(context.id("scene-parcel-$name"), "[TEST] Relay parcel: $name",
            "Current persisted status: ${actual.status}; deposited ${actual.depositedAt}. Original scenario age: $age. No storage fees are assessed; reseeding never resets custody or credentials.",
            "relay", mapOf("parcelId" to id, "orderId" to orderId, "relayPointId" to site, "lockerId" to slotId),
            listOf(if (name == "fresh") "Use the test code or QR in relay pickup and explicitly confirm identity; no ID image is required. Expired or changed credentials are not reset."
                else "Review stored deadlines and custody history. Leave storage fees unassessed without an approved tariff; disposition remains operator-controlled."), credentials)
    }

    private fun returnId(context: LocalOperationalFixtureContext, stage: String) = context.id("return-request-$stage")
    private fun parcelId(context: LocalOperationalFixtureContext, name: String) = context.id("parcel-$name")
    private fun accepted(result: PersistedReturnResult): ReturnRequestSnapshot = when (result) {
        is PersistedReturnResult.Accepted -> result.returnRequest
        is PersistedReturnResult.Rejected -> error("Synthetic return setup rejected: ${result.rejection.code}. Existing data was not reset.")
    }
    private fun relayAccepted(result: RelayParcelServiceResult): RelayParcel = when (result) {
        is RelayParcelServiceResult.Accepted -> result.value.parcel
        is RelayParcelServiceResult.Rejected -> error("Synthetic parcel setup rejected: ${result.rejection.code}. Existing data was not reset.")
    }

    private companion object {
        const val FreshPickupCode = "410241" // Static synthetic fixture only, exposed solely by the guarded local manifest.
    }
}
