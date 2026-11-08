package dev.orestegabo.sequo_api.domain.reference

import dev.orestegabo.sequo.reference.HumanReference
import dev.orestegabo.sequo.reference.PublicReferenceKind
import java.security.SecureRandom
import java.io.Serializable
import java.time.Instant
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

data class PublicReferenceIdentity(val kind: String = "", val internalId: String = "") : Serializable

/** Also models the additive table for supported Hibernate-created local/test schemas. */
@Entity
@IdClass(PublicReferenceIdentity::class)
@Table(name = "public_entity_references", uniqueConstraints = [UniqueConstraint(name = "uk_public_entity_reference", columnNames = ["public_reference"])])
class PublicReferenceRecord(
    @Id @Column(name = "entity_kind", length = 32) val kind: String,
    @Id @Column(name = "internal_id", length = 255) val internalId: String,
    @Column(name = "public_reference", nullable = false, length = 11, updatable = false) val publicReference: String,
    @Column(name = "format_version", nullable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("1") val formatVersion: Int = 1,
    @Column(name = "created_at", nullable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP") val createdAt: Instant = Instant.now(),
)

/** Immutable display metadata. It never changes a domain ID or authorizes access to its target. */
@Service
class PublicReferenceRegistry internal constructor(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val random: SecureRandom,
) {
    @Autowired
    constructor(jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager) :
        this(jdbc, transactionManager, SecureRandom())

    private val allocation = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = false
    }
    private val h2 by lazy {
        jdbc.execute(ConnectionCallback { it.metaData.databaseProductName.equals("H2", ignoreCase = true) }) == true
    }

    fun reference(kind: PublicReferenceKind, internalId: String): String {
        require(internalId.isNotBlank() && internalId.length <= 255) { "A persisted entity identity is required." }
        findReference(kind, internalId)?.let { return it }
        // Normal creation can claim metadata atomically with the domain write. A legacy record
        // projected by a read-only service needs a separate, metadata-only transaction instead.
        return if (TransactionSynchronizationManager.isActualTransactionActive() &&
            !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            claim(kind, internalId)
        } else requireNotNull(allocation.execute { claim(kind, internalId) })
    }

    /** A read-only existence check. In particular, legacy-ID lookup must not create aliases. */
    fun findReference(kind: PublicReferenceKind, internalId: String): String? = jdbc.queryForList(
        "select public_reference from public_entity_references where entity_kind = ? and internal_id = ?",
        String::class.java, kind.name, internalId,
    ).singleOrNull()?.takeIf { HumanReference.isValid(it, kind) }

    fun resolve(kind: PublicReferenceKind, input: String): String? {
        val canonical = HumanReference.normalize(input)?.takeIf { HumanReference.isValid(it, kind) } ?: return null
        return jdbc.queryForList(
            "select internal_id from public_entity_references where entity_kind = ? and public_reference = ?",
            String::class.java, kind.name, canonical,
        ).singleOrNull()
    }

    private fun claim(kind: PublicReferenceKind, internalId: String): String {
        repeat(16) {
            findReference(kind, internalId)?.let { return it }
            val payload = buildString(6) { repeat(6) { append(HumanReference.ALPHABET[random.nextInt(32)]) } }
            val candidate = HumanReference.encode(kind, payload)
            // Do not catch a PostgreSQL unique violation inside the caller's transaction: that
            // transaction would already be aborted. The database arbitrates both kinds of race.
            if (h2) {
                try {
                    jdbc.update(
                        "merge into public_entity_references target using (values (?, ?, ?)) incoming(entity_kind, internal_id, public_reference) " +
                            "on ((target.entity_kind = incoming.entity_kind and target.internal_id = incoming.internal_id) or target.public_reference = incoming.public_reference) " +
                            "when not matched then insert (entity_kind, internal_id, public_reference) values (incoming.entity_kind, incoming.internal_id, incoming.public_reference)",
                        kind.name, internalId, candidate,
                    )
                } catch (_: DuplicateKeyException) {
                    // H2 keeps its transaction usable after a racing statement constraint failure.
                    // This branch is never used for PostgreSQL, whose transaction would be aborted.
                }
            } else jdbc.update(
                "insert into public_entity_references (entity_kind, internal_id, public_reference) values (?, ?, ?) on conflict do nothing",
                kind.name, internalId, candidate,
            )
            findReference(kind, internalId)?.let { return it }
        }
        error("A display reference could not be allocated. Retry after refreshing the record.")
    }
}

/** Redacted or missing relationship identities must never acquire a display alias. */
fun PublicReferenceRegistry?.referenceOrNull(kind: PublicReferenceKind, internalId: String?): String? =
    internalId?.takeIf { it.isNotBlank() }?.let { this?.reference(kind, it) }
