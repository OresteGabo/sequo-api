package dev.orestegabo.sequo_api.domain.admin

import dev.orestegabo.sequo.reference.PublicReferenceKind
import dev.orestegabo.sequo.reference.PublicReferenceKind.*
import dev.orestegabo.sequo_api.domain.reference.PublicReferenceRegistry
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import java.lang.reflect.Modifier
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionController
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionOverrideSnapshot
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionAuditSnapshot
import dev.orestegabo.sequo_api.domain.commission.MerchantCommissionPolicyState
import dev.orestegabo.sequo_api.domain.store.AdminMerchantStoreController
import dev.orestegabo.sequo_api.domain.store.MerchantStoreState

/** Wire-only additions to explicitly named, already-authorized admin response DTOs.
 * Machine IDs, retry keys, provider references and credentials are never replaced.
 * Unknown types (including errors, entities and request DTOs) are not inspected.
 */
@RestControllerAdvice(basePackages = ["dev.orestegabo.sequo_api.domain.admin"],
    assignableTypes = [MerchantCommissionController::class, AdminMerchantStoreController::class])
class AdminPublicReferenceAdvice(private val registry: PublicReferenceRegistry) : ResponseBodyAdvice<Any> {
    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>) =
        returnType.containingClass.packageName == "dev.orestegabo.sequo_api.domain.admin" ||
            returnType.containingClass == MerchantCommissionController::class.java ||
            returnType.containingClass == AdminMerchantStoreController::class.java

    override fun beforeBodyWrite(body: Any?, returnType: MethodParameter, selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>, request: ServerHttpRequest,
        response: ServerHttpResponse): Any? {
        // In particular, never allocate references from denied requests or error/request echoes.
        if (response is ServletServerHttpResponse && response.servletResponse.status !in 200..299) return body
        return projectAdminReferences(body, registry::reference)
    }
}

private data class AdminReferenceField(val source: String, val target: String, val kind: PublicReferenceKind)
private fun primary(source: String, kind: PublicReferenceKind) = AdminReferenceField(source, "publicReference", kind)
private fun relation(source: String, target: String, kind: PublicReferenceKind) = AdminReferenceField(source, target, kind)
private val adminReferenceFields: Map<Class<*>, List<AdminReferenceField>> = mapOf(
    MerchantCommissionController.MerchantCommissionRateResponse::class.java to listOf(relation("merchantId", "merchantReference", MERCHANT)),
    MerchantCommissionPolicyState::class.java to listOf(relation("merchantId", "merchantReference", MERCHANT)),
    MerchantCommissionOverrideSnapshot::class.java to listOf(relation("merchantId", "merchantReference", MERCHANT), relation("updatedByUserId", "actorReference", USER)),
    MerchantCommissionAuditSnapshot::class.java to listOf(relation("actorUserId", "actorReference", USER)),
    MerchantStoreState::class.java to listOf(relation("merchantId", "merchantReference", MERCHANT)),
    AdminUserDto::class.java to listOf(primary("id", USER)),
    AdminMerchantDto::class.java to listOf(primary("id", MERCHANT), relation("ownerUserId", "ownerReference", USER)),
    AdminOrderDto::class.java to listOf(primary("id", ORDER), relation("customerId", "customerReference", USER)),
    AdminDeliveryMissionDto::class.java to listOf(primary("id", DELIVERY), relation("orderId", "orderReference", ORDER), relation("merchantSubOrderId", "merchantOrderReference", MERCHANT_ORDER), relation("courierId", "courierReference", COURIER)),
    AdminPaymentCheckoutDto::class.java to listOf(primary("checkoutId", CHECKOUT), relation("orderId", "orderReference", ORDER)),
    AdminMerchantPayoutDto::class.java to listOf(primary("id", FINANCIAL), relation("merchantId", "merchantReference", MERCHANT), relation("orderId", "orderReference", ORDER), relation("sourceOrderItemId", "merchantOrderReference", MERCHANT_ORDER)),
    AdminPaymentWebhookDto::class.java to listOf(relation("checkoutId", "checkoutReference", CHECKOUT)),
    AdminCommissionOverrideDto::class.java to listOf(relation("merchantId", "merchantReference", MERCHANT), relation("updatedByUserId", "actorReference", USER)),
    AdminReturnDto::class.java to listOf(primary("id", RETURN), relation("orderId", "orderReference", ORDER), relation("relayPointId", "relayReference", RELAY)),
    AdminRelayParcelDto::class.java to listOf(primary("id", PARCEL), relation("orderId", "orderReference", ORDER), relation("relayPointId", "relayReference", RELAY), relation("lockerId", "lockerReference", LOCKER), relation("deliveryMissionId", "deliveryReference", DELIVERY), relation("returnId", "returnReference", RETURN)),
    AdminOrderLineDto::class.java to listOf(relation("productId", "productReference", PRODUCT), relation("sellerId", "merchantReference", MERCHANT)),
    AdminOrderHistoryDto::class.java to listOf(relation("actorUserId", "actorReference", USER)),
    AdminCourierDto::class.java to listOf(primary("id", COURIER), relation("userId", "userReference", USER)),
    AdminRelaySiteDto::class.java to listOf(primary("id", RELAY), relation("operatorUserId", "operatorReference", USER)),
    AdminLogicalSlotDto::class.java to listOf(primary("id", LOCKER), relation("relayPointId", "relayReference", RELAY)),
    AdminPartyChangeDto::class.java to listOf(relation("actorUserId", "actorReference", USER)),
    AdminAccessUserDto::class.java to listOf(primary("id", USER)),
    AdminMembershipDto::class.java to listOf(relation("userId", "userReference", USER), relation("merchantId", "merchantReference", MERCHANT)),
    AdminMerchantMembershipsDto::class.java to listOf(relation("merchantId", "merchantReference", MERCHANT)),
    AdminEnrollmentMerchantDto::class.java to listOf(primary("id", MERCHANT)),
    AdminEnrollmentCourierDto::class.java to listOf(primary("id", COURIER)),
    AdminEnrollmentOperationDto::class.java to listOf(relation("userId", "userReference", USER), relation("actorUserId", "actorReference", USER)),
    AdminCatalogMediaProduct::class.java to listOf(primary("id", PRODUCT), relation("merchantId", "merchantReference", MERCHANT)),
    RelayAdministrationOperationDto::class.java to listOf(relation("relayPointId", "relayReference", RELAY), relation("actorUserId", "actorReference", USER)),
    AdminNegotiationThreadDto::class.java to listOf(primary("id", BARGAINING), relation("customerId", "customerReference", USER), relation("merchantId", "merchantReference", MERCHANT), relation("productId", "productReference", PRODUCT)),
    AdminNegotiationOfferDto::class.java to listOf(relation("actorUserId", "actorReference", USER)),
    AdminDeletionRequestDto::class.java to listOf(relation("userId", "userReference", USER)),
    PausedCourierAlert::class.java to listOf(relation("courierId", "courierReference", COURIER)),
    DeliveryMissionAlert::class.java to listOf(primary("missionId", DELIVERY), relation("orderId", "orderReference", ORDER), relation("courierId", "courierReference", COURIER)),
    MerchantSubOrderAlert::class.java to listOf(primary("subOrderId", MERCHANT_ORDER), relation("orderId", "orderReference", ORDER), relation("merchantId", "merchantReference", MERCHANT)),
    RelayParcelAlert::class.java to listOf(primary("parcelId", PARCEL), relation("relayPointId", "relayReference", RELAY), relation("orderId", "orderReference", ORDER), relation("returnId", "returnReference", RETURN)),
    // Only these wrappers may be traversed. No object-wide *Id/string heuristics.
    AdminDeliveryDto::class.java to emptyList(), AdminPaymentsDto::class.java to emptyList(),
    AdminPaymentCheckoutDetailDto::class.java to emptyList(), AdminCommissionsDto::class.java to emptyList(),
    AdminRelayDto::class.java to emptyList(), AdminOrderDetailDto::class.java to emptyList(),
    AdminCourierDetailDto::class.java to emptyList(), AdminRelaySiteDetailDto::class.java to emptyList(),
    AdminPartyMutationDto::class.java to emptyList(), AdminUserAccessDto::class.java to emptyList(),
    AdminEnrollmentUserDto::class.java to emptyList(), AdminEnrollmentMutationDto::class.java to emptyList(),
    AdminCatalogMediaDto::class.java to emptyList(), RelaySetupSnapshot::class.java to emptyList(),
    RelaySetupMutation::class.java to emptyList(), AdminDeliveryPriceState::class.java to emptyList(),
    AdminOperationsSnapshot::class.java to emptyList(), DeliveryCapacitySnapshot::class.java to emptyList(),
    MerchantFulfillmentMonitoringSnapshot::class.java to emptyList(), RelayOperationsSnapshot::class.java to emptyList(),
)

internal fun projectAdminReferences(value: Any?, reference: (PublicReferenceKind, String) -> String): Any? = when (value) {
    null -> null
    is Collection<*> -> value.map { projectAdminReferences(it, reference) }
    is Map<*, *> -> value.mapValues { projectAdminReferences(it.value, reference) }
    else -> {
        val declared = adminReferenceFields[value.javaClass]
        if (declared == null) value else {
            val fields = value.javaClass.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
                .associate { field -> field.trySetAccessible(); field.name to field.get(value) }
            val result = fields.mapValuesTo(linkedMapOf()) { projectAdminReferences(it.value, reference) }
            declared.forEach { field ->
                result[field.target] = (fields[field.source] as? String)?.takeIf { it.isNotBlank() }?.let { reference(field.kind, it) }
            }
            if (value is AdminPartyChangeDto && value.action in setOf("ASSIGN_OPERATOR", "REMOVE_OPERATOR")) {
                result["beforeReference"] = value.beforeValue?.takeIf { it.isNotBlank() }?.let { reference(USER, it) }
                result["afterReference"] = value.afterValue?.takeIf { it.isNotBlank() }?.let { reference(USER, it) }
            }
            if (value is AdminEnrollmentOperationDto) result["entityReference"] = reference(
                if (value.kind == AdminEnrollmentKind.MERCHANT) MERCHANT else COURIER, value.entityId)
            result
        }
    }
}
