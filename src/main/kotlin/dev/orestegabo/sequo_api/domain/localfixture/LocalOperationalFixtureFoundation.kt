package dev.orestegabo.sequo_api.domain.localfixture

import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.*
import dev.orestegabo.sequo_api.domain.commerce.*
import dev.orestegabo.sequo_api.domain.delivery.CourierAvailabilityService
import dev.orestegabo.sequo_api.domain.hub.*
import dev.orestegabo.sequo_api.domain.party.*
import dev.orestegabo.sequo_api.domain.platform.*
import dev.orestegabo.sequo_api.domain.relay.RelayPointRepository
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.temporal.ChronoUnit

/** Master data only; paid orders, fulfillment and custody are made by normal services separately. */
@Service
class LocalOperationalFixtureFoundation(
    private val policy: LocalOperationalFixturePolicy,
    private val jdbc: JdbcTemplate,
    private val users: UserRepository,
    private val merchants: CommerceMerchantRepository,
    private val products: CommerceProductRepository,
    private val couriers: SettlementCourierRecordRepository,
    private val sites: RelayPointRepository,
    private val lockers: RelayLockerRepository,
    private val availability: CourierAvailabilityService,
    private val groups: ProductCustomizationGroupRepository,
    private val options: ProductCustomizationOptionRepository,
    private val entityManager: EntityManager,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun seed(c: LocalOperationalFixtureContext): List<LocalFixtureAccount> {
        policy.requireEnabled()
        val people = accounts(c)
        people.forEach { person ->
            val existing = users.findById(person.id).orElse(null)
            if (existing != null) {
                require(existing.email == person.email && existing.provider == AuthProvider.EMAIL) {
                    "Local fixture identity collision; existing records were not changed."
                }
                // Never reactivate, repair roles, or restore an account the operator has changed.
            } else {
                require(users.findByEmail(person.email) == null) { "Local fixture email is already bound elsewhere." }
                jdbc.update("insert into users(id,email,display_name,is_active,provider,status,created_at,updated_at) values (?,?,?,true,'EMAIL','ACTIVE',?,?)",
                    person.id, person.email, person.name, Timestamp.from(c.createdAt.minus(45, ChronoUnit.DAYS)), Timestamp.from(c.createdAt.minus(45, ChronoUnit.DAYS)))
                person.roles.forEach { role -> jdbc.update("insert into user_roles(user_id,role_code) values (?,?)", person.id, role) }
            }
        }
        for ((index, name) in listOf("[TEST] Maison des essentiels — Tokoin", "[TEST] Atelier des familles — Adidogomé").withIndex()) {
            val merchantId = c.merchantIds[index]
            val owner = c.id(if (index == 0) "owner-yawa" else "owner-mawuli")
            val existing = merchants.findById(merchantId).orElse(null)
            if (existing == null) merchants.saveAndFlush(MerchantRecord(id = merchantId, ownerUserId = owner, name = name,
                createdAt = c.createdAt.minus(45, ChronoUnit.DAYS), updatedAt = c.createdAt))
            else require(existing.ownerUserId == owner) { "Local merchant ownership collision." }
            membership(c, owner, merchantId, "MERCHANT_OWNER", "owner-$index")
        }
        membership(c, c.id("staff-eyram"), c.merchantIds[0], "MERCHANT_STAFF", "staff")
        for ((index, userSuffix) in listOf("rider-senam", "rider-komlan", "rider-kokou", "rider-akossua").withIndex()) {
            val id = c.courierIds[index]
            val userId = c.id(userSuffix)
            val existing = couriers.findById(id).orElse(null)
            if (existing == null) {
                couriers.saveAndFlush(CourierRecord(id = id, userId = userId,
                    workforceType = if (index == 1) "SequoSalaried" else "Freelancer", vehicleType = "Moto",
                    createdAt = c.createdAt.minus(45, ChronoUnit.DAYS), updatedAt = c.createdAt))
                if (index == 3) availability.pause(id, c.adminId, "LOCAL_FIXTURE — rider taking a scheduled break", at = c.createdAt)
            } else require(existing.userId == userId) { "Local rider ownership collision." }
        }
        for (index in 0..1) {
            val id = c.relayPointIds[index]
            val existing = sites.findById(id).orElse(null)
            if (existing == null) sites.saveAndFlush(RelayPointRecord(id = id, operatorUserId = c.relayOperatorIds[index],
                name = if (index == 0) "[TEST] Relais Tokoin" else "[TEST] Relais Adidogomé", city = "Lomé (local test)",
                neighborhood = if (index == 0) "Tokoin" else "Adidogomé", landmark = "Synthetic site — no physical locker or delivery",
                latitude = if (index == 0) 6.1725 else 6.1950, longitude = if (index == 0) 1.2314 else 1.1700,
                createdAt = c.createdAt.minus(45, ChronoUnit.DAYS), updatedAt = c.createdAt))
            // Retain operator/site edits on interrupted setup or subsequent checks.
            val gridId = c.id("grid-${index + 1}")
            if (entityManager.find(RelayLockerGridRecord::class.java, gridId) == null) {
                entityManager.persist(RelayLockerGridRecord(id = gridId, relayPointId = id, gridCode = "TEST-${index + 1}", label = "Local logical slots", createdAt = c.createdAt.minus(45, ChronoUnit.DAYS), updatedAt = c.createdAt))
                entityManager.flush()
            }
            for (slot in 1..3) {
                val slotId = c.id("slot-${index + 1}-$slot")
                if (!lockers.existsById(slotId)) lockers.save(RelayLockerRecord(id = slotId, relayPointId = id, relayLockerGridId = gridId,
                    lockerCode = "TEST-${if (index == 0) 'A' else 'B'}$slot", createdAt = c.createdAt.minus(45, ChronoUnit.DAYS), updatedAt = c.createdAt))
            }
        }
        product(c, c.generalProductId, c.merchantIds[0], "[TEST] Gourde inox 750 ml", 4800, "GenericSealedItem", true)
        product(c, c.secondMerchantProductId, c.merchantIds[1], "[TEST] Lot de trois carnets", 2400, "GenericSealedItem", false)
        product(c, c.id("product-inactive"), c.merchantIds[0], "[TEST] Sac de courses — brouillon", 3200, "GenericSealedItem", false, false)
        product(c, c.foodProductId, c.merchantIds[0], "[TEST] Déjeuner — options et photo à préparer", 2200, "Food", false, false)
        val group = c.id("food-size")
        if (!groups.existsById(group)) {
            groups.save(ProductCustomizationGroupRecord(id = group, productId = c.foodProductId, name = "Portion", minChoices = 1, maxChoices = 1))
            options.save(ProductCustomizationOptionRecord(id = c.id("food-regular"), groupId = group, name = "Normale", priceDeltaCfa = 0))
            options.save(ProductCustomizationOptionRecord(id = c.id("food-large"), groupId = group, name = "Grande", priceDeltaCfa = 500, sortOrder = 1))
            options.save(ProductCustomizationOptionRecord(id = c.id("food-unavailable"), groupId = group, name = "Supplément indisponible", priceDeltaCfa = 300, sortOrder = 2, active = false))
        }
        return people
    }

    private fun product(c: LocalOperationalFixtureContext, id: String, merchant: String, name: String, price: Int,
        category: String, bargaining: Boolean, active: Boolean = true) {
        val existing = products.findById(id).orElse(null)
        if (existing != null) {
            require(existing.merchantId == merchant) { "Local product ownership collision." }
            return
        }
        products.save(ProductRecord(id, merchant, name = name,
            kind = if (category == "Food") CatalogProductKind.SellerSpecific else CatalogProductKind.GenericSealedItem,
            status = if (active) CatalogProductStatus.ACTIVE else CatalogProductStatus.INACTIVE,
            category = category, detail = "LOCAL_FIXTURE — synthetic product for admin practice. No real sale or capture evidence.",
            basePriceCfa = price, bargainingEnabled = bargaining, photoRequired = category == "Food",
            cameraVerified = false, createdAt = c.createdAt.minus(45, ChronoUnit.DAYS), updatedAt = c.createdAt))
    }

    private fun membership(c: LocalOperationalFixtureContext, user: String, merchant: String, role: String, suffix: String) {
        val count = jdbc.queryForObject("select count(*) from merchant_memberships where user_id=? and merchant_id=? and role_code=?",
            Long::class.java, user, merchant, role) ?: 0
        if (count == 0L) jdbc.update("insert into merchant_memberships(id,user_id,merchant_id,role_code,active,created_at,updated_at) values (?,?,?,?,true,?,?)",
            c.id("membership-$suffix"), user, merchant, role, Timestamp.from(c.createdAt.minus(45, ChronoUnit.DAYS)), Timestamp.from(c.createdAt.minus(45, ChronoUnit.DAYS)))
    }

    fun accounts(c: LocalOperationalFixtureContext): List<LocalFixtureAccount> {
        fun person(suffix: String, name: String, role: String, purpose: String) = LocalFixtureAccount(c.id(suffix), "[TEST] $name",
            "$suffix@fixture.invalid", listOf(role), purpose)
        return listOf(
            person("customer-ama", "Ama Dzifa", "CUSTOMER", "Owns the synthetic orders and returns"),
            person("customer-koffi", "Koffi Mensah", "CUSTOMER", "Second customer for isolation and access controls"),
            person("owner-yawa", "Yawa Adjei", "MERCHANT_OWNER", "First store owner"),
            person("owner-mawuli", "Mawuli Akakpo", "MERCHANT_OWNER", "Second store owner"),
            person("staff-eyram", "Eyram Gaba", "MERCHANT_STAFF", "First store team member"),
            person("staff-unassigned", "Afi Amavi", "MERCHANT_STAFF", "Eligible existing account for membership assignment"),
            person("rider-senam", "Senam Nayo — indépendant disponible", "COURIER", "Available independent rider"),
            person("rider-komlan", "Komlan Bessa — salarié disponible", "COURIER", "Available salaried rider"),
            person("rider-kokou", "Kokou Agbo — mission en cours", "COURIER", "Independent rider with active mission"),
            person("rider-akossua", "Akossua Bello — en pause", "COURIER", "Paused independent rider"),
            person("operator-dede", "Dédé Afi — Tokoin", "RELAY_PARTNER", "First relay operator"),
            person("operator-esso", "Esso Mensah — Adidogomé", "RELAY_PARTNER", "Second relay operator"),
            person("operator-backup", "Nafi Dossou — remplaçante", "RELAY_PARTNER", "Unassigned operator for safe relay assignment practice"),
            person("admin-ordinary", "Administrateur opérations", "ADMIN", "Existing ordinary administrator for role/access practice"),
            person("admin-backup", "Administrateur de secours", "SUPER_ADMIN", "Spare super administrator; fixed local fixture only"),
        )
    }

    fun scenarios(c: LocalOperationalFixtureContext) = listOf(
        LocalFixtureScenario(c.id("accounts"), "Accounts, roles and store teams", "Synthetic people with scoped store memberships; no passwords or real contact details.", "users",
            mapOf("userId" to c.id("staff-unassigned"), "merchantId" to c.merchantIds[0]), listOf("Assign the unassigned staff account to a store.", "Change another test account's access or role; seeding again will not repair it.")),
        LocalFixtureScenario(c.id("catalog"), "Catalog editing and publication", "Two active standard products, an unpublished draft and an unpublished photo-required food menu.", "catalog",
            mapOf("productId" to c.foodProductId, "merchantId" to c.merchantIds[0]), listOf("Edit portion supplements or availability.", "Photo-required publication stays blocked without genuine camera evidence.")),
        LocalFixtureScenario(c.id("riders"), "Available, busy and paused riders", "Independent and salaried fixtures preserve their workforce snapshots.", "riders",
            mapOf("courierId" to c.courierIds[3]), listOf("Resume the paused rider with a reason.", "Change a test rider's future workforce classification; existing earnings stay recorded.")),
        LocalFixtureScenario(c.id("relay-sites"), "Two relay sites and a replacement operator", "Logical slots only; no physical locker controls.", "relay",
            mapOf("relayPointId" to c.relayPointIds[0], "operatorUserId" to c.id("operator-backup")), listOf("Review site membership and storage slots.", "Remove an operator assignment before moving that operator to another site.")),
        LocalFixtureScenario(c.id("store"), "Store hours and ordering pause", "Test stores start with legacy manual availability; no schedule or tariff is invented.", "merchants",
            mapOf("merchantId" to c.merchantIds[0]), listOf("Configure store hours or pause new orders; historical orders remain."))
    )
}
