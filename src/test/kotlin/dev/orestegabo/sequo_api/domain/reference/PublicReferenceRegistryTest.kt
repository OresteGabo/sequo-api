package dev.orestegabo.sequo_api.domain.reference

import dev.orestegabo.sequo.reference.HumanReference
import dev.orestegabo.sequo.reference.PublicReferenceKind
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.transaction.support.TransactionTemplate

class PublicReferenceRegistryTest {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var manager: DataSourceTransactionManager
    private lateinit var registry: PublicReferenceRegistry

    @BeforeEach
    fun database() {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:reference-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "")
        dataSource.connection.use { ScriptUtils.executeSqlScript(it, ClassPathResource("db/migration/V61__durable_public_entity_references.sql")) }
        jdbc = JdbcTemplate(dataSource)
        manager = DataSourceTransactionManager(dataSource)
        registry = PublicReferenceRegistry(jdbc, manager)
    }

    @Test
    fun canonicalReferenceSurvivesNewServiceAndNormalizationWithoutChangingIdentity() {
        assertNull(registry.findReference(PublicReferenceKind.ORDER, "SQ-legacy:order"))
        val reference = registry.reference(PublicReferenceKind.ORDER, "SQ-legacy:order")
        assertTrue(HumanReference.isValid(reference, PublicReferenceKind.ORDER))
        val restarted = PublicReferenceRegistry(jdbc, manager)
        assertEquals(reference, restarted.reference(PublicReferenceKind.ORDER, "SQ-legacy:order"))
        assertEquals("SQ-legacy:order", restarted.resolve(PublicReferenceKind.ORDER, reference.lowercase().replace("-", " ")))
        assertEquals(1, count())
    }

    @Test
    fun differentKindsCanKeepTheSameLegacyIdWithoutSharingAReference() {
        val order = registry.reference(PublicReferenceKind.ORDER, "legacy")
        val parcel = registry.reference(PublicReferenceKind.PARCEL, "legacy")
        assertNotEquals(order, parcel)
        assertNull(registry.resolve(PublicReferenceKind.ORDER, parcel))
        assertEquals(2, count())
    }

    @Test
    fun invalidUnknownOrWrongKindLookupNeverAllocates() {
        val unknown = HumanReference.encode(PublicReferenceKind.RETURN, "000000")
        assertNull(registry.resolve(PublicReferenceKind.RETURN, unknown))
        assertNull(registry.resolve(PublicReferenceKind.ORDER, unknown))
        assertNull(registry.resolve(PublicReferenceKind.RETURN, "not-a-reference"))
        assertNull(registry.findReference(PublicReferenceKind.RETURN, "unknown"))
        assertEquals(0, count())
        assertThrows(IllegalArgumentException::class.java) { registry.reference(PublicReferenceKind.USER, " ") }
    }

    @Test
    fun aCandidateCollisionRetriesWithoutReplacingEitherTarget() {
        val generator = SymbolsRandom(IntArray(12) { 0 } + IntArray(6) { 1 })
        val controlled = PublicReferenceRegistry(jdbc, manager, generator)
        val first = controlled.reference(PublicReferenceKind.ORDER, "first")
        val second = controlled.reference(PublicReferenceKind.ORDER, "second")
        assertNotEquals(first, second)
        assertEquals(first, controlled.reference(PublicReferenceKind.ORDER, "first"))
        assertEquals("first", controlled.resolve(PublicReferenceKind.ORDER, first))
        assertEquals("second", controlled.resolve(PublicReferenceKind.ORDER, second))
        assertEquals(2, count())
    }

    @Test
    fun repeatedCollisionFailsBoundedlyWithoutOverwritingTheExistingMapping() {
        val controlled = PublicReferenceRegistry(jdbc, manager, SymbolsRandom(intArrayOf(0)))
        val first = controlled.reference(PublicReferenceKind.ORDER, "first")
        assertThrows(IllegalStateException::class.java) { controlled.reference(PublicReferenceKind.ORDER, "second") }
        assertEquals(first, controlled.findReference(PublicReferenceKind.ORDER, "first"))
        assertNull(controlled.findReference(PublicReferenceKind.ORDER, "second"))
        assertEquals(1, count())
    }

    @Test
    fun concurrentClaimsForOneEntityReturnOneCanonicalMapping() {
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(6)
        try {
            val calls = (1..6).map {
                workers.submit<String> { ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); registry.reference(PublicReferenceKind.DELIVERY, "existing-mission") }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            assertEquals(1, calls.map { it.get(10, TimeUnit.SECONDS) }.toSet().size)
            assertEquals(1, count())
        } finally { start.countDown(); workers.shutdownNow() }
    }

    @Test
    fun readOnlyLegacyProjectionClaimsOnlyIndependentMetadata() {
        val readOnly = TransactionTemplate(manager).apply { isReadOnly = true }
        val reference = readOnly.execute { status ->
            registry.reference(PublicReferenceKind.RETURN, "legacy-return").also { status.setRollbackOnly() }
        }
        assertEquals(reference, registry.findReference(PublicReferenceKind.RETURN, "legacy-return"))
        assertEquals(1, count())
    }

    @Test
    fun newDomainWriteAndReferenceClaimRollBackTogether() {
        TransactionTemplate(manager).execute { status ->
            registry.reference(PublicReferenceKind.ORDER, "uncommitted-new-order")
            status.setRollbackOnly()
        }
        assertNull(registry.findReference(PublicReferenceKind.ORDER, "uncommitted-new-order"))
        assertEquals(0, count())
    }

    private fun count() = jdbc.queryForObject("select count(*) from public_entity_references", Int::class.java)!!
    private class SymbolsRandom(private val symbols: IntArray) : SecureRandom() {
        private val offset = AtomicInteger()
        override fun nextInt(bound: Int) = symbols[offset.getAndIncrement().coerceAtMost(symbols.lastIndex)] % bound
    }
}
