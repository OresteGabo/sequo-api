# Rider actual local journey: persisted readback

Verified at 2026-11-08T22:37:57.816886+00:00 against loopback API port55840 and installed LOCAL_FIXTURE manifest. Business requests were GET only; temporary normal guarded admin and Ama sessions were logged out. No tokens, PINs, contact details, free-text proof or raw payment bodies were persisted.

Checks: 35 passed, 0 failed.

Parent UI observations: protected Senam restore; offered target accepted; blank pickup note rejected without advancing; entered test note accepted; wrong PIN rejected without advancing; private valid PIN accepted and Delivered shown. This GET-only record verifies resulting canonical state and actors; it does not claim mutation replay. Screenshot: rider-delivered.jpg.

## Canonical persisted state

{
  "mission": {
    "id": "58dc4a13-780a-4b50-a463-f27d0d3dccc7",
    "deliveryCode": "DLV-7960df07a5cc50cfb322525b",
    "orderId": "SQ-local-ops-v1-merchant-preparing",
    "merchantSubOrderId": "525417ed-38ef-439d-bd37-2944cbeca7c2",
    "courierId": "local-ops-v1-courier-senam",
    "deliveryMode": "EXPRESS",
    "status": "DELIVERED_TO_CUSTOMER",
    "destinationType": "CUSTOMER_ADDRESS",
    "assignmentWorkforceType": "Freelancer",
    "packageCount": 1,
    "customerDeliveryFeeCfa": 400,
    "courierFeeCfa": 400,
    "shortfallCfa": 0,
    "createdAt": "2026-11-08T22:06:40.058676Z",
    "assignedAt": "2026-11-08T22:06:40.169811Z",
    "acceptedAt": "2026-11-08T22:26:10.369468Z",
    "pickupAt": "2026-11-08T22:27:00.087217Z",
    "deliveredAt": "2026-11-08T22:27:18.720873Z",
    "updatedAt": "2026-11-08T22:27:18.720873Z",
    "pickupProofActorId": "local-ops-v1-rider-senam",
    "dropoffProofActorId": "local-ops-v1-rider-senam",
    "pickupProofRecorded": true,
    "dropoffProofRecorded": false
  },
  "adminOrder": {
    "id": "SQ-local-ops-v1-merchant-preparing",
    "customerId": "local-ops-v1-customer-ama",
    "orderStatus": "DELIVERED",
    "customerFacingStatus": "Delivered",
    "paymentStatus": "Validated",
    "paymentProvider": "yas_togo",
    "itemSubtotalCfa": 9600,
    "deliveryFeeCfa": 400,
    "totalCfa": 10000,
    "createdAt": "2026-11-08T11:00:27.397816Z",
    "updatedAt": "2026-11-08T22:27:18.720873Z"
  },
  "orderHistory": [
    {
      "id": "SQ-local-ops-v1-merchant-preparing:accepted",
      "eventType": "ACCEPTED_FOR_FULFILLMENT",
      "sourceType": "CHECKOUT",
      "sourceId": "local-ops-v1-merchant-preparing",
      "actorUserId": "local-ops-v1-customer-ama",
      "createdAt": "2026-11-08T11:00:27.397816Z"
    },
    {
      "id": "SQ-local-ops-v1-merchant-preparing:delivered:58dc4a13-780a-4b50-a463-f27d0d3dccc7",
      "eventType": "DELIVERED",
      "sourceType": "DELIVERY_MISSION",
      "sourceId": "58dc4a13-780a-4b50-a463-f27d0d3dccc7",
      "actorUserId": "local-ops-v1-rider-senam",
      "createdAt": "2026-11-08T22:27:18.720873Z"
    },
    {
      "id": "SQ-local-ops-v1-merchant-preparing:return-window:58dc4a13-780a-4b50-a463-f27d0d3dccc7",
      "eventType": "RETURN_WINDOW_OPENED",
      "sourceType": "DELIVERY_MISSION",
      "sourceId": "58dc4a13-780a-4b50-a463-f27d0d3dccc7",
      "actorUserId": "local-ops-v1-rider-senam",
      "createdAt": "2026-11-08T22:27:18.720873Z"
    }
  ],
  "customerOrder": {
    "id": "SQ-local-ops-v1-merchant-preparing",
    "checkoutId": "local-ops-v1-merchant-preparing",
    "customerId": "local-ops-v1-customer-ama",
    "orderStatus": "DELIVERED",
    "customerFacingStatus": "Delivered",
    "paymentStatus": "Validated",
    "itemSubtotalCfa": 9600,
    "deliveryFeeCfa": 400,
    "totalCfa": 10000,
    "deliveredAt": "2026-11-08T22:27:18.720873Z",
    "returnWindowEndsAt": "2026-11-11T22:27:18.720873Z",
    "updatedAt": "2026-11-08T22:27:18.720873Z"
  },
  "customerTracking": {
    "deliveryCode": "DLV-7960df07a5cc50cfb322525b",
    "orderId": "SQ-local-ops-v1-merchant-preparing",
    "status": "DELIVERED_TO_CUSTOMER",
    "deliveryMode": "EXPRESS",
    "destinationType": "CUSTOMER_ADDRESS",
    "courierAssigned": true,
    "currentStep": "Delivered",
    "nextStep": null,
    "terminal": true,
    "acceptedAt": "2026-11-08T22:26:10.369468Z",
    "pickupAt": "2026-11-08T22:27:00.087217Z",
    "deliveredAt": "2026-11-08T22:27:18.720873Z",
    "updatedAt": "2026-11-08T22:27:18.720873Z"
  },
  "paymentCheckout": {
    "checkoutId": "local-ops-v1-merchant-preparing",
    "paymentProvider": "yas_togo",
    "amountCfa": 10000,
    "currency": "XOF",
    "status": "COMPLETED",
    "createdAt": "2026-11-08T11:00:27.397816Z",
    "updatedAt": "2026-11-08T11:00:27.397816Z",
    "completedAt": "2026-11-08T11:00:27.397816Z",
    "orderId": "SQ-local-ops-v1-merchant-preparing",
    "orderStatus": "DELIVERED"
  },
  "paymentEvents": [
    {
      "id": "yas_togo:LOCAL_FIXTURE:local-ops-v1-merchant-preparing",
      "provider": "yas_togo",
      "status": "VALIDATED",
      "amountCfa": 10000,
      "reconciliationStatus": "FULFILLED",
      "reconciliationReason": "fulfilled",
      "receivedAt": "2026-11-08T11:00:27.397816Z",
      "reconciledAt": "2026-11-08T11:00:27.768817Z"
    }
  ],
  "deliveryLedgerSummary": {
    "count": 1,
    "courierPayableCreditCfa": 400,
    "shortfallDebitCfa": 0
  },
  "riderPayOverview": {
    "riderId": "local-ops-v1-courier-senam",
    "workforceType": "Freelancer",
    "payable": true,
    "availableBalanceCfa": 4000,
    "onHoldCfa": 0,
    "paidOrProcessingCfa": 0,
    "historyCount": 10,
    "earnedTotalCfa": 4000,
    "settledMissionCount": 10,
    "unverifiedTransferCfa": 0,
    "reviewRequiredCfa": 0,
    "transferStatus": "NOT_INTEGRATED"
  },
  "merchantPayouts": [
    {
      "id": "SQ-local-ops-v1-merchant-preparing:merchant-payout:525417ed-38ef-439d-bd37-2944cbeca7c2",
      "merchantId": "local-ops-v1-merchant-maison",
      "orderId": "SQ-local-ops-v1-merchant-preparing",
      "sourceOrderItemId": "525417ed-38ef-439d-bd37-2944cbeca7c2",
      "merchantNetCfa": 8160,
      "commissionCfa": 1440,
      "platformMarginCfa": 0,
      "status": "HeldReturnWindow",
      "workflowType": "DeliveryConfirmed",
      "activeReturnHold": true,
      "activeDisputeHold": false,
      "packageReceivedAt": "2026-11-08T22:27:18.720873Z",
      "payoutEligibleAt": "2026-11-11T22:27:18.720873Z",
      "payoutDueBy": "2026-11-15T22:27:18.720873Z"
    }
  ],
  "targetMerchantParts": [
    {
      "id": "525417ed-38ef-439d-bd37-2944cbeca7c2",
      "orderId": "SQ-local-ops-v1-merchant-preparing",
      "merchantId": "local-ops-v1-merchant-maison",
      "status": "PACKED_READY",
      "packageCount": 1,
      "packedReadyAt": "2026-11-08T22:00:38.802982Z",
      "handedOffAt": null,
      "updatedAt": "2026-11-08T22:00:38.802982Z"
    }
  ],
  "priorProjectionObservation": [
    {
      "field": "raw mission packageCount",
      "actual": 0,
      "authoritativeMerchantPartPackageCount": 1
    },
    {
      "field": "customerFacingStatus",
      "actual": "Paid, waiting for seller preparation",
      "authoritativeOrderStatus": "DELIVERED"
    }
  ],
  "readProjectionVerification": [
    {
      "field": "raw mission packageCount",
      "actual": 1,
      "authoritativeMerchantPartPackageCount": 1
    },
    {
      "field": "customerFacingStatus",
      "actual": "Delivered",
      "authoritativeOrderStatus": "DELIVERED"
    }
  ],
  "settlementObservation": "Normal synchronous completion posted one 400 CFA CourierPayable credit. Staff summary shows 4,000 CFA across 10 settled missions and no confirmed provider payment. The prior screen showing 3,600 CFA / 9 missions was awaiting refresh; no settlement operation was run."
}

## Limits

- UI wrong-PIN and blank-pickup rejections are parent observations; these GETs do not replay mutations.
- No settlement evaluation, repair, payout, provider transfer or other business write was performed.
- Provider confirmation is not inferred from recorded liabilities or paid fixture status.
- Canonical refresh was verified; no app/API restart was performed by this script.

Full sanitized evidence: rider-journey-readback.json.

## Resolved read projections after API restart

Parent reports running API source6c2d2e1. Final authorized GET checks below verify the actual returned values; no record/history was rewritten by this verification.

[
  {
    "field": "raw mission packageCount",
    "before": 0,
    "after": 1,
    "authoritativeMerchantPartPackageCount": 1,
    "resolved": true,
    "fixCommit": "6c2d2e1"
  },
  {
    "field": "customerFacingStatus",
    "before": "Paid, waiting for seller preparation",
    "afterCustomer": "Delivered",
    "afterAdmin": "Delivered",
    "resolved": true,
    "fixCommit": "fb9ac50"
  }
]

The actual accept/pickup/wrong-PIN/delivery UI evidence used signed Rider59b3478. Final Ridera078aa6 adds only persistence of the existing language preference, pending separate parent reopen QA. Current artifacts are bound to that later commit.
