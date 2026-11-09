package dev.orestegabo.sequo_api.domain.demo

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.catalog.*
import dev.orestegabo.sequo_api.domain.commerce.*
import dev.orestegabo.sequo_api.domain.party.*
import dev.orestegabo.sequo_api.domain.platform.*
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
@Profile("demo")
class DemoCatalog(private val environment: DemoEnvironment, private val users: UserRepository,
    private val merchants: CommerceMerchantRepository, private val storefronts: MerchantStorefrontRepository,
    private val products: CommerceProductRepository, private val groups: ProductCustomizationGroupRepository,
    private val options: ProductCustomizationOptionRepository, private val couriers: SettlementCourierRecordRepository,
    private val addresses: CustomerAddressRepository, private val jdbc: JdbcTemplate) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        // Initial setup only: preserve all subsequent edits, orders and availability changes.
        if (users.existsById(DemoEnvironment.CUSTOMER)) return
        check(users.count() == 0L) { "Demo setup requires a fresh, empty user database." }
        // Hide the historical migration sample catalog in this newly provisioned demo database only.
        jdbc.update("update products set status='INACTIVE'")
        jdbc.update("update merchant_storefronts set active=false")
        jdbc.update("update catalog_promotions set active=false")
        fun user(id: String, name: String, role: RoleCode) {
            // User has a generated JPA identity: explicit fixture IDs must be inserted, not merged as detached users.
            jdbc.update("insert into users(id,email,display_name,is_active,provider,status,created_at,updated_at) values (?,?,?,true,'EMAIL','ACTIVE',current_timestamp,current_timestamp)",id,"$id@demo.invalid","[DEMO] $name")
            jdbc.update("insert into user_roles(user_id,role_code) values (?,?)",id,role.name)
        }
        user(DemoEnvironment.CUSTOMER, "Client Sequo", RoleCode.CUSTOMER)
        user(DemoEnvironment.ADMIN, "Operations", RoleCode.SUPER_ADMIN)
        for ((i,id) in DemoEnvironment.RIDERS.withIndex()) {
            user("$id-user", listOf("Ami", "Kossi", "Sena", "Yao")[i], RoleCode.COURIER)
            couriers.save(CourierRecord(id=id,userId="$id-user",workforceType=if(i==1) "SequoSalaried" else "Freelancer",vehicleType="Moto"))
        }
        addresses.save(CustomerAddressRecord(id="demo-v1-home",userId=DemoEnvironment.CUSTOMER,kind=CustomerAddressKind.HOME,
            label="[DEMO] Maison de test",recipientName="Client Demo",phoneNumber="+22800000000",city="Lome",area="Tokoin",
            streetHint="Adresse fictive — aucune livraison reelle",latitude=6.1780,longitude=1.2370))
        val catalog = ObjectMapper().readTree(ClassPathResource("demo/catalog.json").inputStream)
        for ((si,shop) in catalog.withIndex()) {
            val id=shop["id"].asText(); val owner="$id-owner"
            user(owner,shop["name"].asText(),RoleCode.MERCHANT_OWNER)
            merchants.saveAndFlush(MerchantRecord(id=id,ownerUserId=owner,name="[DEMO] ${shop["name"].asText()}"))
            jdbc.update("insert into merchant_memberships(id,user_id,merchant_id,role_code,active,created_at,updated_at) values (?,?,?,'MERCHANT_OWNER',true,current_timestamp,current_timestamp)","$id-membership",owner,id)
            storefronts.save(MerchantStorefrontRecord(merchantId=id,area=shop["area"].asText(),kind=shop["kind"].asText(),
                distanceKm=1.0+si*0.25,eta="Demo",photoStatus="Illustrations de demonstration",openStatus="Open now",sortOrder=si))
            for ((pi,p) in shop["products"].withIndex()) {
                val pid=p["id"].asText(); val mode=p["mode"].asText()
                products.saveAndFlush(ProductRecord(id=pid,merchantId=id,name="[DEMO] ${p["name"].asText()}",kind=CatalogProductKind.GenericSealedItem,
                    category=shop["category"].asText(),subcategory=shop["kind"].asText(),basePriceCfa=p["price"].asInt(),
                    detail="Produit fictif ${p["code"].asText()} pour essayer commande, preparation et livraison. Illustration, pas une photo verifiee. Aucun achat reel.",
                    imageUrl="${environment.publicUrl}/api/demo/images/$pid",cameraVerified=false,sortOrder=pi*12+si))
                fun group(suffix:String,name:String,type:ProductOptionType,labels:List<String>,unit:ProductMeasureUnit?=null,amounts:List<String>?=null,required:Boolean=true) {
                    val gid="$pid-$suffix"
                    groups.saveAndFlush(ProductCustomizationGroupRecord(id=gid,productId=pid,name=name,minChoices=if(required)1 else 0,maxChoices=1,type=type,unit=unit))
                    for ((oi,label) in labels.withIndex()) options.save(ProductCustomizationOptionRecord(id="$gid-$oi",groupId=gid,name=label,
                        priceDeltaCfa=if(mode=="meal" && suffix=="portion" && oi==1)500 else 0,sortOrder=oi,
                        measureAmount=amounts?.get(oi)?.let(::BigDecimal),active=!(type==ProductOptionType.SIZE && oi==labels.lastIndex)))
                }
                when(mode) {
                    "clothes" -> { group("size","Taille",ProductOptionType.SIZE,listOf("XS","S","M","L","XL","XXL")); group("color","Couleur",ProductOptionType.COLOR,listOf("Indigo","Sable","Corail")) }
                    "kids" -> group("size","Age / taille",ProductOptionType.SIZE,listOf("2 ans","4 ans","6 ans","8 ans","10 ans","12 ans"))
                    "shoes" -> group("size","Pointure EU",ProductOptionType.SIZE,(36..46).map(Int::toString))
                    "kg","l","m" -> {
                        val unit=when(mode){"kg"->ProductMeasureUnit.KG;"l"->ProductMeasureUnit.L;else->ProductMeasureUnit.M}
                        group("amount","Quantite",ProductOptionType.MEASURE,listOf("Petit format","Standard","Familial","Grand format"),unit,listOf("0.5","1","2","5"))
                    }
                    "meal" -> { group("portion","Portion",ProductOptionType.PREPARATION,listOf("Normale","Grande"));group("spice","Piment",ProductOptionType.PREPARATION,listOf("Doux","Moyen","Fort"),required=false) }
                }
            }
        }
    }
}
