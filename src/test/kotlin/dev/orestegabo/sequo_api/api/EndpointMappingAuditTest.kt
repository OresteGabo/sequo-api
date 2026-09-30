package dev.orestegabo.sequo_api.api

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
class EndpointMappingAuditTest @Autowired constructor(
    @Qualifier("requestMappingHandlerMapping")
    private val handlerMapping: RequestMappingHandlerMapping,
) {
    @Test
    fun activeHttpMappingsHaveNoDuplicateMethodAndPathPairs() {
        val mappings = handlerMapping.handlerMethods.keys.flatMap { mapping ->
            val methods = mapping.methodsCondition.methods.ifEmpty { setOf(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE) }
            methods.flatMap { method -> mapping.patternValues.filter { it != "/error" }.map { pattern -> "$method $pattern" } }
        }
        val duplicates = mappings.groupingBy { it }.eachCount().filterValues { it > 1 }

        assertTrue(duplicates.isEmpty(), "Duplicate endpoint mappings: $duplicates")
    }

    @Test
    fun retiredAliasesAndLegacyProductRouteAreAbsent() {
        val paths = handlerMapping.handlerMethods.keys.flatMap { it.patternValues }

        assertFalse(paths.contains("/api/customer/orders"))
        assertFalse(paths.contains("/api/customer/notifications"))
        assertFalse(paths.contains("/api/v1/products/{id}/related"))
        assertTrue(paths.contains("/api/catalog/products/{productId}/related"))
    }
}
