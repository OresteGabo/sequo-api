package dev.orestegabo.sequo_api.domain.delivery

import org.springframework.data.jpa.repository.JpaRepository

interface MerchantSubOrderRepository : JpaRepository<MerchantSubOrder, String> {
    fun findBySubOrderCode(subOrderCode: String): MerchantSubOrder?

    fun findByOrderId(orderId: String): List<MerchantSubOrder>

    fun countByStatusIn(statuses: Collection<MerchantSubOrderStatus>): Long

    fun findTop20ByStatusInOrderByUpdatedAtAsc(statuses: Collection<MerchantSubOrderStatus>): List<MerchantSubOrder>

    fun findTop200ByStatusInOrderByUpdatedAtAsc(statuses: Collection<MerchantSubOrderStatus>): List<MerchantSubOrder>

    fun findTop200ByStatusOrderByUpdatedAtAsc(status: MerchantSubOrderStatus): List<MerchantSubOrder>

    fun findByMerchantIdAndStatusInOrderByUpdatedAtDesc(
        merchantId: String,
        statuses: Collection<MerchantSubOrderStatus>,
    ): List<MerchantSubOrder>
}
