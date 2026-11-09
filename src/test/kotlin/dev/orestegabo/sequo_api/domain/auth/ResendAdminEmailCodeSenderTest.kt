package dev.orestegabo.sequo_api.domain.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResendAdminEmailCodeSenderTest {
    private val key = "re_test_only_not_a_real_provider_key"
    private val expiry = Instant.parse("2026-11-09T15:00:00Z")

    @Test
    fun disabledSenderMakesNoExternalRequestAndEnabledConfigurationRejectsMissingSecrets() {
        var calls = 0
        val transport = AdminEmailTransport { _, _, _ -> calls++; true }
        val disabled = ResendAdminEmailCodeSender(false, "", "", transport)
        assertFailsWith<AdminEmailDeliveryUnavailableException> { disabled.checkAvailability() }
        assertFailsWith<AdminEmailDeliveryUnavailableException> { disabled.send("admin@example.test", "ABC-DEF", expiry) }
        assertEquals(0, calls)
        assertFailsWith<IllegalArgumentException> { ResendAdminEmailCodeSender(true, "", "admin@example.test", transport) }
        assertFailsWith<IllegalArgumentException> { ResendAdminEmailCodeSender(true, key, "invalid", transport) }
        assertFailsWith<IllegalArgumentException> { ResendAdminEmailCodeSender(true, key + "\r\n", "admin@example.test", transport) }
    }

    @Test
    fun validEmailHasExactRecipientCodeExpiryAndStableOpaqueIdempotencyKey() {
        val bodies = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val sender = ResendAdminEmailCodeSender(true, key, "signin@example.test", AdminEmailTransport { credential, payload, id ->
            assertEquals(key, credential); bodies += payload; ids += id; true
        })
        sender.send("ADMIN@example.test", "ABC-DEF", expiry)
        sender.send("ADMIN@example.test", "ABC-DEF", expiry)
        sender.send("ADMIN@example.test", "GHJ-KMN", expiry)
        val body = ObjectMapper().readTree(bodies.first())
        assertEquals("SEQUO Admin <signin@example.test>", body["from"].asText())
        assertEquals("admin@example.test", body["to"][0].asText())
        assertTrue(body["text"].asText().contains("ABC-DEF"))
        assertTrue(body["text"].asText().contains(expiry.toString()))
        val html = body["html"].asText()
        assertTrue(html.contains("ABC-DEF"))
        assertTrue(html.contains("2026-11-09 · 15:00 UTC"))
        assertTrue(html.contains("src=\"cid:sequo-logo\""))
        assertTrue(html.contains("alt=\"SEQUO\""))
        assertTrue(html.contains("lang=\"en\""))
        assertFalse(html.contains("{{"))
        assertFalse(html.contains("<script", ignoreCase = true))
        assertFalse(html.contains("src=\"http"))
        assertFalse(html.contains(key))
        val attachment = body["attachments"].single()
        assertEquals("sequo-logo", attachment["content_id"].asText())
        assertEquals("image/png", attachment["content_type"].asText())
        val png = Base64.getDecoder().decode(attachment["content"].asText())
        assertEquals(listOf(137, 80, 78, 71, 13, 10, 26, 10), png.take(8).map { it.toInt() and 255 })
        assertEquals(ids[0], ids[1]); assertNotEquals(ids[1], ids[2])
        assertFalse(ids[0].contains("ABC-DEF")); assertFalse(ids[0].contains("admin@"))
    }

    @Test
    fun transportRejectionAndExceptionsCannotLeakCredentialsOrProviderBodies() {
        for (transport in listOf(AdminEmailTransport { _, _, _ -> false }, AdminEmailTransport { _, _, _ -> throw IllegalStateException("secret=$key code=ABC-DEF") })) {
            val sender = ResendAdminEmailCodeSender(true, key, "signin@example.test", transport)
            val error = assertFailsWith<AdminEmailDeliveryException> { sender.send("admin@example.test", "ABC-DEF", expiry) }
            assertEquals("Admin email delivery failed.", error.message)
            assertNull(error.cause)
        }
    }

    @Test
    fun invalidRecipientOrCodeCannotReachProvider() {
        var calls = 0
        val sender = ResendAdminEmailCodeSender(true, key, "signin@example.test", AdminEmailTransport { _, _, _ -> calls++; true })
        assertFailsWith<IllegalArgumentException> { sender.send("bad\r\nrecipient", "ABC-DEF", expiry) }
        assertFailsWith<IllegalArgumentException> { sender.send("admin@example.test", "ABC-DEF\nextra", expiry) }
        assertEquals(0, calls)
    }

    @Test
    fun httpTransportRequiresProviderAcknowledgementAndDoesNotFollowRedirects() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 200
        var responseBody = "{\"id\":\"test-message-id\"}"
        var forwardedRequests = 0
        server.createContext("/emails") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer $key", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("test-idempotency", exchange.requestHeaders.getFirst("Idempotency-Key"))
            assertEquals("{}", exchange.requestBody.bufferedReader().readText())
            if (status == 302) exchange.responseHeaders.add("Location", "/must-not-follow")
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/must-not-follow") { exchange -> forwardedRequests++; exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            val transport = ResendAdminEmailTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), URI.create("http://127.0.0.1:${server.address.port}/emails"))
            assertTrue(transport.send(key, "{}", "test-idempotency"))
            responseBody = "{}"; assertFalse(transport.send(key, "{}", "test-idempotency"))
            status = 422; assertFalse(transport.send(key, "{}", "test-idempotency"))
            status = 302; assertFalse(transport.send(key, "{}", "test-idempotency"))
            assertEquals(0, forwardedRequests)
        } finally { server.stop(0) }
    }
}
