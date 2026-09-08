package dev.orestegabo.sequo_api.domain.cooperative

import java.time.Instant

enum class CooperativeStatus {
    Pending,
    Active,
    Suspended,
}

enum class CooperativeMemberStatus {
    Pending,
    Active,
    Suspended,
}

enum class CooperativeRequestType {
    CreateCooperative,
    JoinCooperative,
}

enum class CooperativeRequestStatus {
    Pending,
    Approved,
    Rejected,
    Cancelled,
}

data class CooperativeMarket(
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val neighborhood: String?,
    val status: CooperativeStatus,
    val createdByAdminId: String,
    val members: List<CooperativeMember> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "id is required." }
        require(code.isNotBlank()) { "code is required." }
        require(name.isNotBlank()) { "name is required." }
        require(city.isNotBlank()) { "city is required." }
        require(createdByAdminId.isNotBlank()) { "createdByAdminId is required." }
    }
}

data class CooperativeMember(
    val merchantId: String,
    val status: CooperativeMemberStatus,
    val joinedAt: Instant,
) {
    init {
        require(merchantId.isNotBlank()) { "merchantId is required." }
    }
}

data class CooperativeRequest(
    val id: String,
    val requestCode: String,
    val requestedByUserId: String,
    val merchantId: String,
    val type: CooperativeRequestType,
    val cooperativeId: String?,
    val requestedName: String?,
    val city: String?,
    val neighborhood: String?,
    val status: CooperativeRequestStatus,
    val reviewedByAdminId: String? = null,
    val reviewReason: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "id is required." }
        require(requestCode.isNotBlank()) { "requestCode is required." }
        require(requestedByUserId.isNotBlank()) { "requestedByUserId is required." }
        require(merchantId.isNotBlank()) { "merchantId is required." }
    }
}

data class CooperativeItem(
    val productId: String,
    val merchantId: String,
    val productName: String,
    val priceCfa: Int,
) {
    init {
        require(productId.isNotBlank()) { "productId is required." }
        require(merchantId.isNotBlank()) { "merchantId is required." }
        require(productName.isNotBlank()) { "productName is required." }
        require(priceCfa >= 0) { "priceCfa cannot be negative." }
    }
}

data class CooperativeStorefront(
    val cooperativeId: String,
    val cooperativeName: String,
    val customerFacingPackageLabel: String,
    val items: List<CooperativeItem>,
    val memberMerchantIds: Set<String>,
    val preservesMerchantOwnership: Boolean,
)

data class CooperativeRequestCommand(
    val requestId: String,
    val requestCode: String,
    val requestedByUserId: String,
    val merchantId: String,
    val type: CooperativeRequestType,
    val cooperativeId: String? = null,
    val requestedName: String? = null,
    val city: String? = null,
    val neighborhood: String? = null,
    val requestedAt: Instant,
)

data class CooperativeApprovalCommand(
    val request: CooperativeRequest,
    val adminId: String,
    val reviewedAt: Instant,
    val cooperativeId: String,
    val cooperativeCode: String,
)

data class CooperativeRejectionCommand(
    val request: CooperativeRequest,
    val adminId: String,
    val reviewedAt: Instant,
    val reason: String,
)

data class CooperativeMemberCommand(
    val cooperative: CooperativeMarket,
    val merchantId: String,
    val joinedAt: Instant,
)

data class CooperativeRejection(
    val code: String,
    val message: String,
)

sealed class CooperativeResult {
    data class RequestAccepted(val request: CooperativeRequest) : CooperativeResult()
    data class MarketCreated(val cooperative: CooperativeMarket, val reviewedRequest: CooperativeRequest) : CooperativeResult()
    data class MembershipApproved(val cooperative: CooperativeMarket, val reviewedRequest: CooperativeRequest) : CooperativeResult()
    data class MemberAdded(val cooperative: CooperativeMarket) : CooperativeResult()
    data class RequestRejected(val request: CooperativeRequest) : CooperativeResult()
    data class Rejected(val rejection: CooperativeRejection) : CooperativeResult()
}

class CooperativeMarketService {
    fun request(command: CooperativeRequestCommand): CooperativeResult {
        validateRequest(command)?.let { return CooperativeResult.Rejected(it) }

        return CooperativeResult.RequestAccepted(
            CooperativeRequest(
                id = command.requestId,
                requestCode = command.requestCode,
                requestedByUserId = command.requestedByUserId,
                merchantId = command.merchantId,
                type = command.type,
                cooperativeId = command.cooperativeId,
                requestedName = command.requestedName,
                city = command.city,
                neighborhood = command.neighborhood,
                status = CooperativeRequestStatus.Pending,
                createdAt = command.requestedAt,
                updatedAt = command.requestedAt,
            )
        )
    }

    fun approveCreation(command: CooperativeApprovalCommand): CooperativeResult {
        val request = command.request
        validateReview(command.adminId, request)?.let { return CooperativeResult.Rejected(it) }
        if (request.type != CooperativeRequestType.CreateCooperative) {
            return rejected("invalid_request_type", "Only create-cooperative requests can create a cooperative market.")
        }
        if (command.cooperativeId.isBlank()) return rejected("missing_cooperative_id", "Cooperative id is required.")
        if (command.cooperativeCode.isBlank()) return rejected("missing_cooperative_code", "Cooperative code is required.")
        val requestedName = request.requestedName ?: return rejected("missing_requested_name", "Requested name is required.")
        val requestedCity = request.city ?: return rejected("missing_city", "City is required.")

        val reviewedRequest = request.copy(
            status = CooperativeRequestStatus.Approved,
            reviewedByAdminId = command.adminId,
            updatedAt = command.reviewedAt,
        )
        val member = CooperativeMember(
            merchantId = request.merchantId,
            status = CooperativeMemberStatus.Active,
            joinedAt = command.reviewedAt,
        )
        val cooperative = CooperativeMarket(
            id = command.cooperativeId,
            code = command.cooperativeCode,
            name = requestedName,
            city = requestedCity,
            neighborhood = request.neighborhood,
            status = CooperativeStatus.Active,
            createdByAdminId = command.adminId,
            members = listOf(member),
            createdAt = command.reviewedAt,
            updatedAt = command.reviewedAt,
        )

        return CooperativeResult.MarketCreated(cooperative, reviewedRequest)
    }

    fun approveMembership(
        request: CooperativeRequest,
        cooperative: CooperativeMarket,
        adminId: String,
        reviewedAt: Instant,
    ): CooperativeResult {
        validateReview(adminId, request)?.let { return CooperativeResult.Rejected(it) }
        if (request.type != CooperativeRequestType.JoinCooperative) {
            return rejected("invalid_request_type", "Only join-cooperative requests can add a member.")
        }
        if (request.cooperativeId != cooperative.id) {
            return rejected("cooperative_mismatch", "Request targets another cooperative.")
        }

        return when (val added = addMember(CooperativeMemberCommand(cooperative, request.merchantId, reviewedAt))) {
            is CooperativeResult.MemberAdded -> {
                val reviewedRequest = request.copy(
                    status = CooperativeRequestStatus.Approved,
                    reviewedByAdminId = adminId,
                    updatedAt = reviewedAt,
                )
                CooperativeResult.MembershipApproved(added.cooperative, reviewedRequest)
            }
            is CooperativeResult.Rejected -> added
            else -> rejected("unexpected_membership_result", "Membership approval produced an unexpected result.")
        }
    }

    fun rejectRequest(command: CooperativeRejectionCommand): CooperativeResult {
        validateReview(command.adminId, command.request)?.let { return CooperativeResult.Rejected(it) }
        if (command.reason.isBlank()) return rejected("missing_review_reason", "Review reason is required.")

        return CooperativeResult.RequestRejected(
            command.request.copy(
                status = CooperativeRequestStatus.Rejected,
                reviewedByAdminId = command.adminId,
                reviewReason = command.reason,
                updatedAt = command.reviewedAt,
            )
        )
    }

    fun addMember(command: CooperativeMemberCommand): CooperativeResult {
        if (command.merchantId.isBlank()) return rejected("missing_merchant_id", "Merchant id is required.")
        if (command.cooperative.status != CooperativeStatus.Active) {
            return rejected("cooperative_not_active", "Members can be added only to active cooperatives.")
        }
        if (command.cooperative.members.any { it.merchantId == command.merchantId }) {
            return rejected("member_already_exists", "Merchant is already a cooperative member.")
        }

        return CooperativeResult.MemberAdded(
            command.cooperative.copy(
                members = command.cooperative.members + CooperativeMember(
                    merchantId = command.merchantId,
                    status = CooperativeMemberStatus.Active,
                    joinedAt = command.joinedAt,
                ),
                updatedAt = command.joinedAt,
            )
        )
    }

    fun buildStorefront(cooperative: CooperativeMarket, items: List<CooperativeItem>): CooperativeStorefront {
        require(cooperative.status == CooperativeStatus.Active) { "cooperative must be active." }
        val activeMemberIds = cooperative.members
            .filter { it.status == CooperativeMemberStatus.Active }
            .map { it.merchantId }
            .toSet()
        require(items.all { it.merchantId in activeMemberIds }) {
            "Every cooperative storefront item must belong to an active member."
        }

        return CooperativeStorefront(
            cooperativeId = cooperative.id,
            cooperativeName = cooperative.name,
            customerFacingPackageLabel = "${cooperative.name} package",
            items = items,
            memberMerchantIds = items.map { it.merchantId }.toSet(),
            preservesMerchantOwnership = items.all { it.merchantId.isNotBlank() },
        )
    }

    private fun validateRequest(command: CooperativeRequestCommand): CooperativeRejection? {
        if (command.requestId.isBlank()) return CooperativeRejection("missing_request_id", "Request id is required.")
        if (command.requestCode.isBlank()) return CooperativeRejection("missing_request_code", "Request code is required.")
        if (command.requestedByUserId.isBlank()) return CooperativeRejection("missing_requester", "Requester id is required.")
        if (command.merchantId.isBlank()) return CooperativeRejection("missing_merchant_id", "Merchant id is required.")

        return when (command.type) {
            CooperativeRequestType.CreateCooperative -> {
                if (command.requestedName.isNullOrBlank()) {
                    CooperativeRejection("missing_requested_name", "Requested cooperative name is required.")
                } else if (command.city.isNullOrBlank()) {
                    CooperativeRejection("missing_city", "City is required for cooperative creation.")
                } else {
                    null
                }
            }
            CooperativeRequestType.JoinCooperative -> {
                if (command.cooperativeId.isNullOrBlank()) {
                    CooperativeRejection("missing_cooperative_id", "Cooperative id is required for membership requests.")
                } else {
                    null
                }
            }
        }
    }

    private fun validateReview(adminId: String, request: CooperativeRequest): CooperativeRejection? {
        if (adminId.isBlank()) return CooperativeRejection("missing_admin_id", "Admin id is required.")
        if (request.status != CooperativeRequestStatus.Pending) {
            return CooperativeRejection("request_not_pending", "Only pending cooperative requests can be reviewed.")
        }

        return null
    }

    private fun rejected(code: String, message: String): CooperativeResult.Rejected =
        CooperativeResult.Rejected(CooperativeRejection(code, message))
}
