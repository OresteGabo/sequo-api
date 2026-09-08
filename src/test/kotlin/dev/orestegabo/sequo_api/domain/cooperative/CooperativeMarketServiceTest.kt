package dev.orestegabo.sequo_api.domain.cooperative

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CooperativeMarketServiceTest {

    private val service = CooperativeMarketService()
    private val now = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun merchantCanRequestCooperativeCreation() {
        val result = service.request(createRequestCommand())

        assertTrue(result is CooperativeResult.RequestAccepted)
        assertEquals(CooperativeRequestStatus.Pending, result.request.status)
        assertEquals(CooperativeRequestType.CreateCooperative, result.request.type)
        assertEquals("Marche de Mulhouse", result.request.requestedName)
        assertEquals("merchant-1", result.request.merchantId)
    }

    @Test
    fun adminApprovesCreationAndFirstMerchantBecomesActiveMember() {
        val request = pendingCreateRequest()

        val result = service.approveCreation(
            CooperativeApprovalCommand(
                request = request,
                adminId = "admin-1",
                reviewedAt = now.plusSeconds(60),
                cooperativeId = "coop-1",
                cooperativeCode = "MULHOUSE",
            )
        )

        assertTrue(result is CooperativeResult.MarketCreated)
        assertEquals(CooperativeRequestStatus.Approved, result.reviewedRequest.status)
        assertEquals("admin-1", result.reviewedRequest.reviewedByAdminId)
        assertEquals(CooperativeStatus.Active, result.cooperative.status)
        assertEquals("Marche de Mulhouse", result.cooperative.name)
        assertEquals("merchant-1", result.cooperative.members.single().merchantId)
        assertEquals(CooperativeMemberStatus.Active, result.cooperative.members.single().status)
    }

    @Test
    fun adminRejectsRequestWithReason() {
        val request = pendingCreateRequest()

        val result = service.rejectRequest(
            CooperativeRejectionCommand(
                request = request,
                adminId = "admin-1",
                reviewedAt = now.plusSeconds(60),
                reason = "Missing market documents.",
            )
        )

        assertTrue(result is CooperativeResult.RequestRejected)
        assertEquals(CooperativeRequestStatus.Rejected, result.request.status)
        assertEquals("Missing market documents.", result.request.reviewReason)
    }

    @Test
    fun merchantCanRequestMembershipAndAdminCanApproveIt() {
        val cooperative = activeCooperative()
        val request = service.request(
            CooperativeRequestCommand(
                requestId = "request-2",
                requestCode = "COOP-REQ-2",
                requestedByUserId = "seller-user-2",
                merchantId = "merchant-2",
                type = CooperativeRequestType.JoinCooperative,
                cooperativeId = "coop-1",
                requestedAt = now,
            )
        ) as CooperativeResult.RequestAccepted

        val approved = service.approveMembership(
            request = request.request,
            cooperative = cooperative,
            adminId = "admin-1",
            reviewedAt = now.plusSeconds(60),
        )

        assertTrue(approved is CooperativeResult.MembershipApproved)
        assertEquals(CooperativeRequestStatus.Approved, approved.reviewedRequest.status)
        assertEquals(setOf("merchant-1", "merchant-2"), approved.cooperative.members.map { it.merchantId }.toSet())
    }

    @Test
    fun duplicateMemberIsRejected() {
        val result = service.addMember(
            CooperativeMemberCommand(
                cooperative = activeCooperative(),
                merchantId = "merchant-1",
                joinedAt = now.plusSeconds(60),
            )
        )

        assertTrue(result is CooperativeResult.Rejected)
        assertEquals("member_already_exists", result.rejection.code)
    }

    @Test
    fun storefrontPreservesItemMerchantOwnership() {
        val cooperative = activeCooperative(
            members = listOf(
                CooperativeMember("merchant-1", CooperativeMemberStatus.Active, now),
                CooperativeMember("merchant-2", CooperativeMemberStatus.Active, now),
            )
        )

        val storefront = service.buildStorefront(
            cooperative = cooperative,
            items = listOf(
                CooperativeItem(
                    productId = "rice-1",
                    merchantId = "merchant-1",
                    productName = "Rice",
                    priceCfa = 1_500,
                ),
                CooperativeItem(
                    productId = "oil-1",
                    merchantId = "merchant-2",
                    productName = "Oil",
                    priceCfa = 2_000,
                ),
            )
        )

        assertEquals("Marche de Mulhouse package", storefront.customerFacingPackageLabel)
        assertEquals(setOf("merchant-1", "merchant-2"), storefront.memberMerchantIds)
        assertTrue(storefront.preservesMerchantOwnership)
        assertEquals("merchant-1", storefront.items.first().merchantId)
        assertEquals("merchant-2", storefront.items.last().merchantId)
    }

    @Test
    fun storefrontRejectsItemsFromNonMembers() {
        assertFailsWith<IllegalArgumentException> {
            service.buildStorefront(
                cooperative = activeCooperative(),
                items = listOf(
                    CooperativeItem(
                        productId = "rice-1",
                        merchantId = "merchant-2",
                        productName = "Rice",
                        priceCfa = 1_500,
                    )
                )
            )
        }
    }

    @Test
    fun createRequestRequiresNameAndCity() {
        val result = service.request(
            createRequestCommand(
                requestedName = "",
                city = "Mulhouse",
            )
        )

        assertTrue(result is CooperativeResult.Rejected)
        assertEquals("missing_requested_name", result.rejection.code)
    }

    private fun pendingCreateRequest(): CooperativeRequest =
        (service.request(createRequestCommand()) as CooperativeResult.RequestAccepted).request

    private fun createRequestCommand(
        requestedName: String = "Marche de Mulhouse",
        city: String = "Mulhouse",
    ): CooperativeRequestCommand =
        CooperativeRequestCommand(
            requestId = "request-1",
            requestCode = "COOP-REQ-1",
            requestedByUserId = "seller-user-1",
            merchantId = "merchant-1",
            type = CooperativeRequestType.CreateCooperative,
            requestedName = requestedName,
            city = city,
            neighborhood = "Centre",
            requestedAt = now,
        )

    private fun activeCooperative(
        members: List<CooperativeMember> = listOf(CooperativeMember("merchant-1", CooperativeMemberStatus.Active, now)),
    ): CooperativeMarket =
        CooperativeMarket(
            id = "coop-1",
            code = "MULHOUSE",
            name = "Marche de Mulhouse",
            city = "Mulhouse",
            neighborhood = "Centre",
            status = CooperativeStatus.Active,
            createdByAdminId = "admin-1",
            members = members,
            createdAt = now,
            updatedAt = now,
        )
}
