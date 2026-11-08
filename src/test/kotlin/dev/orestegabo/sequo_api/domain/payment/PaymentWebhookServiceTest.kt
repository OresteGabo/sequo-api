package dev.orestegabo.sequo_api.domain.payment

import dev.orestegabo.sequo_api.domain.DomainConfig
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.context.ApplicationEventPublisher
import org.mockito.Mockito

class PaymentWebhookServiceTest {
    private val repository = Mockito.mock(PaymentWebhookEventRepository::class.java)
    private val publisher = Mockito.mock(ApplicationEventPublisher::class.java)
    private val entityManager = Mockito.mock(jakarta.persistence.EntityManager::class.java)
    private val fixtureSecrets = mapOf(SequoPaymentProviders.YasTogo to "yas-secret", SequoPaymentProviders.MoovAfrica to "moov-secret")
    private val service = PaymentWebhookService(repository, publisher, NormalizedPaymentWebhookFixtureVerifier(fixtureSecrets), entityManager)
    private val receivedAt = Instant.parse("2026-09-09T10:05:00Z")

    @Test
    fun acceptsSignedWebhookAndReplaysItIdempotently() {
        val command = command()
        val first = service.accept(command)
        Mockito.`when`(repository.findByProviderAndEventId("yas_togo", "event-1"))
            .thenReturn(PaymentWebhookEventRecord(
                id = "yas_togo:event-1",
                provider = "yas_togo",
                eventId = "event-1",
                checkoutId = "checkout-1",
                paymentReference = "payment-1",
                providerReference = "provider-1",
                amountCfa = 4_000,
                status = PaymentWebhookProviderStatus.VALIDATED,
                occurredAt = command.occurredAt,
                payloadHash = sha256(command.rawPayload),
                receivedAt = command.receivedAt,
            ))

        val duplicate = service.accept(command)

        assertEquals(false, first.duplicate)
        assertEquals(true, duplicate.duplicate)
        Mockito.verify(entityManager, Mockito.times(1)).persist(Mockito.any(PaymentWebhookEventRecord::class.java))
    }

    @Test
    fun rejectsInvalidSignatureAndExpiredTimestamp() {
        assertFailsWith<IllegalArgumentException> {
            service.accept(command(signature = "sha256=wrong"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.accept(command(signatureTimestamp = receivedAt.minusSeconds(301)))
        }
        Mockito.verifyNoInteractions(repository, entityManager)
    }

    @Test
    fun rejectsReusedEventIdWithDifferentPayload() {
        val original = command()
        Mockito.`when`(repository.findByProviderAndEventId("yas_togo", "event-1"))
            .thenReturn(PaymentWebhookEventRecord(
                id = "yas_togo:event-1",
                provider = "yas_togo",
                eventId = "event-1",
                checkoutId = original.checkoutId,
                paymentReference = original.paymentReference,
                providerReference = original.providerReference,
                amountCfa = original.amountCfa,
                status = original.status,
                occurredAt = original.occurredAt,
                payloadHash = sha256(original.rawPayload),
                receivedAt = original.receivedAt,
            ))

        assertFailsWith<IllegalArgumentException> {
            service.accept(command(rawPayload = "{\"amountCfa\":9999}"))
        }
    }

    @Test
    fun rejectsTrackedDevelopmentWebhookCredentialsAndWrongCurrency() {
        val developmentService = PaymentWebhookService(repository, publisher, DomainConfig().paymentWebhookVerifier(listOf(
            YasTogoPaymentProviderAdapter(PaymentProviderAdapterConfiguration(webhookSecret = "yas_togo_dev_webhook_secret_2026_change_before_prod")),
            MoovAfricaPaymentProviderAdapter(PaymentProviderAdapterConfiguration()),
        )), entityManager)
        assertFailsWith<IllegalArgumentException> { developmentService.accept(command()) }
        assertFailsWith<IllegalArgumentException> { service.accept(command().copy(currency = "USD")) }
        Mockito.verifyNoInteractions(repository, publisher, entityManager)
    }

    @Test
    fun productionAdapterGateRejectsBothProvidersRegardlessOfCredentialCompleteness() {
        val configurations = listOf(
            PaymentProviderAdapterConfiguration(),
            PaymentProviderAdapterConfiguration("not-a-url", "placeholder", " ", "placeholder"),
            PaymentProviderAdapterConfiguration("https://wallet-fixture.sequo.tg", "synthetic-real-looking-key", "fixture-merchant", "yas-secret"),
        )
        for (configuration in configurations) {
            val production = PaymentWebhookService(repository, publisher, DomainConfig().paymentWebhookVerifier(listOf(
                YasTogoPaymentProviderAdapter(configuration), MoovAfricaPaymentProviderAdapter(configuration.copy(webhookSecret = "moov-secret")),
            )), entityManager)
            for (provider in fixtureSecrets.keys) {
                assertFailsWith<IllegalArgumentException> { production.accept(command(provider = provider)) }
            }
        }
        Mockito.verifyNoInteractions(repository, publisher, entityManager)
    }

    @Test
    fun explicitInternalVerifierAcceptsBothProviderFixturesWithoutChangingProductionAdapters() {
        for (provider in fixtureSecrets.keys) {
            val result = service.accept(command(provider = provider))
            assertEquals(provider, result.provider)
            assertEquals(true, result.accepted)
        }
        Mockito.verify(entityManager, Mockito.times(2)).persist(Mockito.any(PaymentWebhookEventRecord::class.java))
        Mockito.verify(publisher, Mockito.times(2)).publishEvent(Mockito.any(PaymentWebhookAcceptedEvent::class.java))
    }

    private fun command(
        signatureTimestamp: Instant = receivedAt,
        signature: String? = null,
        rawPayload: String = "{\"eventId\":\"event-1\",\"amountCfa\":4000}",
        provider: PaymentProviderId = SequoPaymentProviders.YasTogo,
    ): PaymentWebhookCommand {
        val signatureValue = signature ?: "sha256=${hmac(requireNotNull(fixtureSecrets[provider]), "${signatureTimestamp.epochSecond}.$rawPayload")}"
        return PaymentWebhookCommand(
            provider = provider,
            eventId = "event-1",
            checkoutId = "checkout-1",
            paymentReference = "payment-1",
            providerReference = "provider-1",
            amountCfa = 4_000,
            status = PaymentWebhookProviderStatus.VALIDATED,
            occurredAt = receivedAt.minusSeconds(30),
            receivedAt = receivedAt,
            signatureTimestamp = signatureTimestamp,
            signature = signatureValue,
            rawPayload = rawPayload,
        )
    }
}

private fun hmac(secret: String, value: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
