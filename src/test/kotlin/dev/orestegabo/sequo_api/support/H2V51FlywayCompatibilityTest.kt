package dev.orestegabo.sequo_api.support

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
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
class H2V51FlywayCompatibilityTest @Autowired constructor(private val flyway: Flyway) {
    @Test
    fun h2MigrationPreservesOriginalPostgresMetadataAndChecksum() {
        val filename = "V51__durable_payment_reconciliation.sql"
        val source = ClassPathResource(null, "db/migration/$filename", javaClass.classLoader, StandardCharsets.UTF_8)
        val applied = flyway.info().all().single { it.version?.version == "51" }
        assertEquals(filename, applied.script)
        assertEquals(CoreMigrationType.SQL, applied.type)
        assertEquals(MigrationState.SUCCESS, applied.state)
        assertEquals(ChecksumCalculator.calculate(source), applied.checksum)
        flyway.validate()
    }
}
