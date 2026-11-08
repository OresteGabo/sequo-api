package dev.orestegabo.sequo_api.domain.reference

import dev.orestegabo.sequo_api.domain.auth.RoleCode
import dev.orestegabo.sequo_api.domain.auth.toGrantedAuthority
import dev.orestegabo.sequo_api.domain.delivery.OperatorHistoryEntry
import dev.orestegabo.sequo_api.domain.platform.CooperativeApprovalStatus
import dev.orestegabo.sequo_api.domain.platform.CooperativeController
import dev.orestegabo.sequo_api.domain.platform.CooperativeDto
import dev.orestegabo.sequo_api.domain.platform.CooperativeMemberDto
import dev.orestegabo.sequo_api.domain.platform.CooperativeMemberRole
import dev.orestegabo.sequo_api.domain.platform.PlatformCommerceService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class CooperativeReferencePrivacyTest {
    private val approved = CooperativeDto(
        id = "cooperative-approved", code = "existing-cooperative-code", name = "Approved market",
        city = "Lomé", area = null, status = CooperativeApprovalStatus.APPROVED,
        members = listOf(CooperativeMemberDto("membership", "merchant", CooperativeMemberRole.OWNER,
            merchantReference = "VAB-CDE-FVD")),
        reviewedByUserId = "private-reviewer", reviewedAt = Instant.parse("2026-11-09T10:00:00Z"),
        reviewReason = "Private review reason",
        history = listOf(OperatorHistoryEntry("APPROVED", "private-reviewer", "Private actor metadata",
            Instant.parse("2026-11-09T10:00:00Z"), actorReference = "A12-345-6BJ")),
        publicReference = "Q12-345-6TC", reviewerReference = "A12-345-6BJ",
    )

    @Test
    fun nonStaffPublicListKeepsApprovedBusinessReferencesButRedactsReviewerAliasesAndHistory() {
        val service = Mockito.mock(PlatformCommerceService::class.java)
        Mockito.`when`(service.cooperatives()).thenReturn(listOf(approved,
            approved.copy(id = "cooperative-pending", status = CooperativeApprovalStatus.PENDING_REVIEW)))
        val controller = CooperativeController(service)
        for (role in listOf(RoleCode.CUSTOMER, RoleCode.MERCHANT_OWNER, RoleCode.RELAY_PARTNER)) {
            val response = controller.list(auth(role), request("/api/cooperatives"))
            assertEquals(200, response.statusCode.value())
            val actual = (response.body as List<*>).single() as CooperativeDto
            assertEquals(approved.id, actual.id)
            assertEquals("Q12-345-6TC", actual.publicReference)
            assertEquals("VAB-CDE-FVD", actual.members.single().merchantReference)
            assertNull(actual.reviewedByUserId)
            assertNull(actual.reviewerReference, "A display alias must not reveal the redacted reviewer.")
            assertNull(actual.reviewedAt)
            assertNull(actual.reviewReason)
            assertTrue(actual.history.isEmpty(), "Actor reference aliases must not escape through history.")
            assertEquals(403, controller.list(auth(role), request("/api/admin/cooperatives")).statusCode.value())
        }
        assertEquals(401, controller.list(null, request("/api/cooperatives")).statusCode.value())
    }

    @Test
    fun administratorViewsRetainAuthorizedReviewerAndActorReferences() {
        val service = Mockito.mock(PlatformCommerceService::class.java)
        val pending = approved.copy(id = "cooperative-pending", status = CooperativeApprovalStatus.PENDING_REVIEW)
        Mockito.`when`(service.cooperatives()).thenReturn(listOf(approved, pending))
        val controller = CooperativeController(service)
        for (role in listOf(RoleCode.ADMIN, RoleCode.SUPER_ADMIN)) {
            for (path in listOf("/api/cooperatives", "/api/admin/cooperatives")) {
                val response = controller.list(auth(role), request(path))
                assertEquals(200, response.statusCode.value())
                assertEquals(listOf(approved, pending), response.body)
            }
        }
    }

    private fun auth(role: RoleCode) = UsernamePasswordAuthenticationToken("fixture-actor", null,
        listOf(role.toGrantedAuthority()))

    private fun request(path: String) = MockHttpServletRequest("GET", path)
}
