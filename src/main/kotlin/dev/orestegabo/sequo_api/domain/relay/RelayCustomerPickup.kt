package dev.orestegabo.sequo_api.domain.relay

import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry
import dev.orestegabo.sequo_api.domain.reference.referenceOrNull

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.order.CustomerOrderRecordRepository
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

data class CustomerRelayPickup(val parcelId: String, val relayPointId: String, val status: RelayParcelStatus,
    val depositedAt: Instant?, val pickedUpAt: Instant?, val expiresAt: Instant?, val credentialState: String,
    val numericCode: String? = null, val qrNonce: String? = null, val qrModules: List<String> = emptyList(),
    val publicReference: String? = null,
    val parcelReference: String? = null,
    val orderReference: String? = null,
    val relayReference: String? = null,
)

data class IssuedRelayCredential(val id: String, val expiresAt: Instant)

@Service
class RelayCustomerPickupService(private val parcels: RelayParcelPersistenceService,
    private val records: RelayParcelRecordRepository, private val codes: RelayPickupCodeRecordRepository,
    private val orders: CustomerOrderRecordRepository, private val domain: RelayParcelService,
    @Value("\${sequo.notifications.token-encryption-secret}") private val secret: String,
    private val references: PublicReferenceRegistry? = null) {
    init { require(secret.length >= 32) { "Relay credential signing requires the configured protected server secret." } }
    private val hasher = Sha256RelayCredentialHasher()

    @Transactional
    fun issue(parcelId: String, now: Instant = Instant.now()): IssuedRelayCredential {
        val parcel = requireNotNull(parcels.lockParcel(parcelId)) { "Parcel is unavailable." }
        require(parcel.status.awaitingPickup) { "Only deposited parcels can receive pickup credentials." }
        val order = parcel.orderId?.let { orders.findById(it).orElse(null) }
        require(order != null && order.paymentStatus == PaymentValidationStatus.Validated) { "An owned paid customer order is required." }
        val existing = parcels.findPickupCode(parcelId)
        if (existing?.customerDeliveryVersion == 1 && existing.usedAt == null && now.isBefore(existing.expiresAt) && existing.attemptCount < 5) {
            val raw = derive(order.customerId, parcelId, existing.id)
            if (hasher.hash(raw.first) == existing.codeHash && hasher.hash(raw.second) == existing.qrNonceHash)
                return IssuedRelayCredential(existing.id, existing.expiresAt)
        }
        val id = UUID.randomUUID().toString()
        val raw = derive(order.customerId, parcelId, id)
        val result = domain.createPickupCode(RelayPickupCodeCreateCommand(id, parcel, raw.first, raw.second,
            identityCheckRequired = true, expiresAt = now.plusSeconds(24 * 60 * 60), createdAt = now))
        require(result is RelayParcelServiceResult.Accepted) { "Pickup credential could not be issued." }
        val managed = result.copy(value = result.value.copy(pickupCode = requireNotNull(result.value.pickupCode).copy(customerDeliveryVersion = 1)))
        parcels.savePickupCode(managed)
        return IssuedRelayCredential(id, requireNotNull(managed.value.pickupCode).expiresAt)
    }

    fun owned(customerId: String, orderId: String): Boolean = orders.findById(orderId).orElse(null)?.customerId == customerId
    fun parcelOwned(orderId: String, parcelId: String): Boolean = records.findById(parcelId).orElse(null)?.orderId == orderId

    @Transactional(readOnly = true)
    fun list(customerId: String, orderId: String, now: Instant = Instant.now()): List<CustomerRelayPickup> {
        require(owned(customerId, orderId)) { "Order is unavailable." }
        return records.findByOrderIdOrderByCreatedAtAsc(orderId).map { parcel ->
            val code = parcels.findPickupCode(parcel.id)
            val state = when {
                !parcel.status.awaitingPickup -> "CLOSED"
                code == null || code.customerDeliveryVersion != 1 -> "UNAVAILABLE"
                code.usedAt != null -> "USED"
                !now.isBefore(code.expiresAt) -> "EXPIRED"
                code.attemptCount >= 5 -> "LOCKED"
                else -> "READY"
            }
            val raw = code?.takeIf { state == "READY" }?.let { derive(customerId, parcel.id, it.id) }
                ?.takeIf { hasher.hash(it.first) == code.codeHash && hasher.hash(it.second) == code.qrNonceHash }
            val matrix = raw?.let { QRCodeWriter().encode(it.second, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4)) }
            CustomerRelayPickup(parcel.id, parcel.relayPointId, parcel.status, parcel.depositedAt, parcel.pickedUpAt,
                code?.expiresAt, if (state == "READY" && raw == null) "UNAVAILABLE" else state, raw?.first, raw?.second,
                matrix?.let { m -> (0 until m.height).map { y -> (0 until m.width).joinToString("") { x -> if (m[x,y]) "1" else "0" } } }.orEmpty(),
                publicReference = references.referenceOrNull(PublicReferenceKind.PARCEL, parcel.id),
                parcelReference = references.referenceOrNull(PublicReferenceKind.PARCEL, parcel.id),
                orderReference = references.referenceOrNull(PublicReferenceKind.ORDER, parcel.orderId),
                relayReference = references.referenceOrNull(PublicReferenceKind.RELAY, parcel.relayPointId))
        }
    }
    private fun derive(customerId: String, parcelId: String, codeId: String): Pair<String, String> {
        fun digest(kind: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal("sequo-relay-pickup:v1:$kind:$customerId:$parcelId:$codeId".toByteArray())
        }
        val bytes = digest("numeric")
        val number = java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
        return (number % 1_000_000).toString().padStart(6, '0') to Base64.getUrlEncoder().withoutPadding().encodeToString(digest("qr"))
    }
}

@RestController
class RelayCustomerPickupController(private val service: RelayCustomerPickupService,
    private val parcels: RelayParcelApplicationService, private val access: RelayOperatorAccess) {
    @GetMapping("/api/orders/{orderId}/relay-pickups")
    fun list(@AuthenticationPrincipal customerId: String?, @PathVariable orderId: String): ResponseEntity<Any> =
        if (customerId == null) ResponseEntity.status(401).build()
        else if (!service.owned(customerId, orderId)) ResponseEntity.notFound().build()
        else ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(customerId, orderId))

    @PostMapping("/api/orders/{orderId}/relay-pickups/{parcelId}/credential")
    fun renew(@AuthenticationPrincipal customerId: String?, @PathVariable orderId: String, @PathVariable parcelId: String): ResponseEntity<Any> =
        if (customerId == null) ResponseEntity.status(401).build()
        else if (!service.owned(customerId, orderId) || !service.parcelOwned(orderId, parcelId)) ResponseEntity.notFound().build()
        else ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.issue(parcelId))

    @PostMapping("/api/relay/parcels/{parcelId}/pickup-credential")
    fun issue(authentication: Authentication?, @PathVariable parcelId: String): ResponseEntity<Any> {
        if (authentication == null) return ResponseEntity.status(401).build()
        if (!authentication.hasAnyRole(RoleGroups.RelayOperators)) return ResponseEntity.status(403).build()
        val parcel = parcels.getParcel(parcelId) ?: return ResponseEntity.notFound().build()
        if (!access.permits(authentication, parcel.relayPointId)) return ResponseEntity.status(403).build()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.issue(parcelId))
    }
}
