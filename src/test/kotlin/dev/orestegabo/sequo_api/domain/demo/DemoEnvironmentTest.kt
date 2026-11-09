package dev.orestegabo.sequo_api.domain.demo

import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.MapPropertySource
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import kotlin.test.*

class DemoEnvironmentTest {
    private fun env(vararg changes:Pair<String,Any>)=StandardEnvironment().apply {
        setActiveProfiles("demo")
        propertySources.addFirst(MapPropertySource("demo-test", mutableMapOf<String,Any>(
            "spring.datasource.url" to "jdbc:postgresql://demo-postgres:5432/sequo_demov1",
            "spring.datasource.username" to "demo_app", "sequo.auth.jwt.issuer" to "sequo-demo-v1",
            "sequo.auth.jwt.audience" to "sequo-demo-mobile", "sequo.demo.access-code" to "private-unit-test-code-12345",
            "sequo.demo.public-url" to "https://api.sequoservice.com:8443").apply {putAll(changes)}))
    }
    @Test fun acceptsOnlyDedicatedRealmAndPrivateCode() {
        val policy=DemoEnvironment(env())
        assertTrue(policy.accepts("private-unit-test-code-12345"));assertFalse(policy.accepts("wrong"))
    }
    @Test fun rejectsProductionDatabaseAndMixedProfiles() {
        assertFailsWith<IllegalArgumentException> {DemoEnvironment(env("spring.datasource.url" to "jdbc:postgresql://postgres:5432/sequo"))}
        assertFailsWith<IllegalArgumentException> {DemoEnvironment(env().apply{setActiveProfiles("demo","prod")})}
        assertFailsWith<IllegalArgumentException> {DemoEnvironment(env("sequo.auth.jwt.issuer" to "sequo-api"))}
    }
    @Test fun refusesProviderCredentialsAndOutgoingWorkers() {
        for(key in listOf("sequo.wallets.yas-togo.api-key","sequo.wallets.moov-africa.webhook-secret"))
            assertFailsWith<IllegalArgumentException> { DemoEnvironment(env(key to "credential")) }
        for(key in listOf("sequo.notifications.outbox-worker.enabled","sequo.notifications.provider-delivery-worker.enabled","sequo.admin-auth.email.enabled","sequo.payments.simulator.enabled"))
            assertFailsWith<IllegalArgumentException> { DemoEnvironment(env(key to true)) }
    }
    @Test fun catalogHasUniqueImagesProductsAndAllSellingModes() {
        val shops=ObjectMapper().readTree(ClassPathResource("demo/catalog.json").inputStream)
        assertEquals(12,shops.size())
        val products=shops.flatMap{it["products"].toList()}
        assertEquals(120,products.size);assertEquals(120,products.map{it["id"].asText()}.toSet().size)
        assertTrue(products.all{ClassPathResource("demo/${it["id"].asText()}.jpg").exists()})
        assertEquals(setOf("meal","kg","l","m","clothes","kids","shoes","unit"),products.map{it["mode"].asText()}.toSet())
    }
    @Test fun demoIllustrationsAreRejectedByOrdinaryOrderProcessors() {
        val payment=PaymentProcessor(listOf(YasTogoPaymentMethod(validateHandler={PaymentValidationResult.pending("Demo pending")})))
        val request=OrderProcessingRequest("test-checkout","customer",OrderServiceLevel.Regular,OrderRoute.FastDelivery,
            listOf(OrderLineRequest("demo-v1-bistro-01","seller","Demo","Demo meal",OrderProductCategory.Food,1,1000,photoEvidence=ProductPhotoEvidence.DemoIllustration)),1.0,
            paymentProvider=SequoPaymentProviders.YasTogo,paymentReference="demo-test")
        assertTrue(RegularOrderProcessor(payment,DeliveryPricingService()).process(request) is OrderProcessingResult.Rejected)
        val processor=RegularOrderProcessor(payment,DeliveryPricingService(),allowDemoIllustrations=true)
        assertTrue(processor.process(request) is OrderProcessingResult.AwaitingPaymentValidation)
        assertTrue(processor.process(request.copy(lines=request.lines.map{it.copy(productId="real-product")})) is OrderProcessingResult.Rejected)
    }
}
