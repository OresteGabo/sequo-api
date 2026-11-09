package dev.orestegabo.sequo_api.domain.demo

import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.commerce.*
import dev.orestegabo.sequo_api.domain.platform.*
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.*

/** Exercise the seed in a disposable database without enabling online demo auth/controllers. */
@SpringBootTest(properties=["spring.datasource.url=jdbc:h2:mem:demo_catalog_seed;DB_CLOSE_DELAY=-1"])
@Import(DemoCatalogPersistenceTest.Config::class)
class DemoCatalogPersistenceTest @Autowired constructor(private val catalog:DemoCatalog,private val products:CommerceProductRepository,
    private val users:UserRepository,private val jdbc:JdbcTemplate) {
    @Test fun insertsExplicitAccountsAndKeepsEditsWhenRunAgain() {
        assertEquals(18,users.count())
        assertEquals(120,products.count())
        assertEquals(12,jdbc.queryForObject("select count(*) from merchant_memberships",Int::class.java))
        val product=products.findById("demo-v1-mode-01").orElseThrow()
        product.name="[DEMO] Edited by tester";products.saveAndFlush(product)
        catalog.run(DefaultApplicationArguments())
        assertEquals("[DEMO] Edited by tester",products.findById(product.id).orElseThrow().name)
        assertEquals(120,products.count())
    }
    @TestConfiguration
    class Config {
        @Bean fun demoCatalogForTest(c:ApplicationContext):DemoCatalog {
            val e=StandardEnvironment().apply {
                setActiveProfiles("demo")
                propertySources.addFirst(MapPropertySource("isolated-policy",mapOf(
                    "spring.datasource.url" to "jdbc:postgresql://demo-postgres:5432/sequo_demov1", "spring.datasource.username" to "demo_app",
                    "sequo.auth.jwt.issuer" to "sequo-demo-v1","sequo.auth.jwt.audience" to "sequo-demo-mobile",
                    "sequo.demo.access-code" to "private-unit-test-code-12345","sequo.demo.public-url" to "https://api.sequoservice.com:8443")))
            }
            return DemoCatalog(DemoEnvironment(e),c.getBean(UserRepository::class.java),c.getBean(CommerceMerchantRepository::class.java),
                c.getBean(MerchantStorefrontRepository::class.java),c.getBean(CommerceProductRepository::class.java),c.getBean(ProductCustomizationGroupRepository::class.java),
                c.getBean(ProductCustomizationOptionRepository::class.java),c.getBean(SettlementCourierRecordRepository::class.java),c.getBean(CustomerAddressRepository::class.java),c.getBean(JdbcTemplate::class.java))
        }
    }
}
