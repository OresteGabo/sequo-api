package dev.orestegabo.sequo_api.domain.localfixture

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant

/** Explicit stored schema; no dependency on application-wide Jackson date/Kotlin modules. */
internal object LocalFixtureManifestCodec {
    private val mapper = ObjectMapper()
    fun encode(m: LocalFixtureManifest): String = mapper.writeValueAsString(mapOf(
        "environment" to m.environment, "namespace" to m.namespace, "version" to m.version,
        "seededAt" to m.seededAt?.toString(), "note" to m.note,
        "accounts" to m.accounts.map { mapOf("id" to it.id, "name" to it.name, "email" to it.email, "roles" to it.roles, "purpose" to it.purpose) },
        "scenarios" to m.scenarios.map { mapOf("id" to it.id, "title" to it.title, "summary" to it.summary, "category" to it.category,
            "resourceIds" to it.resourceIds, "nextActions" to it.nextActions, "credentials" to it.credentials) },
    ))
    fun decode(raw: String): LocalFixtureManifest {
        val n = mapper.readTree(raw)
        require(n.path("environment").asText() == "LOCAL_FIXTURE" && n.path("namespace").asText() == "local-ops-v1" && n.path("version").asInt() == 1)
        return LocalFixtureManifest(seededAt = n.get("seededAt")?.takeUnless { it.isNull }?.asText()?.let(Instant::parse),
            accounts = n.path("accounts").map { LocalFixtureAccount(it.path("id").asText(), it.path("name").asText(), it.path("email").asText(),
                it.path("roles").map(JsonNode::asText), it.path("purpose").asText()) },
            scenarios = n.path("scenarios").map { LocalFixtureScenario(it.path("id").asText(), it.path("title").asText(), it.path("summary").asText(),
                it.path("category").asText(), strings(it.path("resourceIds")), it.path("nextActions").map(JsonNode::asText), strings(it.path("credentials"))) },
            note = n.path("note").asText())
    }
    private fun strings(n: JsonNode): Map<String, String> = n.fields().asSequence().associate { it.key to it.value.asText() }
}
