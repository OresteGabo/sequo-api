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

/** Test classpath only: H2 needs separate ADD statements for this PostgreSQL migration. */
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
                original.getResources(prefix, suffixes).filterNot { it.filename == V51 }
        })
        configuration.resolvers(*configuration.resolvers, MigrationResolver { context ->
            val source = context.resourceProvider.getResource(V51) ?: return@MigrationResolver emptyList()
            val name = ResourceNameParser(context.configuration).parse(source.filename)
            if (!name.isValid || name.prefix != context.configuration.sqlMigrationPrefix) {
                return@MigrationResolver emptyList()
            }
            val sql = source.read().use { it.readText() }
            val additions = Regex(",\\s*add column\\s+", RegexOption.IGNORE_CASE)
            check(additions.findAll(sql).count() == 2) { "V51 H2 compatibility expects the original three-column ALTER." }
            val h2Sql = sql.replace(additions, ";\nalter table payment_webhook_events add column ")
            val executable = object : LoadableResource() {
                override fun read() = StringReader(h2Sql)
                override fun getAbsolutePath(): String = source.absolutePath
                override fun getAbsolutePathOnDisk(): String = source.absolutePathOnDisk
                override fun getFilename(): String = source.filename
                override fun getRelativePath(): String = source.relativePath
            }
            val script = context.sqlScriptFactory.createSqlScript(executable,
                context.configuration.isMixed, context.resourceProvider)
            listOf(ResolvedMigrationImpl(name.version, name.description, source.relativePath,
                ChecksumCalculator.calculate(source), null, CoreMigrationType.SQL, source.absolutePathOnDisk,
                SqlMigrationExecutor(context.sqlScriptExecutorFactory, script, false, context.configuration.isBatch)))
        })
    }
}
