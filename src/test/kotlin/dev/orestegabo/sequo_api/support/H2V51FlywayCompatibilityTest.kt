package dev.orestegabo.sequo_api.support

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.CoreMigrationType
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.internal.resolver.ChecksumCalculator
import org.flywaydb.core.internal.resource.classpath.ClassPathResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = [
    "spring.datasource.url=jdbc:h2:mem:h2_v51_compatibility;DB_CLOSE_DELAY=-1",
    "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
])
class H2V51FlywayCompatibilityTest @Autowired constructor(private val flyway: Flyway, private val jdbc: JdbcTemplate) {
    @Test
    fun h2MigrationPreservesOriginalPostgresMetadataAndChecksum() {
        for (version in setOf("51", "64", "65", "66", "67")) {
            val applied = flyway.info().all().single { it.version?.version == version }
            val filename = applied.script.substringAfterLast('/')
            val source = ClassPathResource(null, "db/migration/$filename", javaClass.classLoader, StandardCharsets.UTF_8)
            assertEquals(CoreMigrationType.SQL, applied.type)
            assertEquals(MigrationState.SUCCESS, applied.state)
            assertEquals(ChecksumCalculator.calculate(source), applied.checksum)
        }
        flyway.validate()
    }

    @Test
    fun h2AppendOnlyTablesRejectUpdatesAndDeletes() {
        for ((table, column) in listOf("activity_journal" to "action", "owner_directory_operations" to "action", "partner_onboarding_operations" to "action")) {
            assertFailsWith<DataAccessException> { jdbc.execute("update $table set $column = $column") }
            assertFailsWith<DataAccessException> { jdbc.execute("delete from $table") }
        }
    }
}
