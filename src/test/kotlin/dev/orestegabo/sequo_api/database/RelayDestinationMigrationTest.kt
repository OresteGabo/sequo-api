package dev.orestegabo.sequo_api.database

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.Test
import kotlin.test.assertEquals

// Explicitly opt in against an isolated, disposable loopback fixture database only.
@EnabledIfEnvironmentVariable(named = "SEQUO_RELAY_MIGRATION_TEST_URL", matches = ".+")
@SpringBootTest(properties = ["spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect"])
class RelayDestinationMigrationTest @Autowired constructor(private val jdbc: JdbcTemplate) {
    companion object {
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = requireNotNull(System.getenv("SEQUO_RELAY_MIGRATION_TEST_URL"))
            require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sequo_task14_[a-z0-9_]+"))) { "Use a disposable local Task 14 fixture database." }
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "sequo_fixture" }
            registry.add("spring.datasource.password") { "" }
        }
    }
    @Test fun migratesRelayDestinationsAndValidatesJpaSchema() {
        assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version = '42' and success", Int::class.java))
        for (column in listOf("relay_point_id", "relay_destination_payload", "relay_reroute_actor_id", "relay_rerouted_at"))
            assertEquals(1, jdbc.queryForObject("select count(*) from information_schema.columns where table_name='delivery_missions' and column_name=?", Int::class.java, column))
    }
}
