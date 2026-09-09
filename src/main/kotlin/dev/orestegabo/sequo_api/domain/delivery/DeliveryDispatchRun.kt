package dev.orestegabo.sequo_api.domain.delivery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "delivery_dispatch_runs")
class DeliveryDispatchRunRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: String? = null,

    @Column(name = "scanned_ready_sub_orders", nullable = false)
    val scannedReadySubOrders: Int,

    @Column(name = "created_mission_ids", nullable = false, length = 4000)
    val createdMissionIds: String,

    @Column(name = "existing_mission_ids", nullable = false, length = 4000)
    val existingMissionIds: String,

    @Column(name = "skipped_sub_order_ids", nullable = false, length = 4000)
    val skippedSubOrderIds: String,

    @Column(name = "skipped_reasons", nullable = false, length = 4000)
    val skippedReasons: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

interface DeliveryDispatchRunRepository : JpaRepository<DeliveryDispatchRunRecord, String> {
    fun findTop20ByOrderByCreatedAtDesc(): List<DeliveryDispatchRunRecord>
}
