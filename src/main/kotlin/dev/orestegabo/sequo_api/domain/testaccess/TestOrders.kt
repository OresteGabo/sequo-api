package dev.orestegabo.sequo_api.domain.testaccess

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

const val TEST_ORDER_REFERENCE = "TEST_NO_FUNDS:"
fun isNoFundsTestOrder(reference:String)=reference.startsWith(TEST_ORDER_REFERENCE)
fun isSeededTestRider(id:String)=id in (1..4).map{"demo-v1-rider-$it"}

@Service
class TestOrderPolicy(@Value("\${sequo.testing.orders.enabled:false}") val enabled:Boolean) {
    private val shops=setOf("bistro","saveurs","grains","marche","boissons","mode","petits","pas","tech","maison","tissus","essentiels")
    fun testProduct(id:String,merchant:String?):Boolean = shops.any {id.matches(Regex("demo-v1-$it-[0-9]{2}")) && merchant=="demo-v1-shop-$it"}
    fun processor(pricing:DeliveryPricingService):RegularOrderProcessor {
        check(enabled)
        val pending:(PaymentValidationRequest)->PaymentValidationResult = {PaymentValidationResult.pending("TEST — no wallet call and no money moved.")}
        return RegularOrderProcessor(PaymentProcessor(listOf(YasTogoPaymentMethod(validateHandler=pending),MoovAfricaPaymentMethod(validateHandler=pending))),pricing,allowDemoIllustrations=true)
    }
}

@Service
class TestOrderService(private val policy:TestOrderPolicy,private val checkouts:PendingPaymentCheckoutRepository,
    private val orders:CustomerOrderRecordRepository,private val fulfillment:OrderFulfillmentPersistenceService,
    private val merchant:MerchantFulfillmentService,private val missions:DeliveryMissionRepository,
    private val delivery:DeliveryMissionService,private val assignments:GuardedDeliveryAssignmentService,
    private val summaries:CustomerCartCheckoutService) {
    private val mapper=ObjectMapper()
    @Transactional
    fun confirm(customer:String,id:String):CustomerCheckoutSummary {
        val checkout=ownedCheckout(customer,id,true)
        if(checkout.status==PendingPaymentCheckoutStatus.COMPLETED)return requireNotNull(summaries.get(customer,id))
        require(checkout.status==PendingPaymentCheckoutStatus.AWAITING_WEBHOOK){"This test checkout cannot be confirmed."}
        val request=mapper.readPendingRequest(checkout.requestPayload)
        val pricing=mapper.readPricingSnapshot(checkout.pricingPayload)
        // Explicit test evidence only. Never create a provider webhook receipt or a real payment claim.
        fulfillment.persistAcceptedOrder(request,request.toAcceptedForFulfillment(pricing,TEST_ORDER_REFERENCE+id),Instant.now())
        checkout.status=PendingPaymentCheckoutStatus.COMPLETED;checkout.providerReference=TEST_ORDER_REFERENCE+id
        checkout.updatedAt=Instant.now();checkout.completedAt=checkout.updatedAt;checkouts.saveAndFlush(checkout)
        return requireNotNull(summaries.get(customer,id))
    }
    data class Rider(val id:String,val name:String)
    data class Dispatch(val ready:Boolean,val assigned:Boolean,val message:String,val riders:List<Rider>)
    private val riders=listOf("Ami","Kossi","Sena","Yao").mapIndexed{i,n->Rider("demo-v1-rider-${i+1}","[TEST] $n")}
    @Transactional(readOnly=true)
    fun state(customer:String,id:String):Dispatch {
        val checkout=ownedCheckout(customer,id)
        val order=orders.findByCheckoutId(checkout.checkoutId) ?: throw IllegalArgumentException("Confirm test payment first.")
        val parts=merchant.listForOrder(order.id)
        val assigned=missions.findByOrderIdOrderByCreatedAtAsc(order.id).any {it.courierId!=null}
        val ready=parts.size==1 && parts.all{it.status==MerchantSubOrderStatus.PACKED_READY} && !assigned
        return Dispatch(ready,assigned,when {order.orderStatus==CustomerOrderStatus.DELIVERED->"Test journey complete. No money was moved.";assigned->"Test rider assigned. Continue in SequoRider.";parts.size!=1->"Use one shop for this guided test. Multi-shop consolidation uses normal operations.";ready->"Package ready. Choose a test rider.";else->"Waiting for the vendor to mark the package ready."},riders)
    }
    @Transactional
    fun dispatch(customer:String,id:String,rider:String):Dispatch {
        require(isSeededTestRider(rider)){"Choose a seeded test rider."}
        val checkout=ownedCheckout(customer,id,true)
        val order=orders.findByCheckoutId(checkout.checkoutId) ?: throw IllegalArgumentException("Confirm test payment first.")
        val parts=merchant.listForOrder(order.id)
        require(parts.size==1 && !order.requiresConsolidation){"Use a single test shop for this guided dispatch."}
        val part=parts.single()
        val existing=missions.findByMerchantSubOrderId(part.id)
        if(existing?.courierId!=null){require(existing.courierId==rider){"A rider is already assigned."};return state(customer,id)}
        require(part.status==MerchantSubOrderStatus.PACKED_READY){"The vendor must mark the package ready first."}
        val request=mapper.readPendingRequest(checkout.requestPayload)
        val mission=existing?.let{requireNotNull(delivery.get(requireNotNull(it.id)))} ?: delivery.create(CreateDeliveryMissionCommand(
            "TEST-${UUID.nameUUIDFromBytes(part.id.toByteArray())}",order.id,part.id,DeliveryMissionRecordMode.EXPRESS,
            if(request.relayDestination==null)DeliveryMissionRecordDestination.CUSTOMER_ADDRESS else DeliveryMissionRecordDestination.RELAY_POINT,
            customerDeliveryFeeCfa=order.deliveryFeeCfa,courierFeeCfa=order.deliveryFeeCfa,relayDestination=request.relayDestination))
        fun success(r:DeliveryMissionServiceResult){require(r is DeliveryMissionServiceResult.Success){(r as DeliveryMissionServiceResult.Rejected).message}}
        success(assignments.assign(mission.id,rider,customer,"TEST_NO_FUNDS customer requested a seeded test rider","test-assign-${mission.id}"))
        success(assignments.offer(mission.id,customer,"TEST_NO_FUNDS customer offered test mission","test-offer-${mission.id}"))
        return state(customer,id)
    }
    private fun ownedCheckout(customer:String,id:String,lock:Boolean=false):PendingPaymentCheckoutRecord {
        if(!policy.enabled)throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val checkout=(if(lock)checkouts.lockForReconciliation(id) else checkouts.findById(id).orElse(null))
            ?.takeIf{it.customerId==customer && isNoFundsTestOrder(it.paymentReference)} ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val request=mapper.readPendingRequest(checkout.requestPayload)
        require(request.lines.isNotEmpty() && request.lines.all{policy.testProduct(it.productId,it.sellerId)}){"Only test products can use test confirmation."}
        require(request.relayDestination==null || request.relayDestination.id.startsWith("live-test-v1-hub-")){"Use a test relay hub."}
        return checkout
    }
}
@RestController
class TestOrderController(private val service:TestOrderService) {
    @ExceptionHandler(ResponseStatusException::class)
    fun unavailable(error:ResponseStatusException) = org.springframework.http.ResponseEntity.status(error.statusCode)
        .cacheControl(org.springframework.http.CacheControl.noStore()).body(mapOf("message" to "Test checkout is unavailable."))
    data class Assign(val riderId:String)
    @PostMapping("/api/orders/checkouts/{id}/test-payment")
    fun confirm(@AuthenticationPrincipal customer:String,@PathVariable id:String)=service.confirm(customer,id)
    @GetMapping("/api/orders/checkouts/{id}/test-dispatch")
    fun state(@AuthenticationPrincipal customer:String,@PathVariable id:String)=service.state(customer,id)
    @PostMapping("/api/orders/checkouts/{id}/test-dispatch")
    fun dispatch(@AuthenticationPrincipal customer:String,@PathVariable id:String,@RequestBody body:Assign)=service.dispatch(customer,id,body.riderId)
}
