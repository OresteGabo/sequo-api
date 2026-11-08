package dev.orestegabo.sequo_api.domain.localfixture

import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** Static synthetic scenes only. This class is never an arbitrary payment or mission mutation API. */
@Component
class LocalOperationalJourneySeeder(
    private val policy: LocalOperationalFixturePolicy,
    private val pending: PendingPaymentCheckoutService,
    private val checkouts: PendingPaymentCheckoutRepository,
    private val orders: CustomerOrderRecordRepository,
    private val merchants: CommerceMerchantRepository,
    private val products: CommerceProductRepository,
    private val couriers: SettlementCourierRecordRepository,
    private val merchant: MerchantFulfillmentService,
    private val delivery: DeliveryMissionService,
    private val missions: DeliveryMissionRepository,
    private val assignments: GuardedDeliveryAssignmentService,
    private val pins: DeliveryPinService,
    private val lifecycle: OrderDeliveryLifecycleService,
    private val consolidation: ConsolidationPersistenceService,
    private val entityManager: EntityManager,
    transactions: PlatformTransactionManager,
    paymentProcessor: PaymentProcessor,
    pricing: DeliveryPricingService,
) {
    private val processor = RegularOrderProcessor(paymentProcessor, pricing)
    private val transaction = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    // Suspend the caller's registry transaction while it keeps the namespace row locked:
    // reconciliation needs committed receipt/checkout and custody listeners need committed stages.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun seed(context: LocalOperationalFixtureContext): List<LocalFixtureScenario> {
        policy.requireEnabled()
        require(context.prefix == "local-ops-v1") { "Unsupported local fixture namespace." }
        val result = mutableListOf<LocalFixtureScenario>()
        result += scene(context, "payment-pending", "Payment pending", "payments", "Pending local fixture evidence never enters fulfillment.",
            payment = PaymentWebhookProviderStatus.PENDING)
        result += scene(context, "payment-failed", "Payment rejected", "payments", "Failed local fixture payment has no fulfillment order.",
            payment = PaymentWebhookProviderStatus.FAILED)
        result += scene(context, "merchant-new", "New paid merchant order", "orders", "Synthetic paid order waiting for merchant acceptance.")
        result += scene(context, "merchant-preparing", "Merchant preparing", "orders", "Accepted package with preparation in progress.", prepare = 2)
        result += scene(context, "merchant-ready", "Ready for dispatch", "orders", "Prepared package; dispatch can be exercised from the admin controls.", prepare = 3)
        result += scene(context, "dispatch-unassigned", "Unassigned delivery", "deliveries", "Paid prepared package with a real unassigned mission.", prepare = 3, mission = true)
        result += scene(context, "finance-independent", "Completed independent delivery", "payments", "Normal proof and settlement accrual; no provider transfer was submitted.",
            prepare = 3, mission = true, complete = true, courierIndex = 0)
        result += scene(context, "finance-salaried", "Completed salaried delivery", "payments", "Salaried workforce snapshot with no independent courier payable.",
            prepare = 3, mission = true, complete = true, courierIndex = 1)
        result += seedReturnOrders(context)
        result += group(context, "group-ready", finalDispatch = false)
        result += group(context, "group-final", finalDispatch = true)
        // Last: leave this rider busy while the independent/salaried available fixtures remain free.
        result += scene(context, "dispatch-busy", "Assigned rider mission", "deliveries", "An offered mission occupies the busy rider's capacity.",
            prepare = 3, mission = true, courierIndex = 2, assignOnly = true)
        return result
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun seedReturnOrders(context: LocalOperationalFixtureContext): List<LocalFixtureScenario> {
        policy.requireEnabled()
        require(context.prefix == "local-ops-v1") { "Unsupported local fixture namespace." }
        return context.returnOrderIds.keys.map { stage ->
            scene(context, "return-$stage", "Return journey: $stage", "returns",
                "Delivered synthetic order reserved for the $stage return scene.", prepare = 3, mission = true, complete = true)
        }
    }

    private fun scene(context: LocalOperationalFixtureContext, suffix: String, title: String, category: String,
        summary: String, payment: PaymentWebhookProviderStatus = PaymentWebhookProviderStatus.VALIDATED,
        prepare: Int = 0, mission: Boolean = false, complete: Boolean = false, courierIndex: Int = 0,
        assignOnly: Boolean = false): LocalFixtureScenario {
        val checkoutId = context.id(suffix)
        // A partial/existing scene is retained too: seeding never advances something the user may edit.
        if (!existingScene(context, checkoutId)) {
            val request = request(context, suffix)
            createCheckout(request, payment, context.createdAt)
            if (payment == PaymentWebhookProviderStatus.VALIDATED) {
                val parts = prepare(request.checkoutId.toOrderId(), prepare)
                if (mission) {
                    val created = createMission(context, suffix, request, parts.single())
                    if (complete || assignOnly) {
                        assignAndCollect(context, created, context.courierIds[courierIndex], collect = complete)
                        if (complete) deliver(context, created.id, context.courierIds[courierIndex])
                    }
                }
            }
        }
        return descriptor(context, suffix, title, category, summary)
    }

    private fun request(context: LocalOperationalFixtureContext, suffix: String, grouped: Boolean = false): OrderProcessingRequest {
        val primary = requireNotNull(products.findById(context.generalProductId).orElse(null))
        val primaryMerchant = requireNotNull(merchants.findById(context.merchantIds.first()).orElse(null))
        require(primary.merchantId == primaryMerchant.id && primaryMerchant.status == "ACTIVE" &&
            primary.status == CatalogProductStatus.ACTIVE && primary.kind == CatalogProductKind.GenericSealedItem &&
            primary.basePriceCfa != null) { "An active standard local product and merchant are required; existing edits are retained." }
        val lines = mutableListOf(OrderLineRequest(primary.id, primaryMerchant.id, primaryMerchant.name, primary.name,
            OrderProductCategory.GenericSealedItem, 2, requireNotNull(primary.basePriceCfa),
            photoEvidence = ProductPhotoEvidence.GenericCatalogImage))
        if (grouped) {
            val other = products.findById(context.secondMerchantProductId).orElseThrow()
            val otherMerchant = merchants.findById(context.merchantIds[1]).orElseThrow()
            require(other.merchantId == otherMerchant.id && otherMerchant.status == "ACTIVE" &&
                other.status == CatalogProductStatus.ACTIVE && other.kind == CatalogProductKind.GenericSealedItem) {
                "An active second local product and merchant are required; existing edits are retained."
            }
            lines += OrderLineRequest(other.id, otherMerchant.id, otherMerchant.name, other.name, OrderProductCategory.GenericSealedItem,
                3, requireNotNull(other.basePriceCfa), photoEvidence = ProductPhotoEvidence.GenericCatalogImage)
        }
        val id = context.id(suffix)
        return OrderProcessingRequest(id, context.customerId, OrderServiceLevel.Regular, OrderRoute.FastDelivery, lines, 4.0,
            paymentProvider = SequoPaymentProviders.YasTogo, paymentReference = id,
            homeDestination = HomeDeliveryDestination(context.id("address"), "LOCAL FIXTURE — synthetic home", "Local test customer",
                "+22800000000", "Lomé (synthetic fixture)", "Local fixture zone", "No real delivery; test address only", 6.1725, 1.2314))
    }

    private fun createCheckout(request: OrderProcessingRequest, status: PaymentWebhookProviderStatus, at: Instant) {
        policy.requireEnabled()
        val waiting = processor.process(request)
        check(waiting is OrderProcessingResult.AwaitingPaymentValidation) { "Local fixture requires an unconnected pending payment method." }
        pending.recordAwaitingWebhook(request, waiting, at)
        val eventId = "LOCAL_FIXTURE:${request.checkoutId}"
        val providerReference = "LOCAL_FIXTURE:${request.checkoutId}"
        val raw = "LOCAL_FIXTURE|${request.checkoutId}|${waiting.pricing.totalCfa}|${status.name}|XOF"
        // Fixed server-owned evidence, committed separately before normal reconciliation. No signing
        // keys, remote call, public verifier override or simulator endpoint is introduced.
        transaction.executeWithoutResult {
            entityManager.persist(PaymentWebhookEventRecord("${request.paymentProvider.value}:$eventId", request.paymentProvider.value,
                eventId, request.checkoutId, paymentReference = request.paymentReference, providerReference = providerReference,
                amountCfa = waiting.pricing.totalCfa, status = status, occurredAt = at,
                payloadHash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }, receivedAt = at))
            entityManager.flush()
        }
        val result = pending.reconcile(PaymentWebhookAcceptedEvent(request.paymentProvider, eventId, request.checkoutId,
            request.paymentReference, providerReference, waiting.pricing.totalCfa, status, at, at))
        check(if (status == PaymentWebhookProviderStatus.VALIDATED) result is PaymentReconciliationResult.Fulfilled else result is PaymentReconciliationResult.Ignored) {
            "Local fixture payment evidence did not reconcile normally."
        }
    }

    private fun prepare(orderId: String, stage: Int): List<MerchantSubOrderSnapshot> {
        val parts = merchant.listForOrder(orderId)
        for (part in parts) {
            if (stage >= 1) requireSuccess(merchant.accept(part.id, part.merchantId))
            if (stage >= 2) requireSuccess(merchant.startPreparation(part.id, part.merchantId))
            if (stage >= 3) requireSuccess(merchant.markPacked(part.id, part.merchantId, 1))
        }
        return merchant.listForOrder(orderId)
    }

    private fun createMission(context: LocalOperationalFixtureContext, suffix: String, request: OrderProcessingRequest,
        part: MerchantSubOrderSnapshot, grouped: Boolean = false): DeliveryMissionSnapshot {
        require(part.status == MerchantSubOrderStatus.PACKED_READY)
        val order = orders.findById(request.checkoutId.toOrderId()).orElseThrow()
        return delivery.create(CreateDeliveryMissionCommand(deliveryCode = context.id("mission-$suffix"), orderId = order.id,
            merchantSubOrderId = part.id, deliveryMode = if (grouped) DeliveryMissionRecordMode.PROGRAMMED else DeliveryMissionRecordMode.EXPRESS,
            destinationType = if (grouped) DeliveryMissionRecordDestination.SEQUO_CONSOLIDATION else DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
            customerDeliveryFeeCfa = order.deliveryFeeCfa, courierFeeCfa = order.deliveryFeeCfa), context.createdAt)
    }

    private fun assignAndCollect(context: LocalOperationalFixtureContext, mission: DeliveryMissionSnapshot, courierId: String, collect: Boolean) {
        val actor = requireNotNull(couriers.findById(courierId).orElseThrow().userId)
        requireSuccess(assignments.assign(mission.id, courierId, context.adminId, "LOCAL_FIXTURE static journey assignment", "${mission.deliveryCode}-assign"))
        requireSuccess(assignments.offer(mission.id, context.adminId, "LOCAL_FIXTURE static journey offer", "${mission.deliveryCode}-offer"))
        if (collect) {
            requireSuccess(delivery.acceptForCourier(mission.id, actor))
            requireSuccess(delivery.transitionIdempotent(mission.id, actor, DeliveryMissionEvent.CourierPicksUpFromSeller,
                "${mission.deliveryCode}-pickup", proof = "LOCAL_FIXTURE synthetic merchant handoff"))
            mission.merchantSubOrderId?.let { id ->
                val part = requireNotNull(merchant.get(id))
                requireSuccess(merchant.confirmCourierHandoff(part.id, part.merchantId))
            }
        }
    }

    private fun deliver(context: LocalOperationalFixtureContext, missionId: String, courierId: String) {
        val actor = requireNotNull(couriers.findById(courierId).orElseThrow().userId)
        requireSuccess(delivery.validateDirectDeliveryAttempt(missionId, actor))
        val rawPin = "867420" // Public synthetic credential, generated/verified only in this local scene.
        pins.create(CreateDeliveryPinCommand(missionId, rawPin, Instant.now().plus(Duration.ofHours(2))))
        check(pins.verify(missionId, rawPin) is DeliveryPinResult.Accepted)
        val done = requireSuccess(delivery.transitionIdempotent(missionId, actor, DeliveryMissionEvent.CourierDeliversToCustomer,
            "${context.id("deliver")}:$missionId", proof = "LOCAL_FIXTURE synthetic customer handoff", deliveryPinValidated = true))
        check(lifecycle.markDeliveredFromMission(done, actor)?.orderStatus == CustomerOrderStatus.DELIVERED)
    }

    private fun group(context: LocalOperationalFixtureContext, suffix: String, finalDispatch: Boolean): LocalFixtureScenario {
        val checkoutId = context.id(suffix)
        val manifestId = context.id("manifest-$suffix")
        if (!existingScene(context, checkoutId)) {
            val request = request(context, suffix, grouped = true)
            createCheckout(request, PaymentWebhookProviderStatus.VALIDATED, context.createdAt)
            val parts = prepare(checkoutId.toOrderId(), 3)
            consolidation.createForOrder(manifestId, checkoutId.toOrderId(), context.adminId, null)
            parts.forEachIndexed { index, part ->
                val mission = createMission(context, "$suffix-$index", request, part, grouped = true)
                if (finalDispatch) {
                    assignAndCollect(context, mission, context.courierIds[index], collect = true)
                    consolidation.markSellerPackageCollected(manifestId, part.id, actorUserId = context.adminId,
                        metadata = "LOCAL_FIXTURE synthetic SEQUO receipt")
                }
            }
            if (finalDispatch) {
                val manifest = requireNotNull(consolidation.get(manifestId))
                val sealed = consolidation.transition(ConsolidationTransitionRequest(manifest,
                    ConsolidationEvent.SequoCreatesFinalPackage, Instant.now(), context.id("sealed-$suffix")), context.adminId)
                check(sealed.accepted) { sealed.reason }
                consolidation.dispatchFinalPackage(manifestId, actorUserId = context.adminId)
            }
        }
        return descriptor(context, suffix, if (finalDispatch) "Consolidated final dispatch" else "Two-merchant collection", "cooperatives",
            "Two synthetic merchant packages; ordinary SEQUO grouping and actor history.", mapOf("manifestId" to manifestId))
    }

    private fun descriptor(context: LocalOperationalFixtureContext, suffix: String, title: String, category: String,
        summary: String, extra: Map<String, String> = emptyMap()): LocalFixtureScenario {
        val checkout = context.id(suffix)
        val checkoutState = checkouts.findById(checkout).orElse(null)?.status?.name ?: "UNAVAILABLE"
        val order = orders.findByCheckoutId(checkout)
        val refs = mutableMapOf("checkoutId" to checkout)
        order?.let { refs["orderId"] = it.id }
        order?.let { missions.findByOrderIdOrderByCreatedAtAsc(it.id).firstOrNull()?.id?.let { id -> refs["missionId"] = id } }
        refs.putAll(extra)
        return LocalFixtureScenario(context.id(suffix), title, "$summary Checkout at seeding: $checkoutState; order: ${order?.orderStatus?.name ?: "NONE"}. LOCAL_FIXTURE; no real payment, transfer or delivery.", category,
            refs, listOf("Open the referenced record in the admin controls; existing edits are retained when seeding again."))
    }

    private fun existingScene(context: LocalOperationalFixtureContext, checkoutId: String): Boolean {
        val existing = checkouts.findById(checkoutId).orElse(null) ?: return false
        check(existing.customerId == context.customerId && existing.paymentProvider == SequoPaymentProviders.YasTogo.value &&
            existing.paymentReference == checkoutId) { "Local fixture checkout identity is occupied; existing data is preserved." }
        return true
    }

    private fun requireSuccess(result: MerchantFulfillmentServiceResult): MerchantSubOrderSnapshot =
        (result as? MerchantFulfillmentServiceResult.Success)?.subOrder ?: error("Local merchant transition rejected: $result")
    private fun requireSuccess(result: DeliveryMissionServiceResult): DeliveryMissionSnapshot =
        (result as? DeliveryMissionServiceResult.Success)?.mission ?: error("Local delivery transition rejected: $result")
}
