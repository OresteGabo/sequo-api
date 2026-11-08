package dev.orestegabo.sequo_api.domain.returns

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.SequoApiApplication
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

class CustomerReturnRestartTest {
    @TempDir lateinit var databaseDirectory: Path

    @Test
    fun ownedReturnAndServerPinSurviveActualApplicationRestart() {
        val json = ObjectMapper()
        lateinit var fixture: ReturnJourneyOrder
        val returnId = "restart-return-${UUID.randomUUID()}"
        lateinit var returnedPin: String
        lateinit var requestBody: String
        startApplication().use { context ->
            ReturnJourneyFixtures.relay(context)
            fixture = ReturnJourneyFixtures.order(context, Instant.now().minusSeconds(3600))
            requestBody = ReturnJourneyFixtures.body(returnId, fixture.orderId)
            val response = ReturnJourneyFixtures.request(port(context), "POST", "/api/returns", fixture.owner.token, requestBody)
            assertEquals(200, response.statusCode(), response.body())
            val created = json.readTree(response.body())
            assertEquals(returnId, created["id"].asText())
            assertEquals("AwaitingRelayDropoff", created["status"].asText())
            returnedPin = created["returnPin"].asText()
            assertTrue(returnedPin.matches(Regex("[0-9]{6}")))
        }
        startApplication().use { context ->
            val freshToken = ReturnJourneyFixtures.token(context, fixture.owner.id)
            val response = ReturnJourneyFixtures.request(port(context), "GET", "/api/returns/$returnId", freshToken)
            assertEquals(200, response.statusCode(), response.body())
            val restored = json.readTree(response.body())
            assertEquals(returnId, restored["id"].asText())
            assertEquals(fixture.orderId, restored["orderId"].asText())
            assertEquals(returnedPin, restored["returnPin"].asText())
            val retry = ReturnJourneyFixtures.request(port(context), "POST", "/api/returns", freshToken, requestBody)
            assertEquals(200, retry.statusCode(), retry.body())
            assertEquals(returnId, json.readTree(retry.body())["id"].asText())
            assertEquals(returnedPin, json.readTree(retry.body())["returnPin"].asText())
            assertEquals(1, context.getBean(ReturnRequestRecordRepository::class.java).findByOrderIdOrderByCreatedAtDesc(fixture.orderId).size)
            // A supported legacy hashed credential cannot be reconstructed or invented on read.
            val stored = context.getBean(ReturnRequestRecordRepository::class.java).findById(returnId).orElseThrow()
            val originalHash = stored.returnPinHash
            stored.customerCredentialVersion = null
            context.getBean(ReturnRequestRecordRepository::class.java).saveAndFlush(stored)
            val legacy = ReturnJourneyFixtures.request(port(context), "GET", "/api/returns/$returnId", freshToken)
            assertEquals(200, legacy.statusCode(), legacy.body())
            val legacyView = json.readTree(legacy.body())
            assertTrue(legacyView["returnPin"].isNull)
            assertEquals("SEQUO_INSTRUCTIONS_PENDING", legacyView["dropoffMethod"].asText())
            assertEquals(originalHash, context.getBean(ReturnRequestRecordRepository::class.java).findById(returnId).orElseThrow().returnPinHash)
        }
    }

    private fun port(context: ConfigurableApplicationContext) = requireNotNull(context.environment.getProperty("local.server.port")).toInt()
    private fun startApplication(): ConfigurableApplicationContext = SpringApplicationBuilder(SequoApiApplication::class.java).run(
        "--server.port=0",
        "--spring.datasource.url=jdbc:h2:file:${databaseDirectory.resolve("returns")}",
        "--spring.jpa.hibernate.ddl-auto=update",
        "--spring.flyway.enabled=false",
    )
}
