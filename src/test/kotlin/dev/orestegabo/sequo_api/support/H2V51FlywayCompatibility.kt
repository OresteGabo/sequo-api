package dev.orestegabo.sequo_api.support

import java.io.StringReader
import org.flywaydb.core.api.CoreMigrationType
import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.migration.JavaMigration
import org.flywaydb.core.api.resolver.MigrationResolver
import org.flywaydb.core.api.resource.LoadableResource
import org.flywaydb.core.internal.resolver.ChecksumCalculator
import org.flywaydb.core.internal.resolver.ResolvedMigrationImpl
import org.flywaydb.core.internal.resolver.sql.SqlMigrationExecutor
import org.flywaydb.core.internal.resource.ResourceNameParser
import org.flywaydb.core.internal.scanner.Scanner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.context.annotation.Bean

private const val V51 = "V51__durable_payment_reconciliation.sql"
private val h2CompatibilityScripts = setOf(V51, "V64__persistent_activity_journal.sql",
    "V65__owner_directory_operations.sql", "V66__partner_applications_and_onboarding.sql")

/** Test classpath only: adapt PostgreSQL syntax while retaining original migration checksums.
 * H2 has no TRUNCATE trigger; production append-only enforcement remains in PostgreSQL.
 */
@AutoConfiguration(before = [FlywayAutoConfiguration::class])
class H2V51FlywayCompatibility {
    @Bean
    fun h2V51FlywayCustomizer() = FlywayConfigurationCustomizer { configuration ->
        if (configuration.dataSource.connection.use { it.metaData.databaseProductName } != "H2") {
            return@FlywayConfigurationCustomizer
        }
        val original = configuration.resourceProvider
            ?: Scanner(JavaMigration::class.java, configuration, configuration.locations)
        configuration.resourceProvider(object : ResourceProvider {
            override fun getResource(name: String): LoadableResource? = original.getResource(name)
            override fun getResources(prefix: String, suffixes: Array<out String>): Collection<LoadableResource> =
                original.getResources(prefix, suffixes).filterNot { it.filename in h2CompatibilityScripts }
        })
        configuration.resolvers(*configuration.resolvers, MigrationResolver { context ->
            h2CompatibilityScripts.mapNotNull { filename ->
                val source = context.resourceProvider.getResource(filename) ?: return@mapNotNull null
                val name = ResourceNameParser(context.configuration).parse(source.filename)
                if (!name.isValid || name.prefix != context.configuration.sqlMigrationPrefix) {
                    return@mapNotNull null
                }
                val sql = source.read().use { it.readText() }
                val h2Sql = if (filename == V51) {
                    val additions = Regex(",\\s*add column\\s+", RegexOption.IGNORE_CASE)
                    check(additions.findAll(sql).count() == 2) { "V51 H2 compatibility expects the original three-column ALTER." }
                    sql.replace(additions, ";\nalter table payment_webhook_events add column ")
                } else {
                    val function = Regex("create function reject_activity_journal_mutation\\(\\).*?\\$\\$;", RegexOption.DOT_MATCHES_ALL)
                    val trigger = Regex("create trigger (\\w+) before update or delete or truncate on (\\w+)\\s+for each statement execute function reject_activity_journal_mutation\\(\\);", RegexOption.IGNORE_CASE)
                    check(trigger.findAll(sql).count() == 1) { "Expected one append-only PostgreSQL trigger in $filename." }
                    sql.replace(function, "").replace(trigger) { match ->
                        "create trigger ${match.groupValues[1]} before update, delete on ${match.groupValues[2]} for each statement call \"dev.orestegabo.sequo_api.support.H2AppendOnlyTrigger\";"
                    }
                }
                val executable = object : LoadableResource() {
                    override fun read() = StringReader(h2Sql)
                    override fun getAbsolutePath(): String = source.absolutePath
                    override fun getAbsolutePathOnDisk(): String = source.absolutePathOnDisk
                    override fun getFilename(): String = source.filename
                    override fun getRelativePath(): String = source.relativePath
                }
                val script = context.sqlScriptFactory.createSqlScript(executable,
                    context.configuration.isMixed, context.resourceProvider)
                ResolvedMigrationImpl(name.version, name.description, source.relativePath,
                    ChecksumCalculator.calculate(source), null, CoreMigrationType.SQL, source.absolutePathOnDisk,
                    SqlMigrationExecutor(context.sqlScriptExecutorFactory, script, false, context.configuration.isBatch))
            }
        })
    }
}
