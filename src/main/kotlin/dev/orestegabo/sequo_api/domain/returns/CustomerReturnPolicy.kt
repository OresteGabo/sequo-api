package dev.orestegabo.sequo_api.domain.returns

import dev.orestegabo.sequo_api.domain.order.OrderProductCategory
import dev.orestegabo.sequo_api.domain.relay.RelayDestination
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

@Configuration(proxyBeanMethods = false)
class ReturnClockConfiguration {
    @Bean("returnClock")
    @ConditionalOnMissingBean(name = ["returnClock"])
    fun returnClock(): Clock = Clock.systemUTC()
}

enum class ClickAndCollectReturnMode { PENDING, SELLER_MANAGED, RELAY }

@Component
class CustomerReturnPolicy(
    @Value("\${sequo.returns.window-hours:72}") hours: Long,
    @Value("\${sequo.returns.excluded-categories:}") excluded: String,
    @Value("\${sequo.returns.click-and-collect-mode:PENDING}") clickMode: String,
    @Value("\${sequo.notifications.token-encryption-secret}") private val secret: String,
) {
    val window: Duration = Duration.ofHours(hours).also { require(hours > 0) { "Return window must be positive." } }
    // The PDF does not establish a blanket food-return ban. Only approved explicit exclusions apply.
    val excludedCategories: Set<OrderProductCategory> = excluded.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { value ->
        requireNotNull(OrderProductCategory.entries.firstOrNull { it.name.equals(value, true) }) { "Unknown configured return category." }
    }.toSet()
    val clickAndCollectMode = ClickAndCollectReturnMode.valueOf(clickMode.trim().uppercase())
    init { require(secret.length >= 32) { "Return credentials require the configured protected server secret." } }

    fun credential(customerId: String, orderId: String, returnId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val bytes = mac.doFinal("sequo-return-pin:v1:$customerId:$orderId:$returnId".toByteArray())
        val number = java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
        return (number % 1_000_000).toString().padStart(6, '0')
    }
}

data class CustomerReturnEligibility(
    val orderId: String,
    val eligible: Boolean,
    val code: String?,
    val message: String?,
    val deliveredAt: Instant?,
    val returnWindowEndsAt: Instant?,
    val existingReturnId: String?,
    val dropoffMethod: String,
    val instructions: String,
    val relayPoints: List<RelayDestination> = emptyList(),
    val orderReference: String? = null,
    val existingReturnReference: String? = null,
)

class ReturnWriteConflict : RuntimeException("Return request changed or its ID is already in use. Refresh before retrying.")
