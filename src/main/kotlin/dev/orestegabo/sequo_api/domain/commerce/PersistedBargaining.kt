package dev.orestegabo.sequo_api.domain.commerce

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry
import dev.orestegabo.sequo_api.api.ApiInputPolicy
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.bargaining.BargainingPolicy
import dev.orestegabo.sequo_api.domain.catalog.CatalogProductStatus
import dev.orestegabo.sequo_api.domain.catalog.ProductRecord
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

data class NegotiatedCartPrice(val unitPriceCfa: Int, val expiresAt: Instant)
class BargainingRetryConflict : RuntimeException("Negotiation changed or the retry key was already used. Refresh before retrying.")

/** Persists the existing thread/offer model. All writers serialize on the customer account. */
@Service
class PersistedBargainingService(
    private val threads: BargainingThreadRepository,
    private val offers: BargainingOfferRepository,
    private val products: CommerceProductRepository,
    private val users: UserRepository,
    private val merchants: CommerceMerchantRepository,
    @Value("\${sequo.bargaining.max-customer-attempts:3}") maxCustomerAttempts: Int,
    @Value("\${sequo.bargaining.max-merchant-attempts:3}") maxMerchantAttempts: Int,
    @Value("\${sequo.bargaining.accepted-price-lock-hours:24}") acceptedPriceLockHours: Long,
    private val publicReferences: PublicReferenceRegistry? = null,
) {
    private val policy = BargainingPolicy(maxCustomerAttempts, maxMerchantAttempts, Duration.ofHours(acceptedPriceLockHours))
    private val mapper = ObjectMapper()

    /** Explicit staff projection source; HTTP authorization and redaction live in the admin surface. */
    @Transactional(readOnly = true)
    internal fun administrativeSnapshot(threadId: String): BargainingThreadDto? =
        threads.findById(threadId).orElse(null)?.let(::snapshot)

    @Transactional(readOnly = true)
    fun list(authentication: Authentication, merchantId: String?): List<BargainingThreadDto> {
        val records = if (merchantId != null) {
            val id = ApiInputPolicy.requiredIdentifier(merchantId, "merchantId")
            merchantAccess(authentication, id)
            threads.findByMerchantIdOrderByOpenedAtDesc(id)
        } else {
            requireCustomer(authentication)
            threads.findByCustomerIdOrderByOpenedAtDesc(authentication.name)
        }
        return records.map { snapshot(it) }
    }

    @Transactional(readOnly = true)
    fun get(authentication: Authentication, threadId: String): BargainingThreadDto {
        val thread = requireNotNull(threads.findById(ApiInputPolicy.requiredIdentifier(threadId, "threadId")).orElse(null)) { "Thread is unavailable." }
        actor(authentication, thread)
        return snapshot(thread)
    }

    @Transactional
    fun create(customerId: String, request: CreateBargainingThreadRequest): BargainingThreadDto {
        require(users.lockCheckoutOwner(customerId)?.active == true) { "Customer account is unavailable." }
        val productId = ApiInputPolicy.requiredIdentifier(request.productId, "productId")
        val message = ApiInputPolicy.optionalLongText(request.message, "message", 500)
        val key = retryKey(request.idempotencyKey)
        val fingerprint = fingerprint(listOf("create", productId, request.amountCfa, message))
        replay(customerId, key, fingerprint)?.let { return it }
        val product = requireProduct(productId)
        val merchantId = requireNotNull(product.merchantId)
        val existing = scopeThreads(customerId, merchantId, productId).firstOrNull()
        val thread = existing ?: threads.save(BargainingThreadRecord(UUID.randomUUID().toString(), customerId, merchantId, productId = productId))
        val current = snapshot(thread)
        require(current.status != BargainingThreadStatus.ACCEPTED) { "The accepted price is still valid; use the existing agreement." }
        return apply(thread, customerId, BargainingActorType.CUSTOMER,
            CreateBargainingOfferRequest(request.amountCfa, message, BargainingOfferType.OFFER, idempotencyKey = key), fingerprint, product)
    }

    @Transactional
    fun act(authentication: Authentication, threadId: String, request: CreateBargainingOfferRequest): BargainingThreadDto {
        val id = ApiInputPolicy.requiredIdentifier(threadId, "threadId")
        val customerId = requireNotNull(threads.customerReference(id)) { "Thread is unavailable." }
        // Same ordering as cart/checkout and thread creation: customer -> product -> thread.
        require(users.lockCheckoutOwner(customerId)?.active == true) { "Customer account is unavailable." }
        val preview = requireNotNull(threads.findById(id).orElse(null)) { "Thread is unavailable." }
        val side = actor(authentication, preview)
        val key = retryKey(request.idempotencyKey)
        val normalized = request.copy(
            offerType = request.offerType ?: if (request.amountCfa == null) BargainingOfferType.MESSAGE else
                if (side == BargainingActorType.CUSTOMER) BargainingOfferType.OFFER else BargainingOfferType.COUNTER_OFFER,
            message = ApiInputPolicy.optionalLongText(request.message, "message", 500), idempotencyKey = key,
        )
        val fingerprint = fingerprint(listOf(id, normalized.offerType, normalized.amountCfa, normalized.message, normalized.expectedOfferId))
        replay(authentication.name, key, fingerprint)?.let { return it }
        val product = requireProduct(preview.productId)
        val thread = requireNotNull(threads.lockForAction(id))
        require(thread.merchantId == product.merchantId) { "The product merchant changed; this negotiation is unavailable." }
        return apply(thread, authentication.name, side, normalized, fingerprint, product)
    }

    /** Compatibility entry point for supported internal callers of the old customer service. */
    @Transactional
    fun customerOffer(customerId: String, threadId: String, request: CreateBargainingOfferRequest): BargainingThreadDto {
        val auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken(customerId, null,
            listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER")))
        return act(auth, threadId, request)
    }

    @Transactional(readOnly = true)
    fun customerThreads(customerId: String): List<BargainingThreadDto> =
        threads.findByCustomerIdOrderByOpenedAtDesc(customerId).map { snapshot(it) }

    @Transactional(readOnly = true, noRollbackFor = [IllegalArgumentException::class])
    fun acceptedPrice(customerId: String, product: ProductRecord, threadId: String, at: Instant = Instant.now()): NegotiatedCartPrice {
        val thread = requireNotNull(threads.findById(ApiInputPolicy.requiredIdentifier(threadId, "bargainingThreadId")).orElse(null)) { "Negotiated price is unavailable. Remove the item and review the catalog price." }
        require(thread.customerId == customerId && thread.productId == product.id && thread.merchantId == product.merchantId) {
            "Negotiated price does not belong to this customer, merchant and product."
        }
        val expires = thread.expiresAt
        require(thread.status == BargainingThreadStatus.ACCEPTED && thread.closedAt != null && expires != null && at.isBefore(expires)) {
            "Negotiated price expired or was not accepted. Remove the item and review the catalog price."
        }
        val amount = requireNotNull(thread.acceptedPriceCfa)
        require(amount > 0 && product.basePriceCfa?.let { amount <= it } == true) {
            "The catalog price changed. Remove the item and review the current price."
        }
        return NegotiatedCartPrice(amount, expires)
    }

    private fun apply(thread: BargainingThreadRecord, actorId: String, side: BargainingActorType,
        request: CreateBargainingOfferRequest, fingerprint: String, product: ProductRecord): BargainingThreadDto {
        val state = snapshot(thread)
        val type = requireNotNull(request.offerType)
        require(state.status !in setOf(BargainingThreadStatus.ACCEPTED, BargainingThreadStatus.CANCELLED, BargainingThreadStatus.LOCKED_ATTEMPTS_EXHAUSTED)) {
            "Negotiation is accepted, cancelled or attempts are exhausted."
        }
        val history = offers.findByThreadIdOrderByCreatedAtAsc(thread.id)
        val pending = history.lastOrNull { it.offerType != BargainingOfferType.MESSAGE }
        val now = Instant.now()
        when (type) {
            BargainingOfferType.OFFER -> {
                require(side == BargainingActorType.CUSTOMER) { "Only the customer can submit a customer offer." }
                require(state.customerAttemptsUsed < policy.maxCustomerAttempts && state.merchantAttemptsUsed < policy.maxMerchantCounters) { "Bargaining attempts are exhausted for these parties and product." }
                require(state.nextActor == BargainingActorType.CUSTOMER || state.status == BargainingThreadStatus.EXPIRED) { "Wait for the merchant's response." }
                validateAmount(request.amountCfa, product)
                thread.status = BargainingThreadStatus.OPEN
                thread.acceptedPriceCfa = null; thread.closedAt = null; thread.expiresAt = null
                thread.lastOfferCfa = request.amountCfa
            }
            BargainingOfferType.COUNTER_OFFER -> {
                require(side == BargainingActorType.MERCHANT && state.nextActor == BargainingActorType.MERCHANT && pending?.actorType == BargainingActorType.CUSTOMER) { "A customer offer must be pending before a merchant counteroffer." }
                require(state.merchantAttemptsUsed < policy.maxMerchantCounters) { "Merchant counteroffer attempts are exhausted." }
                requireExpected(request, pending)
                validateAmount(request.amountCfa, product)
                thread.status = BargainingThreadStatus.OPEN; thread.lastOfferCfa = request.amountCfa
            }
            BargainingOfferType.ACCEPT, BargainingOfferType.REJECT -> {
                require(state.nextActor == side && pending != null && pending.actorType != side &&
                    pending.offerType in setOf(BargainingOfferType.OFFER, BargainingOfferType.COUNTER_OFFER)) { "An offer from the other party must be pending." }
                requireExpected(request, pending)
                require(request.amountCfa == null) { "A decision uses the pending server amount." }
                if (type == BargainingOfferType.ACCEPT) {
                    validateAmount(pending.amountCfa, product)
                    thread.status = BargainingThreadStatus.ACCEPTED
                    thread.acceptedPriceCfa = pending.amountCfa; thread.closedAt = now
                    thread.expiresAt = now.plus(policy.acceptedPriceLockTtl)
                } else {
                    thread.status = if (state.customerAttemptsUsed >= policy.maxCustomerAttempts || state.merchantAttemptsUsed >= policy.maxMerchantCounters)
                        BargainingThreadStatus.LOCKED_ATTEMPTS_EXHAUSTED else BargainingThreadStatus.REJECTED
                    thread.closedAt = now.takeIf { thread.status == BargainingThreadStatus.LOCKED_ATTEMPTS_EXHAUSTED }
                }
            }
            BargainingOfferType.MESSAGE -> {
                require(request.amountCfa == null && !request.message.isNullOrBlank()) { "A message requires text and cannot set a price." }
            }
        }
        try {
            offers.saveAndFlush(BargainingOfferRecord(UUID.randomUUID().toString(), thread.id, actorUserId = actorId, actorType = side,
                offerType = type, amountCfa = request.amountCfa, message = request.message, createdAt = now,
                idempotencyKey = request.idempotencyKey, requestFingerprint = fingerprint))
        } catch (error: org.springframework.dao.DataIntegrityViolationException) {
            if (request.idempotencyKey != null) throw BargainingRetryConflict()
            throw error
        }
        threads.save(thread)
        return snapshot(thread)
    }

    private fun snapshot(thread: BargainingThreadRecord): BargainingThreadDto {
        val product = products.findById(thread.productId).orElse(null)
        val history = offers.findByThreadIdOrderByCreatedAtAsc(thread.id)
        // Count supported legacy threads too; creating another ID never resets the same-party budget.
        val all = offers.findByThreadIdInOrderByCreatedAtAsc(scopeThreads(thread.customerId, thread.merchantId, thread.productId).map { it.id })
        val customerUsed = all.count { it.actorType == BargainingActorType.CUSTOMER && it.offerType == BargainingOfferType.OFFER }
        val merchantUsed = all.count { it.actorType == BargainingActorType.MERCHANT && it.offerType in setOf(BargainingOfferType.OFFER, BargainingOfferType.COUNTER_OFFER) }
        val status = when {
            thread.status == BargainingThreadStatus.ACCEPTED && (thread.expiresAt == null || !Instant.now().isBefore(thread.expiresAt)) -> BargainingThreadStatus.EXPIRED
            thread.status == BargainingThreadStatus.REJECTED && (customerUsed >= policy.maxCustomerAttempts || merchantUsed >= policy.maxMerchantCounters) -> BargainingThreadStatus.LOCKED_ATTEMPTS_EXHAUSTED
            else -> thread.status
        }
        val pending = history.lastOrNull { it.offerType != BargainingOfferType.MESSAGE }
        val next = when {
            status !in setOf(BargainingThreadStatus.OPEN, BargainingThreadStatus.REJECTED) -> null
            pending?.offerType == BargainingOfferType.OFFER && pending.actorType == BargainingActorType.CUSTOMER -> BargainingActorType.MERCHANT
            pending?.offerType == BargainingOfferType.COUNTER_OFFER && pending.actorType == BargainingActorType.MERCHANT -> BargainingActorType.CUSTOMER
            else -> BargainingActorType.CUSTOMER
        }
        return BargainingThreadDto(thread.id, thread.customerId, thread.merchantId, thread.productId, status,
            thread.lastOfferCfa, thread.acceptedPriceCfa, thread.openedAt,
            history.map { BargainingOfferDto(it.id, it.actorUserId, it.actorType, it.offerType, it.amountCfa, it.message, it.createdAt) },
            product?.name, product?.basePriceCfa, product?.let { it.status == CatalogProductStatus.ACTIVE && it.bargainingEnabled && it.merchantId == thread.merchantId } == true,
            customerUsed, merchantUsed, policy.maxCustomerAttempts, policy.maxMerchantCounters, thread.closedAt.takeIf { thread.acceptedPriceCfa != null }, thread.expiresAt, next,
            publicReference = publicReferences?.reference(PublicReferenceKind.BARGAINING, thread.id),
            customerReference = publicReferences?.reference(PublicReferenceKind.USER, thread.customerId),
            merchantReference = publicReferences?.reference(PublicReferenceKind.MERCHANT, thread.merchantId),
            productReference = publicReferences?.reference(PublicReferenceKind.PRODUCT, thread.productId))
    }

    private fun scopeThreads(customerId: String, merchantId: String, productId: String) =
        threads.findByCustomerIdAndMerchantIdAndProductIdOrderByOpenedAtDesc(customerId, merchantId, productId)

    private fun requireProduct(id: String): ProductRecord {
        val product = requireNotNull(products.findActiveForBargaining(id)) { "This product does not accept bargaining." }
        require(product.bargainingEnabled && product.merchantId != null && merchants.findById(product.merchantId!!).orElse(null)?.status == "ACTIVE") { "This product does not accept bargaining." }
        require(product.basePriceCfa?.let { it > 0 } == true) { "Product price is unavailable." }
        return product
    }

    private fun validateAmount(amount: Int?, product: ProductRecord) {
        require(amount != null && amount > 0 && amount < requireNotNull(product.basePriceCfa)) { "Offer must be positive and lower than the catalog price." }
        require(product.bargainFloorCfa?.let { amount >= it } != false) { "Offer is below the merchant's configured bargaining floor." }
    }

    private fun requireExpected(request: CreateBargainingOfferRequest, pending: BargainingOfferRecord?) {
        require(request.expectedOfferId != null && request.expectedOfferId == pending?.id) { "The offer changed. Refresh before responding." }
    }

    private fun requireCustomer(auth: Authentication) {
        if (!auth.hasAnyRole(setOf(RoleCode.CUSTOMER))) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Customer role is required.")
    }
    private fun merchantAccess(auth: Authentication, merchantId: String) {
        if (!auth.hasAnyRole(setOf(RoleCode.MERCHANT_OWNER, RoleCode.MERCHANT_STAFF)) || !auth.hasMerchantScope(merchantId))
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant scope is required.")
    }
    private fun actor(auth: Authentication, thread: BargainingThreadRecord): BargainingActorType {
        if (auth.name == thread.customerId && auth.hasAnyRole(setOf(RoleCode.CUSTOMER))) return BargainingActorType.CUSTOMER
        merchantAccess(auth, thread.merchantId)
        return BargainingActorType.MERCHANT
    }
    private fun retryKey(value: String?) = value?.let { ApiInputPolicy.requiredShortText(it, "idempotencyKey", 128) }
    private fun fingerprint(values: List<Any?>) = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(values)).toHexString()
    private fun replay(actorId: String, key: String?, fingerprint: String): BargainingThreadDto? {
        if (key == null) return null
        val previous = offers.findByActorUserIdAndIdempotencyKey(actorId, key) ?: return null
        require(previous.requestFingerprint == fingerprint) { "Idempotency key was already used for a different bargaining action." }
        return snapshot(threads.findById(previous.threadId).orElseThrow())
    }
}
