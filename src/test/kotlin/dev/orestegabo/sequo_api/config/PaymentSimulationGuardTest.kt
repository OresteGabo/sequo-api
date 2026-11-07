package dev.orestegabo.sequo_api.config

import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentSimulationGuardTest {
    private val context = ApplicationContextRunner().withBean(PaymentSimulationGuard::class.java)

    @Test
    fun productionRejectsSimulatorEvenWithDevelopmentSecurityOverride() {
        context.withPropertyValues(
            "spring.profiles.active=production",
            "sequo.security.allow-dev-defaults=true",
            "sequo.payments.simulator.enabled=true",
        ).run {
            val error = assertNotNull(it.startupFailure)
            assertTrue(generateSequence(error as Throwable?) { cause -> cause.cause }.any { cause ->
                cause.message.orEmpty().contains("Runtime payment simulation is unsupported")
            })
        }
    }

    @Test
    fun simulatorIsDisabledByDefault() {
        context.run { assertNull(it.startupFailure) }
    }
}
