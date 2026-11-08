package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo.reference.PublicReferenceKind.*
import dev.orestegabo.sequo_api.domain.returns.ReturnStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AdminPublicReferenceProjectionTest {
    @Test
    fun `only declared identities gain references while raw IDs remain exact`() {
        val row = AdminReturnDto("old:return:42", "order-legacy", ReturnStatus.Requested, 2000, "site-1", Instant.EPOCH)
        val calls = mutableListOf<Pair<PublicReferenceKind, String>>()
        val result = projectAdminReferences(listOf(row)) { kind, id -> calls += kind to id; "${kind.name}:$id" } as List<*>
        val dto = result.single() as Map<*, *>
        assertEquals("old:return:42", dto["id"])
        assertEquals("order-legacy", dto["orderId"])
        assertEquals("RETURN:old:return:42", dto["publicReference"])
        assertEquals("ORDER:order-legacy", dto["orderReference"])
        assertEquals("RELAY:site-1", dto["relayReference"])
        assertEquals(listOf(RETURN to row.id, ORDER to row.orderId, RELAY to "site-1"), calls)
    }

    @Test
    fun `nested known wrappers project actors without minting audit or request identities`() {
        val event = AdminPartyChangeDto("audit-key", "SET", "actor-1", "old", "new", "Reason", "retry-key", Instant.EPOCH)
        val result = projectAdminReferences(AdminPartyMutationDto(mapOf("note" to "unchanged"), event)) { kind, id -> "$kind:$id" } as Map<*, *>
        val operation = result["operation"] as Map<*, *>
        assertEquals("USER:actor-1", operation["actorReference"])
        assertEquals("audit-key", operation["id"])
        assertEquals("retry-key", operation["idempotencyKey"])
        assertNull(operation["publicReference"])
        val request = AdminRoleRequest("Reason", Instant.EPOCH, "retry-key")
        assertSame(request, projectAdminReferences(request) { _, _ -> error("Requests must not allocate references") })
        val error = mapOf("code" to "denied", "userId" to "guessed-id", "accessToken" to "not-a-reference")
        assertEquals(error, projectAdminReferences(error) { _, _ -> error("Errors must not allocate references") })
    }

    @Test
    fun `missing relationships remain absent and unknown types are not reflected`() {
        val row = AdminReturnDto("return-1", "order-1", ReturnStatus.Requested, 0, null, Instant.EPOCH)
        val dto = projectAdminReferences(row) { kind, id -> "$kind:$id" } as Map<*, *>
        assertNull(dto["relayReference"])
        data class Unknown(val id: String, val userId: String)
        val unknown = Unknown("private-record", "user-1")
        assertSame(unknown, projectAdminReferences(unknown) { _, _ -> error("Unknown DTOs are not inspected") })
    }
}
