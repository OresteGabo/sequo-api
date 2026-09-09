package dev.orestegabo.sequo_api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.web.filter.OncePerRequestFilter

class ApiContentTypeFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method in BODY_METHODS && hasBody(request) && !isJson(request.contentType)) {
            response.status = HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE
            response.contentType = "application/json"
            response.writer.write("{\"code\":\"unsupported_media_type\",\"message\":\"Request bodies must use application/json.\"}")
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun hasBody(request: HttpServletRequest): Boolean =
        request.contentLengthLong > 0 || !request.getHeader("Transfer-Encoding").isNullOrBlank()

    private fun isJson(contentType: String?): Boolean {
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return mediaType == "application/json" ||
            (mediaType.startsWith("application/") && mediaType.endsWith("+json"))
    }

    private companion object {
        val BODY_METHODS = setOf(
            HttpMethod.POST.name(),
            HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(),
            HttpMethod.DELETE.name(),
        )
    }
}
