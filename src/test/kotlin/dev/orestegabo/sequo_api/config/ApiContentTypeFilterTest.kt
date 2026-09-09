package dev.orestegabo.sequo_api.config

import jakarta.servlet.FilterChain
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ApiContentTypeFilterTest {

    private val filter = ApiContentTypeFilter()
    private val chain = Mockito.mock(FilterChain::class.java)

    @Test
    fun rejectsNonJsonBody() {
        val request = MockHttpServletRequest("POST", "/api/orders/process").apply {
            contentType = "text/plain"
            setContent("not-json".toByteArray())
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertEquals(415, response.status)
        assertTrue(response.contentAsString.contains("unsupported_media_type"))
        Mockito.verifyNoInteractions(chain)
    }

    @Test
    fun acceptsJsonAndVendorJsonBodies() {
        listOf("application/json", "application/vnd.sequo+json; charset=utf-8").forEach { contentType ->
            val request = MockHttpServletRequest("POST", "/api/orders/process").apply {
                this.contentType = contentType
                setContent("{}".toByteArray())
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, chain)
        }

        Mockito.verify(chain, Mockito.times(2)).doFilter(Mockito.any(), Mockito.any())
    }

    @Test
    fun doesNotRequireContentTypeForBodylessPost() {
        val request = MockHttpServletRequest("POST", "/api/delivery/missions/dispatch-ready")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        Mockito.verify(chain).doFilter(Mockito.any(), Mockito.any())
    }
}
