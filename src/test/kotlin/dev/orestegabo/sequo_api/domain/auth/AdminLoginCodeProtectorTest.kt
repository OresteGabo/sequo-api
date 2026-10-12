package dev.orestegabo.sequo_api.domain.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdminLoginCodeProtectorTest {
    private val protector = AesGcmAdminLoginCodeProtector("test_admin_login_code_secret_2026_value")

    @Test
    fun protectsAndRevealsCodeWithoutPlaintextStorage() {
        val protected = protector.protect("ABC-DEF")

        assertTrue(protected.startsWith("v1:"))
        assertNotEquals("ABC-DEF", protected)
        assertEquals("ABC-DEF", protector.reveal(protected))
    }
}
