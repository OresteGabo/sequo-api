package dev.orestegabo.sequo_api.domain.returns

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.SequoApiApplication
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

class ReturnCustodyRestartTest {
    @TempDir lateinit var databaseDirectory: Path

    @Test
    fun pendingRefundInstructionAndCustodyHistorySurviveActualRestartAndRetry() {
        val id = "restart-custody-${UUID.randomUUID()}"
        lateinit var fixture: ReturnJourneyOrder
        lateinit var admin: ReturnJourneyActor
        lateinit var instructionId: String
        var historyCount = 0
        startApplication().use { context ->
            val relay = ReturnJourneyFixtures.relayWithOperator(context)
            fixture = ReturnJourneyFixtures.order(context, Instant.now().minusSeconds(3600))
            val support = ReturnCustodyFixtures.actor(context, RoleCode.SUPPORT_AGENT)
            admin = ReturnCustodyFixtures.actor(context, RoleCode.ADMIN)
            val created = post(context, "/api/returns", fixture.owner.token, ReturnJourneyFixtures.body(id, fixture.orderId))
            post(context, "/api/returns/$id/relay-dropoff", relay.operator.token,
                ReturnCustodyFixtures.dropoffBody(relay.id, created["returnPin"].asText(), "dropoff-$id"))
            post(context, "/api/returns/$id/collection", support.token, ReturnCustodyFixtures.collectionBody("collection-$id"))
            post(context, "/api/returns/$id/physical-receipt", support.token, ReturnCustodyFixtures.receiptBody("receipt-$id"))
            post(context, "/api/returns/$id/inspection", support.token, ReturnCustodyFixtures.inspectionBody("inspection-$id"))
            val instructed = post(context, "/api/returns/$id/refund", admin.token, ReturnCustodyFixtures.refundBody(3000, "refund-$id"))
            instructionId = instructed["refundInstruction"]["id"].asText()
            historyCount = instructed["history"].size()
            assertEquals("NOT_SUBMITTED", instructed["refundInstruction"]["paymentStatus"].asText())
        }
        startApplication().use { context ->
            val adminToken = ReturnJourneyFixtures.token(context, admin.id)
            val restored = read(context, "/api/returns/$id", adminToken)
            assertEquals(instructionId, restored["refundInstruction"]["id"].asText())
            assertEquals(historyCount, restored["history"].size())
            assertEquals("RefundPending", restored["status"].asText())
            assertEquals("REQUESTED", restored["refundInstruction"]["status"].asText())
            assertEquals("NOT_SUBMITTED", restored["refundInstruction"]["paymentStatus"].asText())
            assertEquals("UNCONFIGURED", restored["refundDeadlineState"].asText())
            for (key in listOf("refund-$id", "another-refund-key-$id")) {
                val retry = post(context, "/api/returns/$id/refund", adminToken, ReturnCustodyFixtures.refundBody(3000, key))
                assertEquals(instructionId, retry["refundInstruction"]["id"].asText())
                assertEquals(historyCount, retry["history"].size())
            }
            val customer = read(context, "/api/returns/$id", ReturnJourneyFixtures.token(context, fixture.owner.id))
            assertEquals("NOT_SUBMITTED", customer["refundInstruction"]["paymentStatus"].asText())
            assertTrue(customer.path("returnPin").isNull || customer.path("returnPin").isMissingNode)
            assertEquals(1, context.getBean(ReturnRequestRecordRepository::class.java).findByOrderIdOrderByCreatedAtDesc(fixture.orderId).size)
        }
    }

    private fun post(context: ConfigurableApplicationContext, path: String, token: String, body: String) = parse(
        ReturnJourneyFixtures.request(port(context), "POST", path, token, body))
    private fun read(context: ConfigurableApplicationContext, path: String, token: String) = parse(
        ReturnJourneyFixtures.request(port(context), "GET", path, token))
    private fun parse(response: java.net.http.HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        return ObjectMapper().readTree(response.body())
    }
    private fun port(context: ConfigurableApplicationContext) = requireNotNull(context.environment.getProperty("local.server.port")).toInt()
    private fun startApplication(): ConfigurableApplicationContext = SpringApplicationBuilder(SequoApiApplication::class.java).run(
        "--server.port=0", "--spring.datasource.url=jdbc:h2:file:${databaseDirectory.resolve("custody")}",
        "--spring.jpa.hibernate.ddl-auto=update", "--spring.flyway.enabled=false",
    )
}
