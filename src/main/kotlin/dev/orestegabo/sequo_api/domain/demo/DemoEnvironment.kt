package dev.orestegabo.sequo_api.domain.demo

import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.security.MessageDigest

/** Online demo is a separate database and JWT realm. Never relax the local fixture guards. */
@Component
@Profile("demo")
class DemoEnvironment(environment: Environment) {
    val accessCode = environment.getRequiredProperty("sequo.demo.access-code")
    val publicUrl = environment.getRequiredProperty("sequo.demo.public-url").trimEnd('/')
    init { require(findings(environment).isEmpty()) { findings(environment).joinToString(" ") } }
    fun accepts(code: String) = MessageDigest.isEqual(accessCode.toByteArray(), code.toByteArray())
    companion object {
        const val CUSTOMER = "demo-v1-customer"
        const val ADMIN = "demo-v1-admin"
        val RIDERS = (1..4).map { "demo-v1-rider-$it" }
        fun findings(e: Environment): List<String> = buildList {
            if (e.activeProfiles.toSet() != setOf("demo")) add("Demo requires its exclusive profile.")
            if (e.getProperty("spring.datasource.url") != "jdbc:postgresql://demo-postgres:5432/sequo_demov1") add("Demo requires its dedicated database.")
            if (e.getProperty("spring.datasource.username") != "demo_app") add("Demo requires its dedicated database account.")
            if (e.getProperty("sequo.auth.jwt.issuer") != "sequo-demo-v1" || e.getProperty("sequo.auth.jwt.audience") != "sequo-demo-mobile") add("Demo requires an isolated token realm.")
            if (e.getProperty("sequo.demo.access-code", "").length < 20) add("A private demo access code is required.")
            if (!e.getProperty("sequo.demo.public-url", "").matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?"))) add("An HTTPS demo origin is required.")
            for (key in listOf("sequo.notifications.outbox-worker.enabled", "sequo.notifications.provider-delivery-worker.enabled", "sequo.admin-auth.email.enabled", "sequo.payments.simulator.enabled", "sequo.admin.development-access.enabled", "sequo.admin.local-fixtures.enabled", "sequo.auth.local-mobile-access.enabled"))
                if (e.getProperty(key, Boolean::class.java, false)) add("Demo must disable $key.")
            for (provider in listOf("yas-togo", "moov-africa")) for (key in listOf("api-base-url", "api-key", "merchant-account-id", "webhook-secret"))
                if (e.getProperty("sequo.wallets.$provider.$key", "").isNotBlank()) add("Demo refuses real provider credentials.")
        }
    }
}
