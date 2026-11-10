package dev.orestegabo.sequo_api.domain.catalog

import kotlin.test.*

class ProductReferencesTest {
    private val milk = ProductReferenceDto("milk", "Lait", "grocery", listOf("milk"))
    private val coconut = ProductReferenceDto("coconut", "Lait de coco", "grocery", listOf("coconut milk"))
    @Test fun aliasesAccentsAndWordPrefixesAreRankedWithoutFuzzyIdentityMerging() {
        val records = listOf(coconut, milk, ProductReferenceDto("coffee", "Café", "grocery", listOf("coffee")))
        assertEquals(listOf("milk", "coconut"), suggestProductReferences(records, "lait", 6).map { it.id })
        assertEquals(listOf("milk", "coconut"), suggestProductReferences(records, "MILK", 6).map { it.id })
        assertEquals("coffee", suggestProductReferences(records, "cafe\u0301", 6).single().id)
        assertTrue(suggestProductReferences(records, "l", 6).isEmpty())
        assertTrue(suggestProductReferences(records, "ait", 6).isEmpty())
        assertEquals(1, suggestProductReferences(records, "lait", 1).size)
    }
}
