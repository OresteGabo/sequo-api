package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.commerce.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class AdminNegotiationOfferDto(val id: String, val actorUserId: String, val actorType: BargainingActorType,
    val offerType: BargainingOfferType, val amountCfa: Int?, val createdAt: Instant)
data class AdminNegotiationThreadDto(
    val id: String, val customerId: String, val merchantId: String, val productId: String,
    val productName: String?, val catalogPriceCfa: Int?, val bargainingEnabled: Boolean, val status: BargainingThreadStatus,
    val lastOfferCfa: Int?, val acceptedPriceCfa: Int?, val openedAt: Instant, val acceptedAt: Instant?, val expiresAt: Instant?,
    val customerAttemptsUsed: Int, val merchantAttemptsUsed: Int, val maxCustomerAttempts: Int, val maxMerchantAttempts: Int,
    val nextActor: BargainingActorType?, val history: List<AdminNegotiationOfferDto>,
)

@Service
class AdminNegotiationReadService(private val threads: BargainingThreadRepository, private val bargaining: PersistedBargainingService) {
    @Transactional(readOnly = true)
    fun list(limit: Int, merchantId: String?): List<AdminNegotiationThreadDto> {
        val page = PageRequest.of(0, limit.coerceIn(1, 200), Sort.by(Sort.Direction.DESC, "openedAt", "id"))
        val rows = if (merchantId == null) threads.findAll(page)
        else threads.findByMerchantId(ApiInputPolicy.requiredIdentifier(merchantId, "merchantId"), page)
        return rows.content.mapNotNull { detail(it.id) }
    }

    @Transactional(readOnly = true)
    fun detail(id: String): AdminNegotiationThreadDto? = bargaining.administrativeSnapshot(id)?.let {
        AdminNegotiationThreadDto(it.id, it.customerId, it.merchantId, it.productId, it.productName, it.catalogPriceCfa,
            it.bargainingEnabled, it.status, it.lastOfferCfa, it.acceptedPriceCfa, it.openedAt, it.acceptedAt, it.expiresAt,
            it.customerAttemptsUsed, it.merchantAttemptsUsed, it.maxCustomerAttempts, it.maxMerchantAttempts, it.nextActor,
            it.offers.map { offer -> AdminNegotiationOfferDto(offer.id, offer.actorUserId, offer.actorType, offer.offerType, offer.amountCfa, offer.createdAt) })
    }
}

@RestController
@RequestMapping("/api/admin/web/bargaining-threads")
class AdminNegotiationReadController(private val service: AdminNegotiationReadService) {
    @GetMapping
    fun list(authentication: Authentication?, @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) merchantId: String?): ResponseEntity<Any> = adminControlRead(authentication) {
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(limit, merchantId))
    }

    @GetMapping("/{id}")
    fun detail(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Any> = adminControlRead(authentication) {
        service.detail(ApiInputPolicy.requiredIdentifier(id, "id"))?.let {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(it)
        } ?: ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
    }
}
