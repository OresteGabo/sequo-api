package dev.orestegabo.sequo_api.api

import dev.orestegabo.sequo_api.domain.auth.AccountLinkRequiredException
import dev.orestegabo.sequo_api.domain.auth.AuthProvider
import dev.orestegabo.sequo_api.domain.auth.AuthProviderRequiredException
import dev.orestegabo.sequo_api.domain.auth.EmailAlreadyRegisteredException
import dev.orestegabo.sequo_api.domain.auth.InvalidGoogleTokenException
import dev.orestegabo.sequo_api.domain.auth.RateLimitExceededException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class ApiErrorResponse(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArgument(error: IllegalArgumentException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.BAD_REQUEST, "invalid_request", error.message ?: "Invalid request.")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformedBody(): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.BAD_REQUEST, "malformed_json", "The request body is not valid JSON for this endpoint.")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidParameter(error: MethodArgumentTypeMismatchException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.BAD_REQUEST, "invalid_parameter", "Parameter '${error.name}' has an invalid value.")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(error: MissingServletRequestParameterException): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.BAD_REQUEST, "missing_parameter", "Parameter '${error.parameterName}' is required.")

    @ExceptionHandler(RateLimitExceededException::class)
    fun rateLimited(error: RateLimitExceededException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, error.retryAfterSeconds.toString())
            .body(
                ApiErrorResponse(
                    code = "rate_limited",
                    message = "Too many attempts. Please wait before trying again.",
                    details = mapOf("retryAfterSeconds" to error.retryAfterSeconds),
                )
            )

    @ExceptionHandler(AuthProviderRequiredException::class)
    fun authProviderRequired(error: AuthProviderRequiredException): ResponseEntity<ApiErrorResponse> =
        errorResponse(
            HttpStatus.CONFLICT,
            "auth_provider_required",
            "This account uses ${providerLabel(error.requiredProvider)} sign-in. Continue with ${providerLabel(error.requiredProvider)} to access it.",
            mapOf("requiredProvider" to error.requiredProvider),
        )

    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    fun emailAlreadyRegistered(): ResponseEntity<ApiErrorResponse> =
        errorResponse(HttpStatus.CONFLICT, "email_already_registered", "An account already exists for this email.")

    @ExceptionHandler(AccountLinkRequiredException::class)
    fun accountLinkRequired(error: AccountLinkRequiredException): ResponseEntity<ApiErrorResponse> =
        errorResponse(
            HttpStatus.CONFLICT,
            "account_link_required",
            "This email already belongs to an existing ${providerLabel(error.existingProvider)} account. Sign in with that method before linking ${providerLabel(error.attemptedProvider)}.",
            mapOf(
                "requiredProvider" to error.existingProvider,
                "attemptedProvider" to error.attemptedProvider,
            ),
        )

    @ExceptionHandler(InvalidGoogleTokenException::class)
    fun invalidGoogleToken(): ResponseEntity<ApiErrorResponse> =
        errorResponse(
            HttpStatus.UNAUTHORIZED,
            "invalid_google_token",
            "Google sign-in could not be completed with the provided token.",
        )

    private fun errorResponse(
        status: HttpStatus,
        code: String,
        message: String,
        details: Map<String, Any?> = emptyMap(),
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(status).body(ApiErrorResponse(code, message, details))

    private fun providerLabel(provider: AuthProvider): String =
        when (provider) {
            AuthProvider.EMAIL -> "email/password"
            AuthProvider.GOOGLE -> "Google"
            AuthProvider.FACEBOOK -> "Facebook"
            AuthProvider.APPLE -> "Apple"
            AuthProvider.PASSKEY -> "Passkey"
        }
}
