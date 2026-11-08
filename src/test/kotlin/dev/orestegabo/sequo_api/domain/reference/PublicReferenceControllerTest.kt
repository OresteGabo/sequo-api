package dev.orestegabo.sequo_api.domain.reference

import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import dev.orestegabo.sequo_api.domain.relay.RelayOperatorAccess
import dev.orestegabo.sequo_api.domain.returns.ReturnPersistenceService
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class PublicReferenceControllerTest {
    private val registry = mock(PublicReferenceRegistry::class.java)
    private val access = mock(RelayOperatorAccess::class.java)
    private val returns = mock(ReturnPersistenceService::class.java)
    private val controller = PublicReferenceController(registry, access, returns)
    private fun actor(role: RoleCode) = UsernamePasswordAuthenticationToken("actor", null, listOf(role.toGrantedAuthority()))
    private fun request(reference: String = "R98-765-4Q4", point: String? = "relay-a", legacy: Boolean = false) =
        PublicReferenceController.ResolveRequest(PublicReferenceKind.RETURN, reference, point, legacy)

    @Test fun `missing or non operator sessions cannot use the resolver`() {
        assertEquals(401, controller.resolve(null, request()).statusCode.value())
        for (role in listOf(RoleCode.CUSTOMER, RoleCode.COURIER, RoleCode.MERCHANT_OWNER, RoleCode.SUPPORT_AGENT))
            assertEquals(403, controller.resolve(actor(role), request()).statusCode.value())
        verifyNoInteractions(registry, access, returns)
    }

    @Test fun `admin malformed checksum and wrong kind never reach registry or domain lookup`() {
        val admin = actor(RoleCode.ADMIN)
        for (input in listOf("R98-765-4Q5", "C12-345-6E6", "random", "R98-765-4Q4\n"))
            assertEquals(400, controller.resolve(admin, request(input)).statusCode.value())
        verifyNoInteractions(registry, access, returns)
    }

    @Test fun `valid but unallocated references have no existence claim`() {
        assertEquals(404, controller.resolve(actor(RoleCode.ADMIN), request()).statusCode.value())
        verify(registry).resolve(PublicReferenceKind.RETURN, "R98-765-4Q4")
        verifyNoInteractions(access, returns)
    }

    @Test fun `forged relay and unsupported entity types are denied before lookup`() {
        val relay = actor(RoleCode.RELAY_PARTNER)
        assertEquals(403, controller.resolve(relay, request()).statusCode.value())
        assertEquals(403, controller.resolve(relay, PublicReferenceController.ResolveRequest(PublicReferenceKind.ORDER, "C12-345-6E6", "relay-a")).statusCode.value())
        verifyNoInteractions(registry, returns)
    }

    @Test fun `relay cannot resolve a return belonging to another site`() {
        val relay = actor(RoleCode.RELAY_PARTNER)
        `when`(access.permits(relay, "relay-a")).thenReturn(true)
        `when`(registry.resolve(PublicReferenceKind.RETURN, "R98-765-4Q4")).thenReturn("old-return-id")
        assertEquals(404, controller.resolve(relay, request()).statusCode.value())
        verify(returns).canResolveForRelay("old-return-id", "relay-a")
        verify(registry, never()).reference(PublicReferenceKind.RETURN, "old-return-id")
    }

    @Test fun `authorized relay lookup returns canonical ID without PIN or customer fields`() {
        val relay = actor(RoleCode.RELAY_PARTNER)
        `when`(access.permits(relay, "relay-a")).thenReturn(true)
        `when`(registry.resolve(PublicReferenceKind.RETURN, "r98 765 4q4")).thenReturn("old-return-id")
        `when`(returns.canResolveForRelay("old-return-id", "relay-a")).thenReturn(true)
        `when`(registry.reference(PublicReferenceKind.RETURN, "old-return-id")).thenReturn("R98-765-4Q4")
        val response = controller.resolve(relay, request("r98 765 4q4"))
        assertEquals(200, response.statusCode.value())
        assertEquals(PublicReferenceController.ResolvedReference(PublicReferenceKind.RETURN, "old-return-id", "R98-765-4Q4"), response.body)
        assertEquals("no-store", response.headers.cacheControl)
    }

    @Test fun `legacy mode is explicit scoped and never claims arbitrary admin IDs`() {
        val admin = actor(RoleCode.ADMIN)
        assertEquals(404, controller.resolve(admin, request("legacy-return", legacy = true)).statusCode.value())
        verify(registry).findReference(PublicReferenceKind.RETURN, "legacy-return")
        verify(registry, never()).reference(PublicReferenceKind.RETURN, "legacy-return")
        val relay = actor(RoleCode.RELAY_PARTNER)
        `when`(access.permits(relay, "relay-a")).thenReturn(true)
        `when`(returns.canResolveForRelay("legacy-return", "relay-a")).thenReturn(true)
        `when`(registry.reference(PublicReferenceKind.RETURN, "legacy-return")).thenReturn("R98-765-4Q4")
        assertEquals(200, controller.resolve(relay, request("legacy-return", legacy = true)).statusCode.value())
    }
}
