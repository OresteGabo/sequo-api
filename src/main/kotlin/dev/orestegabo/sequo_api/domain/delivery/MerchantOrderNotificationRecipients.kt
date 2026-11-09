package dev.orestegabo.sequo_api.domain.delivery

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** A merchant ID is not a login identity. Only currently authorized active memberships receive inbox data. */
@Component
class MerchantOrderNotificationRecipients(private val jdbc: JdbcTemplate) {
    fun users(merchantId: String): Set<String> = jdbc.queryForList(
        """select distinct membership.user_id
           from merchant_memberships membership
           join users account on account.id = membership.user_id
           join merchants merchant on merchant.id = membership.merchant_id
           where membership.merchant_id = ? and membership.active = true
             and membership.role_code in ('MERCHANT_OWNER', 'MERCHANT_STAFF')
             and account.is_active = true and account.status = 'ACTIVE' and merchant.status = 'ACTIVE'
           order by membership.user_id""".trimIndent(), String::class.java, merchantId).filterNotNull().filter(String::isNotBlank).toSet()
}
