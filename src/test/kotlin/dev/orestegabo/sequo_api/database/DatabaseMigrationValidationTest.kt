package dev.orestegabo.sequo_api.database

import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:migration_validation;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
    ]
)
class DatabaseMigrationValidationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun flywaySchemaMatchesCurrentJpaEntities() {
        // Spring context startup performs Flyway migration and Hibernate schema validation.
    }

    @Test
    fun deliveryFulfillmentTablesAreMigrated() {
        val expectedTables = listOf(
            "MERCHANT_SUB_ORDERS",
            "DELIVERY_MISSIONS",
            "DELIVERY_PINS",
            "RELAY_PARCELS",
            "RELAY_PICKUP_CODES",
            "RELAY_CUSTODY_EVENTS",
        )

        expectedTables.forEach { tableName ->
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    """
                    select count(*)
                    from information_schema.tables
                    where table_schema = 'PUBLIC'
                      and table_name = ?
                    """.trimIndent(),
                    Int::class.java,
                    tableName,
                ),
                "$tableName should be created by Flyway.",
            )
        }
    }

    @Test
    fun merchantSubOrderStatusConstraintRejectsInvalidState() {
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into merchant_sub_orders (
                    id,
                    sub_order_code,
                    order_id,
                    merchant_id,
                    status
                ) values (?, ?, ?, ?, ?)
                """.trimIndent(),
                "sub-order-invalid",
                "SC-INVALID",
                "order-1",
                "merchant-1",
                "PACKED_WITHOUT_ACCEPTANCE",
            )
        }
    }

    @Test
    fun deliveryPinsMustReferenceExistingMission() {
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into delivery_pins (
                    id,
                    delivery_mission_id,
                    pin_hash,
                    expires_at
                ) values (?, ?, ?, current_timestamp)
                """.trimIndent(),
                "pin-orphan",
                "missing-mission",
                "hashed-pin",
            )
        }
    }

    @Test
    fun relayPickupCodesMustReferenceExistingParcel() {
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                """
                insert into relay_pickup_codes (
                    id,
                    relay_parcel_id,
                    code_hash,
                    expires_at
                ) values (?, ?, ?, current_timestamp)
                """.trimIndent(),
                "relay-code-orphan",
                "missing-parcel",
                "hashed-code",
            )
        }
    }
}
