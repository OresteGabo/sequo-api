package dev.orestegabo.sequo_api.domain.delivery

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.platform.CustomerSubscriptionRepository
import dev.orestegabo.sequo_api.domain.platform.CustomerSubscriptionStatus
import java.time.Instant
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

enum class DeliveryModeChoice { EXPRESS, CLASSIC, DEFERRED }

data class DeliveryModeOffer(val id: DeliveryModeChoice, val missionMode: DeliveryMissionRecordMode,
    val etaMinMinutes: Int, val etaMaxMinutes: Int, val zoneId: String, val deliveryFeeCfa: Int)

fun readDeliveryModeOffer(payload: String): DeliveryModeOffer {
    val n = ObjectMapper().readTree(payload)
    return DeliveryModeOffer(DeliveryModeChoice.valueOf(n["id"].asText()), DeliveryMissionRecordMode.valueOf(n["missionMode"].asText()),
        n["etaMinMinutes"].asInt(), n["etaMaxMinutes"].asInt(), n["zoneId"].asText(), n["deliveryFeeCfa"].asInt())
}

@Service
class DeliveryOptionsService(private val environment: Environment, private val benefits: dev.orestegabo.sequo_api.domain.subscription.DeliveryBenefitsService) {
    fun offers(distanceKm: Double, deliveryFeeCfa: Int): List<DeliveryModeOffer> {
        val zone = environment.getProperty("sequo.delivery.zone-id").orEmpty().trim()
        val limit = environment.getProperty("sequo.delivery.max-distance-km")?.toDoubleOrNull()
        if (zone.isBlank() || limit == null || !limit.isFinite() || limit <= 0 || distanceKm > limit) return emptyList()
        return DeliveryModeChoice.entries.mapNotNull { choice ->
            val key = "sequo.delivery.modes.${choice.name.lowercase()}"
            val min = environment.getProperty("$key.eta-min-minutes")?.toIntOrNull()
            val max = environment.getProperty("$key.eta-max-minutes")?.toIntOrNull()
            if (environment.getProperty("$key.enabled", Boolean::class.java, false) != true || min == null || max == null || min <= 0 || max < min) return@mapNotNull null
            DeliveryModeOffer(choice, when (choice) {
                DeliveryModeChoice.EXPRESS -> DeliveryMissionRecordMode.EXPRESS
                DeliveryModeChoice.CLASSIC -> DeliveryMissionRecordMode.STANDARD
                DeliveryModeChoice.DEFERRED -> DeliveryMissionRecordMode.PROGRAMMED
            }, min, max, zone, deliveryFeeCfa)
        }
    }
    fun select(choice: String?, offers: List<DeliveryModeOffer>): DeliveryModeOffer? =
        if (choice == null) offers.firstOrNull { it.id == DeliveryModeChoice.EXPRESS }
        else requireNotNull(offers.firstOrNull { it.id.name == choice }) { "Delivery option is unavailable. Review a new quote." }

    fun subscriberPriority(customerId: String, now: Instant = Instant.now()): Boolean = benefits.subscriberPriority(customerId)
}
