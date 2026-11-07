package dev.orestegabo.sequo_api.domain.delivery

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface MerchantSubOrderRepository : JpaRepository<MerchantSubOrder, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MerchantSubOrder s where s.id = :id")
    fun lockForAction(id: String): MerchantSubOrder?

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
