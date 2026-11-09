package dev.orestegabo.sequo_api.domain.delivery

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface MerchantSubOrderRepository : JpaRepository<MerchantSubOrder, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MerchantSubOrder s where s.id = :id")
    fun lockForAction(id: String): MerchantSubOrder?

    @Query("select s.orderId from MerchantSubOrder s where s.id = :id")
    fun findOrderIdForAction(id: String): String?

    @Query("select s.merchantId from MerchantSubOrder s where s.id = :id")
    fun findMerchantIdForAction(id: String): String?

    @Query("select s.id from MerchantSubOrder s where s.orderId = :orderId order by s.id")
    fun findIdsForOrder(orderId: String): List<String>

    @Query("select s.id from MerchantSubOrder s join s.order parent where s.status = dev.orestegabo.sequo_api.domain.delivery.MerchantSubOrderStatus.MERCHANT_PENDING and s.responseDeadlineAt <= :at and s.responseExpiredAt is null and parent.paymentStatus = dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus.Validated and parent.orderStatus = dev.orestegabo.sequo_api.domain.order.CustomerOrderStatus.ACCEPTED_FOR_FULFILLMENT order by s.responseDeadlineAt, s.id")
    fun findDueResponseIds(at: java.time.Instant, pageable: org.springframework.data.domain.Pageable): List<String>

    fun existsByOrderIdAndResponseDisposition(orderId: String, responseDisposition: String): Boolean

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
