package dev.orestegabo.sequo_api.domain.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface AdminEmailTransport {
    fun send(apiKey: String, payload: String, idempotencyKey: String): Boolean
}

@Component
class ResendAdminEmailTransport internal constructor(
    private val client: HttpClient,
    private val endpoint: URI,
) : AdminEmailTransport {
    @org.springframework.beans.factory.annotation.Autowired
    constructor() : this(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
        URI.create("https://api.resend.com/emails"),
    )

    override fun send(apiKey: String, payload: String, idempotencyKey: String): Boolean {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return false
        return runCatching {
            val id = ObjectMapper().readTree(response.body()).path("id")
            id.isTextual && id.asText().isNotBlank()
        }.getOrDefault(false)
    }
}
