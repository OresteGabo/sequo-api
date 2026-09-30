package dev.orestegabo.sequo_api.domain.platform

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.User
import dev.orestegabo.sequo_api.domain.auth.UserRepository
import dev.orestegabo.sequo_api.domain.auth.UserStatus
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductKind
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import dev.orestegabo.sequo_api.domain.commerce.CommerceMerchantRepository
import dev.orestegabo.sequo_api.domain.commerce.CommerceProductRepository
import dev.orestegabo.sequo_api.domain.party.MerchantRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:platform_commerce_service;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
    ]
)
@Transactional
class PlatformCommerceServiceTest @Autowired constructor(
    private val service: PlatformCommerceService,
    private val users: UserRepository,
    private val merchants: CommerceMerchantRepository,
    private val products: CommerceProductRepository,
    private val plans: SubscriptionPlanRepository,
    private val paymentProviders: PaymentProviderConfigurationRepository,
) {
    @Test
    fun customerProfileAddressSubscriptionAndReferralUseDatabaseRecords() {
        val userId = createUser("platform-customer@sequo.test")
        val referredUserId = createUser("platform-referred@sequo.test")
        plans.save(SubscriptionPlanRecord("plan-basic", "Basic", 2500, 10, 3000, 6, 5))

        val profile = service.upsertProfile(
            userId,
            UpsertCustomerProfileRequest(
                displayName = "Platform Customer",
                phoneNumber = "+22890000000",
                receiptEmail = "Platform-Customer@Sequo.Test",
            ),
        )
        val address = service.upsertAddress(
            userId,
            null,
            UpsertCustomerAddressRequest(
                label = "Maison",
                recipientName = "Platform Customer",
                phoneNumber = "+22890000000",
                city = "Lome",
                area = "Tokoin",
            ),
        )
        val subscription = service.startSubscription(userId, StartSubscriptionRequest("plan-basic"))
        val referral = service.createReferralCredit(userId, CreateReferralCreditRequest(referredUserId, "WELCOME"))

        assertEquals("platform-customer@sequo.test", profile.receiptEmail)
        assertEquals("Tokoin", address.area)
        assertEquals(CustomerSubscriptionStatus.ACTIVE, subscription.status)
        assertEquals(500, referral.creditCfa)
        assertEquals(1, service.referralCredits(userId).size)
    }

    @Test
    fun productCustomizationsPaymentProvidersAndCooperativesArePersisted() {
        merchants.save(MerchantRecord(id = "merchant-owner", name = "Owner Merchant"))
        merchants.save(MerchantRecord(id = "merchant-member", name = "Member Merchant"))
        products.save(
            ProductRecord(
                id = "food-product",
                merchantId = "merchant-owner",
                name = "Rice plate",
                kind = CatalogProductKind.SellerSpecific,
                status = CatalogProductStatus.ACTIVE,
                category = "food",
                basePriceCfa = 2500,
            )
        )
        paymentProviders.save(
            PaymentProviderConfigurationRecord(
                providerId = "yas_togo",
                displayName = "YAS Togo",
                countryCode = "TG",
                readiness = PaymentProviderReadiness.PLANNED,
                notes = "Prepared for later integration.",
            )
        )

        val customizations = service.replaceCustomizations(
            "food-product",
            ReplaceProductCustomizationsRequest(
                groups = listOf(
                    ProductCustomizationGroupRequest(
                        name = "Supplements",
                        minChoices = 0,
                        maxChoices = 2,
                        options = listOf(ProductCustomizationOptionRequest("Alloco", 500)),
                    )
                )
            ),
        )
        val cooperative = service.createCooperative(
            CreateCooperativeRequest(
                code = "TOKOIN-FOOD",
                name = "Tokoin Food Cooperative",
                city = "Lome",
                ownerMerchantId = "merchant-owner",
            )
        )
        val withMember = service.addCooperativeMember(
            cooperative.id,
            AddCooperativeMemberRequest("merchant-member"),
        )

        assertEquals("Supplements", customizations.single().name)
        assertEquals("Alloco", customizations.single().options.single().name)
        assertTrue(service.paymentProviders().any { it.providerId == "yas_togo" })
        assertEquals(2, withMember.members.size)
        assertNotNull(service.cooperatives().single { it.id == cooperative.id })
    }

    private fun createUser(email: String): String =
        requireNotNull(users.save(User(email = email, provider = AuthProvider.EMAIL, status = UserStatus.ACTIVE)).id)
}
