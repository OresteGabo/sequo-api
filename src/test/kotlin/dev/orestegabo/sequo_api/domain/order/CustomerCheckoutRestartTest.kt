package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.support.databaseInstant
import dev.orestegabo.sequo_api.SequoApiApplication
import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.*
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import dev.orestegabo.sequo_api.domain.platform.*
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomerCheckoutRestartTest {
    @TempDir lateinit var databaseDirectory: Path

    @Test
    fun pendingCheckoutAndRetrySurviveApplicationRestart() {
        var customerId: String
        lateinit var submitted: CustomerCheckoutSummary
        lateinit var request: SubmitCartCheckoutRequest
        startApplication().use { context ->
            val user = context.getBean(UserRepository::class.java).save(User(email = "restart@sequo.test", provider = AuthProvider.EMAIL))
            customerId = requireNotNull(user.id)
            context.getBean(CommerceMerchantRepository::class.java).save(MerchantRecord(id = "restart-shop", name = "Restart shop"))
            context.getBean(CommerceProductRepository::class.java).save(ProductRecord(
                id = "restart-product", merchantId = "restart-shop", name = "Sealed item",
                kind = CatalogProductKind.GenericSealedItem, basePriceCfa = 2000, imageUrl = "https://fixture.invalid/image.jpg",
            ))
            context.getBean(CustomerAddressRepository::class.java).save(CustomerAddressRecord(
                "restart-address", customerId, CustomerAddressKind.HOME, "Home", "Fixture recipient",
                "+22890000000", "Lomé", latitude = 0.0, longitude = 0.01,
            ))
            context.getBean(CommerceService::class.java).putCartItem(customerId, UpsertCartItemRequest("restart-product", 1))
            val quote = context.getBean(CartDeliveryQuoteService::class.java).quote(customerId, "restart-address")
            request = SubmitCartCheckoutRequest("restart-address", quote.revision, "yas_togo")
            submitted = context.getBean(CustomerCartCheckoutService::class.java).submit(customerId, request)
        }
        startApplication().use { context ->
            val service = context.getBean(CustomerCartCheckoutService::class.java)
            assertEquals(submitted.copy(createdAt = databaseInstant(submitted.createdAt)), service.get(customerId, submitted.checkoutId))
            assertEquals(submitted.copy(createdAt = databaseInstant(submitted.createdAt)), service.submit(customerId, request))
            assertEquals(1, service.list(customerId).size)
            assertNull(context.getBean(CustomerOrderRecordRepository::class.java).findByCheckoutId(submitted.checkoutId))
        }
    }

    private fun startApplication(): ConfigurableApplicationContext =
        SpringApplicationBuilder(SequoApiApplication::class.java).run(
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:file:${databaseDirectory.resolve("checkout")}",
            "--spring.jpa.hibernate.ddl-auto=update",
            "--spring.flyway.enabled=false",
            "--sequo.cart.straight-line-dispatch-origin=0,0",
        )
}
