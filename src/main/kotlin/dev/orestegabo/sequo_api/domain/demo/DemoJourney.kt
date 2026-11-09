package dev.orestegabo.sequo_api.domain.demo

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import dev.orestegabo.sequo_api.domain.delivery.*
import dev.orestegabo.sequo_api.domain.order.*
import dev.orestegabo.sequo_api.domain.payment.*
import dev.orestegabo.sequo_api.domain.settlement.SettlementCourierRecordRepository
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.http.*
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
@Profile("demo")
class DemoJourney(private val environment: DemoEnvironment, private val checkouts: PendingPaymentCheckoutRepository,
    private val pending: PendingPaymentCheckoutService, private val receipts: PaymentWebhookEventRepository,
    private val orders: CustomerOrderRecordRepository, private val merchants: MerchantFulfillmentService,
    private val missions: DeliveryMissionRepository, private val delivery: DeliveryMissionService,
    private val assignments: GuardedDeliveryAssignmentService, private val couriers: SettlementCourierRecordRepository,
    private val pins: DeliveryPinService, private val lifecycle: OrderDeliveryLifecycleService,
    transactions: PlatformTransactionManager) {
    private val transaction=TransactionTemplate(transactions).apply { propagationBehavior=TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    private val gate=ReentrantLock()
    data class Rider(val id:String,val name:String)
    data class State(val stage:String,val title:String,val nextAction:String?,val nextLabel:String?,
        val riderName:String?=null,val riders:List<Rider> = emptyList(),val note:String="DEMO — no money, messages or physical dispatch. Each button simulates the named actor.")
    private val stages=listOf("PAY","ACCEPT","PREPARE","PACK","ASSIGN","RIDER_ACCEPT","PICKUP","DELIVER","DONE")
    private val labels=listOf("Simulate payment","Seller accepts","Start preparation","Mark ready for pickup","Assign test rider","Rider accepts mission","Rider picks up package","Confirm demo delivery")
    private val titles=listOf("Waiting for demo payment","Payment confirmed","Seller accepted","Preparing your order","Ready for pickup","Test rider assigned","Rider on the way to the shop","Package picked up","Delivered")
    fun state(customer:String,id:String):State {
        val checkout=owned(customer,id)
        val order=orders.findByCheckoutId(id)
        val parts=order?.let { merchants.listForOrder(it.id) }.orEmpty()
        val deliveries=order?.let { missions.findByOrderIdOrderByCreatedAtAsc(it.id) }.orEmpty()
        val stage=when {
            checkout.status==PendingPaymentCheckoutStatus.AWAITING_WEBHOOK -> "PAY"
            checkout.status!=PendingPaymentCheckoutStatus.COMPLETED || parts.isEmpty() -> throw IllegalArgumentException("This checkout cannot continue. Create a new demo order.")
            parts.any { it.status in setOf(MerchantSubOrderStatus.CANCELLED,MerchantSubOrderStatus.REJECTED) } -> throw IllegalArgumentException("This order was cancelled or rejected.")
            parts.any { it.status==MerchantSubOrderStatus.MERCHANT_PENDING } -> "ACCEPT"
            parts.any { it.status==MerchantSubOrderStatus.ACCEPTED } -> "PREPARE"
            parts.any { it.status==MerchantSubOrderStatus.PREPARING } -> "PACK"
            deliveries.size < parts.size || deliveries.any { it.courierId==null || it.status==DeliveryMissionRecordStatus.CREATED } -> "ASSIGN"
            deliveries.any { it.status==DeliveryMissionRecordStatus.OFFERED_TO_COURIER } -> "RIDER_ACCEPT"
            deliveries.any { it.status==DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER } -> "PICKUP"
            deliveries.any { it.status==DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER } -> "DELIVER"
            deliveries.all { it.status==DeliveryMissionRecordStatus.DELIVERED_TO_CUSTOMER } -> "DONE"
            else -> throw IllegalArgumentException("This mission needs review in the normal operations controls.")
        }
        val i=stages.indexOf(stage)
        val riderNames=deliveries.mapNotNull { it.courierId }.distinct().map { rider(it).name }.joinToString(" / ").takeIf(String::isNotBlank)
        return State(stage,titles[i],stage.takeUnless { it=="DONE" },labels.getOrNull(i),riderNames,DemoEnvironment.RIDERS.map(::rider))
    }
    fun advance(customer:String,id:String,action:String,riderId:String?):State = gate.withLock {
        val before=state(customer,id)
        require(action in stages.dropLast(1)) { "Unknown demo step." }
        if (action != before.stage) {
            require(stages.indexOf(action) < stages.indexOf(before.stage)) { "Refresh the order and complete the next step first." }
            return@withLock before // a repeated request never advances a second step
        }
        val checkout=owned(customer,id)
        if(action=="PAY") {
            val now=Instant.now(); val eventId="DEMO:$id"; val reference="DEMO:$id"; val provider=PaymentProviderId(checkout.paymentProvider)
            val event=PaymentWebhookAcceptedEvent(provider,eventId,id,checkout.paymentReference,reference,checkout.amountCfa,PaymentWebhookProviderStatus.VALIDATED,now,now)
            transaction.executeWithoutResult {
                if(!receipts.existsById("${provider.value}:$eventId")) receipts.saveAndFlush(PaymentWebhookEventRecord(id="${provider.value}:$eventId",provider=provider.value,eventId=eventId,checkoutId=id,paymentReference=checkout.paymentReference,providerReference=reference,amountCfa=checkout.amountCfa,
                    status=PaymentWebhookProviderStatus.VALIDATED,occurredAt=now,payloadHash=MessageDigest.getInstance("SHA-256").digest("DEMO|$id|${checkout.amountCfa}".toByteArray()).joinToString(""){"%02x".format(it)},receivedAt=now))
            }
            check(pending.reconcile(event) is PaymentReconciliationResult.Fulfilled) { "Demo payment reconciliation needs review." }
            return@withLock state(customer,id)
        }
        val order=requireNotNull(orders.findByCheckoutId(id)); val parts=merchants.listForOrder(order.id)
        when(action) {
            "ACCEPT" -> parts.filter { it.status==MerchantSubOrderStatus.MERCHANT_PENDING }.forEach { success(merchants.accept(it.id,it.merchantId)) }
            "PREPARE" -> parts.filter { it.status==MerchantSubOrderStatus.ACCEPTED }.forEach { success(merchants.startPreparation(it.id,it.merchantId)) }
            "PACK" -> parts.filter { it.status==MerchantSubOrderStatus.PREPARING }.forEach { success(merchants.markPacked(it.id,it.merchantId,1)) }
            "ASSIGN" -> {
                require(riderId in DemoEnvironment.RIDERS) { "Choose a test rider." }
                require(parts.size<=4) { "Use up to four shops per demo delivery, one test rider per package." }
                for((i,part) in parts.withIndex()) {
                    val fee=order.deliveryFeeCfa/parts.size + if(i < order.deliveryFeeCfa % parts.size) 1 else 0
                    val mission=missions.findByMerchantSubOrderId(part.id)?.let { delivery.get(it.id!!) }
                        ?: delivery.create(CreateDeliveryMissionCommand("DEMO-${UUID.nameUUIDFromBytes(part.id.toByteArray())}",order.id,part.id,DeliveryMissionRecordMode.EXPRESS,DeliveryMissionRecordDestination.CUSTOMER_ADDRESS,
                            customerDeliveryFeeCfa=fee,courierFeeCfa=fee))
                    val courier=mission.courierId ?: DemoEnvironment.RIDERS[(DemoEnvironment.RIDERS.indexOf(riderId)+i)%4]
                    if(mission.courierId==null) success(assignments.assign(mission.id,courier,DemoEnvironment.ADMIN,"DEMO assignment requested by $customer","demo-assign-${mission.id}"))
                    if(delivery.get(mission.id)?.status==DeliveryMissionRecordStatus.CREATED) success(assignments.offer(mission.id,DemoEnvironment.ADMIN,"DEMO rider offer","demo-offer-${mission.id}"))
                }
            }
            else -> for(m in missions.findByOrderIdOrderByCreatedAtAsc(order.id)) {
                val mid=requireNotNull(m.id); val actor=requireNotNull(couriers.findById(requireNotNull(m.courierId)).orElseThrow().userId)
                when(action) {
                    "RIDER_ACCEPT" -> if(m.status==DeliveryMissionRecordStatus.OFFERED_TO_COURIER) success(delivery.acceptForCourier(mid,actor))
                    "PICKUP" -> {
                        if(m.status==DeliveryMissionRecordStatus.ACCEPTED_BY_COURIER) success(delivery.transitionIdempotent(mid,actor,DeliveryMissionEvent.CourierPicksUpFromSeller,"demo-pickup-$mid",proof="DEMO synthetic seller handoff; requested by $customer"))
                        parts.firstOrNull { it.id==m.merchantSubOrderId && it.status==MerchantSubOrderStatus.PACKED_READY }?.let { success(merchants.confirmCourierHandoff(it.id,it.merchantId)) }
                    }
                    "DELIVER" -> if(m.status==DeliveryMissionRecordStatus.PICKED_UP_FROM_SELLER) {
                        success(delivery.validateDirectDeliveryAttempt(mid,actor))
                        val pin=(100000+java.security.SecureRandom().nextInt(900000)).toString()
                        pins.create(CreateDeliveryPinCommand(mid,pin,Instant.now().plus(Duration.ofMinutes(5))))
                        check(pins.verify(mid,pin) is DeliveryPinResult.Accepted)
                        val done=success(delivery.transitionIdempotent(mid,actor,DeliveryMissionEvent.CourierDeliversToCustomer,"demo-deliver-$mid",proof="DEMO synthetic customer handoff; requested by $customer",deliveryPinValidated=true))
                        lifecycle.markDeliveredFromMission(done,actor)
                    }
                }
            }
        }
        state(customer,id)
    }
    private fun rider(id:String)=Rider(id,listOf("Ami · moto","Kossi · salarie","Sena · moto","Yao · moto").getOrElse(DemoEnvironment.RIDERS.indexOf(id)){"Test rider"})
    private fun owned(customer:String,id:String):PendingPaymentCheckoutRecord {
        if(customer!=DemoEnvironment.CUSTOMER) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val checkout=checkouts.findByCheckoutIdAndCustomerId(id,customer) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val request=ObjectMapper().readPendingRequest(checkout.requestPayload)
        require(request.lines.isNotEmpty() && request.lines.all { it.productId.startsWith("demo-v1-") } && request.relayDestination==null) { "Only your demo catalog orders for home delivery can use these controls." }
        return checkout
    }
    private fun success(r:MerchantFulfillmentServiceResult) { require(r is MerchantFulfillmentServiceResult.Success) { (r as MerchantFulfillmentServiceResult.Rejected).message } }
    private fun success(r:DeliveryMissionServiceResult):DeliveryMissionSnapshot { require(r is DeliveryMissionServiceResult.Success) { (r as DeliveryMissionServiceResult.Rejected).message }; return r.mission }
}

@RestController
@Profile("demo")
class DemoController(private val environment:DemoEnvironment,private val journey:DemoJourney,private val auth:AuthService,private val limiter:InMemoryRateLimiter) {
    data class Login(val accessCode:String,val deviceId:String)
    data class Step(val action:String,val riderId:String?=null)
    @PostMapping("/api/auth/demo/session")
    fun login(@RequestBody body:Login):ResponseEntity<Any> {
        val limit=limiter.consume("demo.login","all",RateLimitRule(12,Duration.ofMinutes(10)))
        if(!limit.allowed) return ResponseEntity.status(429).header("Retry-After",limit.retryAfterSeconds.toString()).build()
        if(!environment.accepts(body.accessCode)) return ResponseEntity.status(401).build()
        require(body.deviceId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        val tokens=auth.issueTokensForExistingUser(DemoEnvironment.CUSTOMER,AuthProvider.EMAIL,DeviceBinding(body.deviceId,AppSource.SEQUO_APP,null,deviceLabel="Sequo Demo")) ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens)
    }
    @GetMapping("/api/demo/checkouts/{id}")
    fun state(@AuthenticationPrincipal customer:String,@PathVariable id:String)=journey.state(customer,id)
    @PostMapping("/api/demo/checkouts/{id}/advance")
    fun step(@AuthenticationPrincipal customer:String,@PathVariable id:String,@RequestBody body:Step)=journey.advance(customer,id,body.action,body.riderId)
    @GetMapping("/api/demo/images/{id}")
    fun image(@PathVariable id:String):ResponseEntity<Any> {
        if(!id.matches(Regex("demo-v1-[a-z]+-[0-9]{2}"))) return ResponseEntity.notFound().build()
        val resource=ClassPathResource("demo/$id.jpg")
        return if(resource.exists()) ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.maxAge(Duration.ofDays(7))).body(resource) else ResponseEntity.notFound().build()
    }
}
