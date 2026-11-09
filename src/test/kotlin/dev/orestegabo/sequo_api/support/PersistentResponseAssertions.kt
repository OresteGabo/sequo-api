package dev.orestegabo.sequo_api.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

/** H2 and PostgreSQL timestamp(6) columns round Instants to microseconds. */
fun databaseInstant(value: Instant): Instant = value.plusNanos(500).truncatedTo(ChronoUnit.MICROS)

/** Compare the persisted payload, excluding the explicitly per-request server clock. */
fun persistentJson(value: JsonNode): JsonNode = when {
    value.isObject -> ObjectMapper().createObjectNode().also { result ->
        value.fields().forEachRemaining { (key, child) ->
            if (key != "serverNow") result.set<JsonNode>(key, persistentJson(child))
        }
    }
    value.isArray -> ObjectMapper().createArrayNode().also { result -> value.forEach { result.add(persistentJson(it)) } }
    value.isTextual -> runCatching { TextNode(databaseInstant(Instant.parse(value.asText())).toString()) }.getOrDefault(value)
    else -> value
}

fun assertPersistentJsonEquals(expected: JsonNode, actual: JsonNode) = assertEquals(persistentJson(expected), persistentJson(actual))
