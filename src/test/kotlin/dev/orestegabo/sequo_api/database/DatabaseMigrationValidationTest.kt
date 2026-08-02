package dev.orestegabo.sequo_api.database

import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.Test

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:migration_validation;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
    ]
)
class DatabaseMigrationValidationTest {

    @Test
    fun flywaySchemaMatchesCurrentJpaEntities() {
        // Spring context startup performs Flyway migration and Hibernate schema validation.
    }
}
