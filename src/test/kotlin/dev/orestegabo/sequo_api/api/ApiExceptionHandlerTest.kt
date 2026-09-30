package dev.orestegabo.sequo_api.api

import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.AuthProviderRequiredException
import dev.orestegabo.sequo_api.domain.auth.RateLimitExceededException
import org.springframework.web.bind.MissingServletRequestParameterException
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiExceptionHandlerTest {
    private val handler = ApiExceptionHandler()

    @Test
    fun illegalArgumentsUseTheSharedErrorContract() {
        val response = handler.illegalArgument(IllegalArgumentException("limit is invalid"))

        assertEquals(400, response.statusCode.value())
        assertEquals("invalid_request", response.body?.code)
        assertEquals("limit is invalid", response.body?.message)
    }

    @Test
    fun rateLimitsExposeRetryAfterThroughTheSharedContract() {
        val response = handler.rateLimited(RateLimitExceededException(12))

        assertEquals(429, response.statusCode.value())
        assertEquals("12", response.headers.getFirst("Retry-After"))
        assertEquals("rate_limited", response.body?.code)
        assertEquals(12L, response.body?.details?.get("retryAfterSeconds"))
    }

    @Test
    fun providerConflictsExposeAStableMachineReadableCode() {
        val response = handler.authProviderRequired(AuthProviderRequiredException(AuthProvider.GOOGLE))

        assertEquals(409, response.statusCode.value())
        assertEquals("auth_provider_required", response.body?.code)
        assertEquals(AuthProvider.GOOGLE, response.body?.details?.get("requiredProvider"))
    }

    @Test
    fun invalidGoogleTokensDoNotExposeProviderRejectionReason() {
        val response = handler.invalidGoogleToken()

        assertEquals(401, response.statusCode.value())
        assertEquals("invalid_google_token", response.body?.code)
        assertEquals("Google sign-in could not be completed with the provided token.", response.body?.message)
        assertEquals(emptyMap(), response.body?.details)
    }

    @Test
    fun malformedAndMissingParametersUseBadRequestResponses() {
        assertEquals("malformed_json", handler.malformedBody().body?.code)
        assertEquals(
            "missing_parameter",
            handler.missingParameter(MissingServletRequestParameterException("limit", "int")).body?.code,
        )
    }
}
