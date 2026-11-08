package dev.orestegabo.sequo.reference

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.*
import org.junit.jupiter.api.Test

class HumanReferenceTest {
    @Test fun `v1 vectors match every record kind`() {
        val vectors = ObjectMapper().readTree(Path.of("contracts/human-reference-v1.json").toFile())["vectors"]
        for (vector in vectors) {
            val kind = PublicReferenceKind.valueOf(vector["kind"].asText())
            val reference = vector["reference"].asText()
            assertEquals(reference, HumanReference.encode(kind, vector["payload"].asText()))
            assertEquals(reference, HumanReference.normalize(reference))
            assertTrue(HumanReference.isValid(reference, kind))
            assertFalse(HumanReference.isValid(reference, PublicReferenceKind.entries.first { it != kind }))
        }
    }

    @Test fun `every single symbol substitution and distinct adjacent swap is rejected`() {
        val random = Random(481516)
        for (kind in PublicReferenceKind.entries) repeat(100) {
            val reference = HumanReference.encode(kind, (1..6).map { HumanReference.ALPHABET[random.nextInt(32)] }.joinToString(""))
                .replace("-", "")
            for (index in reference.indices) for (replacement in HumanReference.ALPHABET) {
                if (replacement != reference[index]) assertNull(HumanReference.normalize(reference.replaceRange(index, index + 1, replacement.toString())))
            }
            for (index in 0 until reference.lastIndex) {
                if (reference[index] != reference[index + 1]) assertNull(HumanReference.normalize(
                    reference.replaceRange(index, index + 2, "${reference[index + 1]}${reference[index]}")))
            }
        }
    }

    @Test fun `input normalization accepts only documented aliases and separators`() {
        assertEquals("C12-345-6E6", HumanReference.normalize(" cI2 345-6e6 "))
        assertEquals("H00-000-0TP", HumanReference.normalize("hooooootp"))
        for (input in listOf("", "C123456E", "C123456E66", "C12-345-6E7", "U00-000-0TP",
            "C12\n3456E6", "C12\t3456E6", "Ｃ123456E6", "C12_345_6E6", "\u00a0C123456E6", "X".repeat(65))) {
            assertNull(HumanReference.normalize(input), input)
        }
        assertEquals(PublicReferenceKind.RETURN, HumanReference.kind("r98-765-4q4"))
        assertNull(HumanReference.kind("not-a-reference"))
        assertFailsWith<IllegalArgumentException> { HumanReference.encode(PublicReferenceKind.ORDER, "ABC") }
        assertFailsWith<IllegalArgumentException> { HumanReference.encode(PublicReferenceKind.ORDER, "ABCDEO") }
    }

    @Test fun `reference is not a claim of existence`() {
        // The UI may accept this structurally correct but entirely unallocated reference.
        assertTrue(HumanReference.isValid("C12-345-6E6"))
    }
}
