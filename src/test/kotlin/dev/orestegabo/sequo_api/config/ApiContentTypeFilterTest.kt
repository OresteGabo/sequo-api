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

    @Test
    fun multipartIsLimitedToExactMerchantAndAdminCameraUploadPaths() {
        val paths = mapOf(
            "/api/merchant/catalog/products/product-1/photos/camera" to 200,
            "/api/admin/web/catalog/products/product-1/media/photos/camera" to 200,
            "/api/admin/web/catalog/products/product-1/media/photos/generic" to 200,
            "/api/admin/web/catalog/products/product-1/media/photos" to 415,
            "/api/admin/web/catalog/products/product-1/media/photos/camera/extra" to 415,
            "/api/orders/process" to 415,
        )
        paths.forEach { (path, status) ->
            val request = MockHttpServletRequest("POST", path).apply {
                contentType = "multipart/form-data; boundary=fixture"
                setContent("fixture".toByteArray())
            }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, chain)
            assertEquals(status, response.status)
        }
        Mockito.verify(chain, Mockito.times(3)).doFilter(Mockito.any(), Mockito.any())
    }
}
