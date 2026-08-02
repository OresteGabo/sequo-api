package dev.orestegabo.sequo_api.domain.delivery

import org.springframework.data.jpa.repository.JpaRepository

interface MerchantSubOrderRepository : JpaRepository<MerchantSubOrder, String> {
    fun findBySubOrderCode(subOrderCode: String): MerchantSubOrder?

    fun findByOrderId(orderId: String): List<MerchantSubOrder>

    fun findByMerchantIdAndStatusIn(
        merchantId: String,
        statuses: Collection<MerchantSubOrderStatus>,
    ): List<MerchantSubOrder>
}
