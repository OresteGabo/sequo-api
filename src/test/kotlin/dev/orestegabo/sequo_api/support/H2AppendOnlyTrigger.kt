package dev.orestegabo.sequo_api.support

import org.h2.api.Trigger
import java.sql.Connection
import java.sql.SQLException

/** H2 equivalent of the production PostgreSQL statement-level UPDATE/DELETE guard. */
class H2AppendOnlyTrigger : Trigger {
    override fun fire(connection: Connection?, oldRow: Array<out Any?>?, newRow: Array<out Any?>?) {
        throw SQLException("Audit history records are append-only", "45000")
    }
}
