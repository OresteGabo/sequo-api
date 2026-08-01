package dev.orestegabo.sequo_api.domain.order

import dev.orestegabo.sequo_api.domain.payment.MoovAfricaPaymentMethod
import dev.orestegabo.sequo_api.domain.payment.PaymentProcessor
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationRequest
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationResult
import dev.orestegabo.sequo_api.domain.payment.PaymentValidationStatus
import dev.orestegabo.sequo_api.domain.payment.SequoPaymentProviders
import dev.orestegabo.sequo_api.domain.payment.YasTogoPaymentMethod
import dev.orestegabo.sequo_api.domain.pricing.DeliveryPricingService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderProcessingTest {

    @Test
    fun regularOrderRunsSharedFlowAndKeepsStandardDeliveryPricing() {
        val confirmationSender = RecordingConfirmationSender()
        val processor = RegularOrderProcessor(
            paymentProcessor = validatedPaymentProcessor(),
            pricingService = DeliveryPricingService(),
            confirmationSender = confirmationSender,
        )

        val result = processor.process(sampleRequest(serviceLevel = OrderServiceLevel.Regular))

        assertTrue(result is OrderProcessingResult.AcceptedForFulfillment)
        assertEquals(
            listOf(
                OrderFlowStage.ValidateOrder,
                OrderFlowStage.CalculateTotal,
                OrderFlowStage.ProcessPayment,
                OrderFlowStage.SendConfirmation,
                OrderFlowStage.SendToMerchant,
            ),
            result.events.map { it.stage },
        )
        assertEquals(5000, result.order.pricing.itemSubtotalCfa)
        assertEquals(500, result.order.pricing.delivery.finalDeliveryFeeCfa)
        assertEquals(0, result.order.pricing.delivery.subscriptionDiscountCfa)
        assertEquals(5500, result.order.pricing.totalCfa)
        assertEquals(FulfillmentPriority.Standard, result.order.fulfillmentPlan.priority)
        assertEquals(1, confirmationSender.sentOrders.size)
    }

    @Test
    fun primeMultiYearOrderAppliesDeeperDiscountAndPriorityFulfillment() {
        val processor = PrimeOrderProcessor(
            paymentProcessor = validatedPaymentProcessor(),
            pricingService = DeliveryPricingService(),
        )

        val result = processor.process(
            sampleRequest(
                serviceLevel = OrderServiceLevel.PrimeMultiYear,
                deliveryDistanceKm = 8.4,
            ),
        )

        assertTrue(result is OrderProcessingResult.AcceptedForFulfillment)
        assertEquals(800, result.order.pricing.delivery.baseFeeCfa)
        assertEquals(200, result.order.pricing.delivery.subscriptionDiscountCfa)
        assertEquals(600, result.order.pricing.delivery.finalDeliveryFeeCfa)
        assertEquals(5600, result.order.pricing.totalCfa)
        assertEquals(FulfillmentPriority.Prime, result.order.fulfillmentPlan.priority)
    }

    @Test
    fun pendingPaymentStopsBeforeConfirmationAndMerchantHandoff() {
        val confirmationSender = RecordingConfirmationSender()
        val processor = RegularOrderProcessor(
            paymentProcessor = paymentProcessorReturning(PaymentValidationResult.pending("Provider callback not received yet.")),
            pricingService = DeliveryPricingService(),
            confirmationSender = confirmationSender,
        )

        val result = processor.process(sampleRequest(serviceLevel = OrderServiceLevel.Regular))

        assertTrue(result is OrderProcessingResult.AwaitingPaymentValidation)
        assertEquals(
            listOf(
                OrderFlowStage.ValidateOrder,
                OrderFlowStage.CalculateTotal,
                OrderFlowStage.ProcessPayment,
            ),
            result.events.map { it.stage },
        )
        assertEquals(OrderFlowStepStatus.Waiting, result.events.last().status)
        assertEquals(0, confirmationSender.sentOrders.size)
    }

    @Test
    fun rejectsGalleryUploadForSellerSpecificProductsBeforePayment() {
        val processor = RegularOrderProcessor(
            paymentProcessor = PaymentProcessor(
                paymentMethods = listOf(
                    YasTogoPaymentMethod(validateHandler = { error("Payment must not run when validation fails.") }),
                ),
            ),
            pricingService = DeliveryPricingService(),
        )

        val result = processor.process(
            sampleRequest(
                serviceLevel = OrderServiceLevel.Regular,
                lines = listOf(sampleLine(photoEvidence = ProductPhotoEvidence.GalleryUpload)),
            ),
        )

        assertTrue(result is OrderProcessingResult.Rejected)
        assertEquals("invalid_photo_evidence", result.reason.code)
        assertEquals(listOf(OrderFlowStage.ValidateOrder), result.events.map { it.stage })
    }

    @Test
    fun allowsGenericCatalogImageOnlyForGenericSealedItems() {
        val processor = RegularOrderProcessor(
            paymentProcessor = validatedPaymentProcessor(),
            pricingService = DeliveryPricingService(),
        )

        val result = processor.process(
            sampleRequest(
                serviceLevel = OrderServiceLevel.Regular,
                lines = listOf(
                    sampleLine(
                        category = OrderProductCategory.GenericSealedItem,
                        photoEvidence = ProductPhotoEvidence.GenericCatalogImage,
                    ),
                ),
            ),
        )

        assertTrue(result is OrderProcessingResult.AcceptedForFulfillment)
        assertEquals(5500, result.order.pricing.totalCfa)
    }

    @Test
    fun factoryRoutesServiceLevelsToTheRightProcessor() {
        val factory = OrderProcessorFactory(
            regularOrderProcessor = RegularOrderProcessor(
                paymentProcessor = validatedPaymentProcessor(),
                pricingService = DeliveryPricingService(),
            ),
            primeOrderProcessor = PrimeOrderProcessor(
                paymentProcessor = validatedPaymentProcessor(),
                pricingService = DeliveryPricingService(),
            ),
        )

        val result = factory.processorFor(OrderServiceLevel.PrimeMonthly)
            .process(sampleRequest(serviceLevel = OrderServiceLevel.PrimeMonthly))

        assertTrue(result is OrderProcessingResult.AcceptedForFulfillment)
        assertEquals(FulfillmentPriority.Prime, result.order.fulfillmentPlan.priority)
        assertEquals(75, result.order.pricing.delivery.subscriptionDiscountCfa)
    }

    private fun sampleRequest(
        serviceLevel: OrderServiceLevel,
        lines: List<OrderLineRequest> = listOf(sampleLine()),
        deliveryDistanceKm: Double = 5.6,
    ): OrderProcessingRequest =
        OrderProcessingRequest(
            checkoutId = "checkout-2419",
            customerId = "customer-1",
            serviceLevel = serviceLevel,
            route = OrderRoute.FastDelivery,
            lines = lines,
            deliveryDistanceKm = deliveryDistanceKm,
            referralCreditCfa = 0,
            paymentProvider = SequoPaymentProviders.YasTogo,
            paymentReference = "yas-provider-reference",
        )

    private fun sampleLine(
        category: OrderProductCategory = OrderProductCategory.Food,
        photoEvidence: ProductPhotoEvidence = ProductPhotoEvidence.LiveCameraCapture(1722000000000),
    ): OrderLineRequest =
        OrderLineRequest(
            productId = "attieke-1",
            sellerId = "seller-ramatou",
            sellerName = "Chez Ramatou Attieke",
            productName = "Attieke poisson braise",
            category = category,
            quantity = 2,
            unitPriceCfa = 2500,
            photoEvidence = photoEvidence,
        )

    private fun validatedPaymentProcessor(): PaymentProcessor =
        paymentProcessorReturning { request -> PaymentValidationResult.validated(request.paymentReference) }

    private fun paymentProcessorReturning(result: PaymentValidationResult): PaymentProcessor =
        paymentProcessorReturning { result }

    private fun paymentProcessorReturning(
        validationHandler: (PaymentValidationRequest) -> PaymentValidationResult,
    ): PaymentProcessor =
        PaymentProcessor(
            paymentMethods = listOf(
                YasTogoPaymentMethod(validateHandler = validationHandler),
                MoovAfricaPaymentMethod(validateHandler = validationHandler),
            ),
        )

    private class RecordingConfirmationSender : OrderConfirmationSender() {
        val sentOrders = mutableListOf<ProcessedOrder>()

        override fun send(order: ProcessedOrder): OrderConfirmation {
            sentOrders += order
            return OrderConfirmation(
                title = "Recorded",
                message = "Recorded confirmation for ${order.checkoutId}.",
            )
        }
    }
}
