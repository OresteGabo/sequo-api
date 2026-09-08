package dev.orestegabo.sequo_api.domain.referral

import java.time.Instant

enum class ReferralDeliveryCreditStatus {
    Active,
    Expired,
    Consumed,
    Revoked,
}

enum class ReferralCreditUse {
    DeliveryFee,
    ItemPrice,
    PlatformMargin,
    ServiceFee,
    Tip,
    Refund,
    MerchantPayout,
    CourierPayout,
    CashWithdrawal,
    WalletTransfer,
}

data class ReferralDeliveryCredit(
    val id: String,
    val customerId: String,
    val sourceReferralId: String?,
    val amountCfa: Int,
    val remainingCfa: Int,
    val status: ReferralDeliveryCreditStatus,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "id is required." }
        require(customerId.isNotBlank()) { "customerId is required." }
        require(amountCfa > 0) { "amountCfa must be positive." }
        require(remainingCfa in 0..amountCfa) { "remainingCfa must be between 0 and amountCfa." }
    }
}

data class ReferralCreditIssueCommand(
    val creditId: String,
    val customerId: String,
    val sourceReferralId: String?,
    val amountCfa: Int,
    val expiresAt: Instant?,
    val issuedAt: Instant,
)

data class ReferralCreditApplicationCommand(
    val credit: ReferralDeliveryCredit,
    val customerId: String,
    val deliveryFeeAfterDiscountsCfa: Int,
    val use: ReferralCreditUse,
    val appliedAt: Instant,
)

data class ReferralCreditApplication(
    val credit: ReferralDeliveryCredit,
    val originalCreditAmountCfa: Int,
    val appliedAmountCfa: Int,
    val remainingAmountCfa: Int,
    val deliveryFeeBeforeCreditCfa: Int,
    val deliveryFeeAfterCreditCfa: Int,
    val sourceReferralId: String?,
    val expiresAt: Instant?,
)

data class ReferralCreditRejection(
    val code: String,
    val message: String,
)

sealed class ReferralCreditResult {
    data class Accepted(val application: ReferralCreditApplication) : ReferralCreditResult()
    data class Issued(val credit: ReferralDeliveryCredit) : ReferralCreditResult()
    data class Rejected(val rejection: ReferralCreditRejection) : ReferralCreditResult()
}

class ReferralDeliveryCreditService {
    fun issue(command: ReferralCreditIssueCommand): ReferralCreditResult {
        if (command.creditId.isBlank()) return rejected("missing_credit_id", "Credit id is required.")
        if (command.customerId.isBlank()) return rejected("missing_customer_id", "Customer id is required.")
        if (command.amountCfa <= 0) return rejected("invalid_credit_amount", "Credit amount must be positive.")
        if (command.expiresAt != null && !command.expiresAt.isAfter(command.issuedAt)) {
            return rejected("invalid_expiry", "Credit expiry must be after issue time.")
        }

        return ReferralCreditResult.Issued(
            ReferralDeliveryCredit(
                id = command.creditId,
                customerId = command.customerId,
                sourceReferralId = command.sourceReferralId,
                amountCfa = command.amountCfa,
                remainingCfa = command.amountCfa,
                status = ReferralDeliveryCreditStatus.Active,
                expiresAt = command.expiresAt,
                createdAt = command.issuedAt,
                updatedAt = command.issuedAt,
            )
        )
    }

    fun applyToDeliveryFee(command: ReferralCreditApplicationCommand): ReferralCreditResult {
        val credit = expireIfNeeded(command.credit, command.appliedAt)
        if (command.customerId != credit.customerId) {
            return rejected("customer_mismatch", "Credit belongs to another customer.")
        }
        if (command.deliveryFeeAfterDiscountsCfa < 0) {
            return rejected("invalid_delivery_fee", "Delivery fee cannot be negative.")
        }
        if (command.use != ReferralCreditUse.DeliveryFee) {
            return rejected("delivery_credit_only", "Referral credit can reduce delivery fees only.")
        }
        if (credit.status != ReferralDeliveryCreditStatus.Active) {
            return rejected("credit_not_active", "Referral credit is not active.")
        }
        if (credit.remainingCfa <= 0) {
            return rejected("credit_empty", "Referral credit has no remaining amount.")
        }

        val appliedAmount = credit.remainingCfa.coerceAtMost(command.deliveryFeeAfterDiscountsCfa)
        val remainingAmount = credit.remainingCfa - appliedAmount
        val updatedCredit = credit.copy(
            remainingCfa = remainingAmount,
            status = if (remainingAmount == 0) ReferralDeliveryCreditStatus.Consumed else ReferralDeliveryCreditStatus.Active,
            updatedAt = command.appliedAt,
        )

        return ReferralCreditResult.Accepted(
            ReferralCreditApplication(
                credit = updatedCredit,
                originalCreditAmountCfa = credit.amountCfa,
                appliedAmountCfa = appliedAmount,
                remainingAmountCfa = remainingAmount,
                deliveryFeeBeforeCreditCfa = command.deliveryFeeAfterDiscountsCfa,
                deliveryFeeAfterCreditCfa = command.deliveryFeeAfterDiscountsCfa - appliedAmount,
                sourceReferralId = credit.sourceReferralId,
                expiresAt = credit.expiresAt,
            )
        )
    }

    fun expireIfNeeded(credit: ReferralDeliveryCredit, evaluatedAt: Instant): ReferralDeliveryCredit =
        if (
            credit.status == ReferralDeliveryCreditStatus.Active &&
            credit.expiresAt != null &&
            !evaluatedAt.isBefore(credit.expiresAt)
        ) {
            credit.copy(status = ReferralDeliveryCreditStatus.Expired, updatedAt = evaluatedAt)
        } else {
            credit
        }

    fun revoke(credit: ReferralDeliveryCredit, revokedAt: Instant): ReferralDeliveryCredit =
        credit.copy(status = ReferralDeliveryCreditStatus.Revoked, updatedAt = revokedAt)

    private fun rejected(code: String, message: String): ReferralCreditResult.Rejected =
        ReferralCreditResult.Rejected(ReferralCreditRejection(code, message))
}
