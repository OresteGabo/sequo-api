package dev.orestegabo.sequo_api.domain.localfixture

import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus
import dev.orestegabo.sequo_api.domain.relay.*
import dev.orestegabo.sequo_api.domain.returns.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

@ActiveProfiles("dev")
@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:sequo_admin_dev_return_fixtures;DB_CLOSE_DELAY=-1",
    "server.address=127.0.0.1", "server.forward-headers-strategy=NONE", "spring.config.import=",
    "sequo.admin.local-access.enabled=true", "sequo.admin.local-access.environment=LOCAL_FIXTURE",
    "sequo.admin.local-access.user-id=00000000-0000-0000-0000-000000000062", "sequo.admin.local-fixtures.enabled=true",
    "sequo.security.cors.allowed-origins=http://127.0.0.1:5173",
    "sequo.auth.jwt.secret=local_return_fixture_signing_key_for_isolated_tests_only",
    "sequo.auth.jwt.issuer=sequo-api-local-admin-fixture", "sequo.auth.jwt.audience=sequo-admin-local-fixture",
    "sequo.notifications.outbox-worker.enabled=false", "sequo.notifications.provider-delivery-worker.enabled=false",
    "sequo.wallets.yas-togo.api-base-url=", "sequo.wallets.yas-togo.api-key=", "sequo.wallets.yas-togo.merchant-account-id=", "sequo.wallets.yas-togo.webhook-secret=",
    "sequo.wallets.moov-africa.api-base-url=", "sequo.wallets.moov-africa.api-key=", "sequo.wallets.moov-africa.merchant-account-id=", "sequo.wallets.moov-africa.webhook-secret=",
])
class LocalReturnRelayFixtureSeederTest @Autowired constructor(
    private val foundation: LocalOperationalFixtureFoundation,
    private val journeys: LocalOperationalJourneySeeder,
    private val seeder: LocalReturnRelayFixtureSeeder,
    private val returns: ReturnPersistenceService,
    private val returnRecords: ReturnRequestRecordRepository,
    private val parcels: RelayParcelApplicationService,
    private val parcelRecords: RelayParcelRecordRepository,
    private val custody: RelayCustodyEventRecordRepository,
    private val release: RelayPickupOperations,
    private val lockers: RelayLockerRepository,
    private val hubs: HubMobileService,
    private val orders: CustomerOrderRecordRepository,
    private val registry: LocalOperationalFixtureRunRepository,
    private val transactions: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
) {
    @Test fun normalReturnAndParcelScenesCommitCustodyWithoutFeesOrTransfersAndPreserveOperatorEdits() {
        val at = Instant.now()
        val admin = "00000000-0000-0000-0000-000000000062"
        jdbc.update("insert into users(id,email,display_name,is_active,provider,status,created_at,updated_at) values (?,?,?,true,'EMAIL','ACTIVE',?,?)",
            admin, "return-fixture-admin@fixture.invalid", "[TEST] Local return administrator", Timestamp.from(at), Timestamp.from(at))
        jdbc.update("insert into user_roles(user_id,role_code) values (?,?)", admin, "SUPER_ADMIN")
        val context = LocalOperationalFixtureContext(admin, at)
        foundation.seed(context)
        journeys.seedReturnOrders(context)
        journeys.seedRelayOrders(context)
        registry.saveAndFlush(LocalOperationalFixtureRun("local-ops-v1"))

        val scenes = requireNotNull(TransactionTemplate(transactions).execute {
            registry.lockRun("local-ops-v1")
            val seeded = seeder.seed(context)
            // The suspended registry transaction must not hide any normal committed domain stage.
            val visible = CompletableFuture.supplyAsync { returns.get(context.id("return-request-refund")) }.get(5, TimeUnit.SECONDS)
            assertEquals(ReturnStatus.RefundPending, visible?.status)
            seeded
        })
        assertEquals(10, scenes.size)
        assertEquals(7L, returnRecords.count())
        fun returned(stage: String) = requireNotNull(returns.get(context.id("return-request-$stage")))
        assertEquals(ReturnStatus.AwaitingRelayDropoff, returned("request").status)
        assertEquals(ReturnStatus.AwaitingRelayDropoff, returned("awaiting-dropoff").status)
        assertEquals(ReturnStatus.DroppedAtRelay, returned("dropoff").status)
        assertEquals(ReturnStatus.CollectedBySequo, returned("collected").status)
        assertEquals(ReturnStatus.ReceivedBySequo, returned("received").status)
        assertNull(returned("received").inspectionDecision)
        assertEquals(ReturnInspectionDecision.ACCEPTED, returned("accepted").inspectionDecision)
        assertNull(returned("accepted").refundInstruction)
        val instruction = returned("refund")
        assertEquals(ReturnStatus.RefundPending, instruction.status)
        assertEquals("REQUESTED", instruction.refundInstruction?.status)
        assertEquals("NOT_SUBMITTED", instruction.refundInstruction?.paymentStatus)
        assertEquals(instruction.requestedRefundCfa, instruction.refundInstruction?.amountCfa)
        assertEquals(CustomerOrderStatus.RETURN_REQUESTED, orders.findById(instruction.orderId).orElseThrow().orderStatus)
        assertEquals(listOf("RELAY_DROPOFF", "SEQUO_COLLECTION", "PHYSICAL_RECEIPT", "CONDITION_INSPECTION", "REFUND_INSTRUCTION"), instruction.history.map { it.action })
        assertEquals(context.relayOperatorIds[0], instruction.history.first().actorUserId)
        assertTrue(instruction.history.drop(1).all { it.actorUserId == admin })
        assertTrue(scenes.first { it.id == context.id("scene-return-awaiting-dropoff") }.credentials.getValue("returnPin").matches(Regex("[0-9]{6}")))

        val freshId = context.id("parcel-fresh")
        val fresh = requireNotNull(parcels.getParcel(freshId))
        val eight = requireNotNull(parcels.getParcel(context.id("parcel-after-eight-days")))
        val thirty = requireNotNull(parcels.getParcel(context.id("parcel-after-thirty-days")))
        assertEquals(RelayParcelStatus.Deposited, fresh.status)
        assertEquals(RelayParcelStatus.Delayed, eight.status)
        assertEquals(RelayParcelStatus.ReturnToSellerReview, thirty.status)
        listOf("fresh" to fresh, "after-eight-days" to eight, "after-thirty-days" to thirty).forEach { (name, parcel) ->
            val source = orders.findById(requireNotNull(context.relayOrderIds[name])).orElseThrow()
            assertEquals(source.id, parcel.orderId)
            assertEquals(CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT, source.orderStatus)
            assertTrue(source.createdAt.isBefore(requireNotNull(parcel.depositedAt)))
        }
        assertEquals(listOf(RelayCustodyEventType.Deposit, RelayCustodyEventType.Delay), eight.custodyEvents.map { it.type })
        assertEquals(listOf(RelayCustodyEventType.Deposit, RelayCustodyEventType.Review), thirty.custodyEvents.map { it.type })
        assertFalse(requireNotNull(fresh.deadlines(at)).storageFeeEligible)
        assertTrue(requireNotNull(eight.deadlines(at)).storageFeeEligible)
        assertFalse(requireNotNull(eight.deadlines(at)).reviewDue)
        assertTrue(requireNotNull(thirty.deadlines(at)).reviewDue)
        assertTrue(parcels.listStorageFeeAssessments(context.relayPointIds[1]).isEmpty())

        val code = scenes.first { it.id == context.id("scene-parcel-fresh") }.credentials.getValue("pickupCode")
        assertIs<RelayParcelServiceResult.Rejected>(release.release(freshId, context.relayPointIds[1], context.relayOperatorIds[1],
            code, null, false, context.id("test-missing-identity"), context.id("test-missing-identity")))
        val releaseKey = context.id("test-release")
        assertIs<RelayParcelServiceResult.Accepted>(release.release(freshId, context.relayPointIds[1], context.relayOperatorIds[1], code, null, true, releaseKey, releaseKey))
        val releasedEvents = custody.count()
        assertIs<RelayParcelServiceResult.Accepted>(release.release(freshId, context.relayPointIds[1], context.relayOperatorIds[1], code, null, true, releaseKey, releaseKey))
        assertEquals(releasedEvents, custody.count())
        assertEquals(HubLockerStatus.AVAILABLE, lockers.findById(context.id("slot-2-1")).orElseThrow().status)
        assertIs<PersistedReturnResult.Accepted>(returns.recordInspection(PersistedReturnInspectionCommand(
            returned("received").id, admin, ReturnInspectionDecision.REJECTED, "[TEST] Operator rejected this synthetic package.", RefundResponsibility.Merchant, context.id("test-reject"))))
        hubs.updateLockerAvailability(LockerAvailabilityCommand(context.id("slot-2-1"), context.relayPointIds[1], HubLockerStatus.MAINTENANCE,
            HubLockerAvailabilityReason.OTHER, updatedByUserId = context.relayOperatorIds[1]))
        val returnsBefore = returnRecords.count(); val parcelsBefore = parcelRecords.count(); val eventsBefore = custody.count()
        val historyBefore = returned("received").history
        val replayed = seeder.seed(context)
        assertEquals(10, replayed.size)
        assertEquals(returnsBefore, returnRecords.count()); assertEquals(parcelsBefore, parcelRecords.count()); assertEquals(eventsBefore, custody.count())
        assertEquals(ReturnStatus.Rejected, returned("received").status)
        assertEquals(historyBefore, returned("received").history)
        assertEquals(RelayParcelStatus.PickedUp, parcels.getParcel(freshId)?.status)
        assertEquals(HubLockerStatus.MAINTENANCE, lockers.findById(context.id("slot-2-1")).orElseThrow().status)
        assertTrue(replayed.first { it.id == context.id("scene-parcel-fresh") }.credentials.isEmpty())
        assertTrue(parcels.listStorageFeeAssessments(context.relayPointIds[1]).isEmpty())
        assertEquals(instruction.refundInstruction?.id, returned("refund").refundInstruction?.id)
    }
}
