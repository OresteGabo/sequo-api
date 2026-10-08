package dev.orestegabo.sequo_api.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiInputPolicyTest {
    @Test
    fun identityEmailCanonicalizesGmailDotsPlusTagsAndGooglemailDomain() {
        assertEquals(
            "gabooreste@gmail.com",
            ApiInputPolicy.normalizedIdentityEmail("Gabo.Oreste+2@Gmail.com"),
        )
        assertEquals(
            "gabooreste@gmail.com",
            ApiInputPolicy.normalizedIdentityEmail("g.a.b.o.o.r.e.s.t.e@googlemail.com"),
        )
    }

    @Test
    fun identityEmailDoesNotCollapseProviderSpecificAliasesForOtherDomains() {
        assertEquals(
            "gabo.oreste+2@example.com",
            ApiInputPolicy.normalizedIdentityEmail("Gabo.Oreste+2@Example.com"),
        )
    }

    @Test
    fun deliveryEmailKeepsGmailAliasText() {
        assertEquals(
            "gabo.oreste+receipts@gmail.com",
            ApiInputPolicy.normalizedEmail("Gabo.Oreste+Receipts@Gmail.com"),
        )
    }
}
