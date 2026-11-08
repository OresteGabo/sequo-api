# Live local role/scope and authorized ready-dispatch evidence

No tokens, PINs, contact details or raw credentials are included. Sessions were created through the guarded fixed local entries and logged out in finally. Redirects and proxies were disabled. No release, code issuance, problem, payment, acceptance, pickup, delivery or seed/reset actions were executed.

Initial run: 121 passed, 0 failed; the scoped-history follow-up below brings the final total to 124 passed, 0 failed.

## Relay allocation

- dede: local-ops-v1-relay-tokoin; slots=3 {'AVAILABLE': 3}; parcels=0 {}; returns=5; parcel history=0.
- esso: local-ops-v1-relay-adidogome; slots=3 {'OCCUPIED': 3}; parcels=3 {'Delayed': 1, 'ReturnToSellerReview': 1, 'Deposited': 1}; returns=0; parcel history=0.

## Existing preparing order dispatch

{
  "orderId": "SQ-local-ops-v1-merchant-preparing",
  "beforeOrderStatus": "ACCEPTED_FOR_FULFILLMENT",
  "paymentStatus": "Validated",
  "merchantParts": [
    {
      "id": "525417ed-38ef-439d-bd37-2944cbeca7c2",
      "orderId": "SQ-local-ops-v1-merchant-preparing",
      "merchantId": "local-ops-v1-merchant-maison",
      "status": "PACKED_READY",
      "packageCount": 1,
      "packedReadyAt": "2026-11-08T22:00:38.802982Z",
      "updatedAt": "2026-11-08T22:00:38.802982Z"
    }
  ],
  "merchantActorPersistence": "NOT_EXPOSED_BY_EXISTING_SUBORDER_DTO",
  "readyQueueCount": 6,
  "normalAdminWrites": [
    "/api/delivery/missions/dispatch-ready?limit=6",
    "/api/delivery/missions/58dc4a13-780a-4b50-a463-f27d0d3dccc7/assign",
    "/api/delivery/missions/58dc4a13-780a-4b50-a463-f27d0d3dccc7/offer"
  ],
  "dispatchRunId": "6051bf6d-4252-4376-84c2-48e22c449d62",
  "createdMissions": [
    {
      "id": "f4b4d150-5e09-4846-a1fd-075843db164d",
      "orderId": "SQ-local-ops-v1-merchant-ready",
      "merchantSubOrderId": "77ee2715-1f1c-4a80-99d2-c5e4ed1aa2c0",
      "status": "CREATED"
    },
    {
      "id": "58dc4a13-780a-4b50-a463-f27d0d3dccc7",
      "orderId": "SQ-local-ops-v1-merchant-preparing",
      "merchantSubOrderId": "525417ed-38ef-439d-bd37-2944cbeca7c2",
      "status": "CREATED"
    }
  ],
  "existingMissionCount": 4,
  "skippedSubOrders": [],
  "mission": {
    "id": "58dc4a13-780a-4b50-a463-f27d0d3dccc7",
    "orderId": "SQ-local-ops-v1-merchant-preparing",
    "merchantSubOrderId": "525417ed-38ef-439d-bd37-2944cbeca7c2",
    "courierId": "local-ops-v1-courier-senam",
    "status": "OFFERED_TO_COURIER"
  },
  "adminActionHistory": [
    {
      "id": "420f7b5a-d8f4-4ff9-8d5a-151bbac07646",
      "action": "OFFER_MISSION",
      "actorUserId": "5ae10368-fd93-4d5f-9c72-cd62d81c313a",
      "createdAt": "2026-11-08T22:06:40.212565Z",
      "idempotencyKey": "native-review-58dc4a13-780a-4b50-a463-f27d0d3dccc7-offer-senam-v1"
    },
    {
      "id": "92caf561-443c-451b-9dea-d692c6eb25af",
      "action": "ASSIGN_COURIER",
      "actorUserId": "5ae10368-fd93-4d5f-9c72-cd62d81c313a",
      "createdAt": "2026-11-08T22:06:40.172880Z",
      "idempotencyKey": "native-review-58dc4a13-780a-4b50-a463-f27d0d3dccc7-assign-senam-v1"
    }
  ],
  "senamMobileReadback": {
    "id": "58dc4a13-780a-4b50-a463-f27d0d3dccc7",
    "orderId": "SQ-local-ops-v1-merchant-preparing",
    "courierId": "local-ops-v1-courier-senam",
    "status": "OFFERED_TO_COURIER"
  }
}

## Scope notes

- Catalog GET is public: forged includeArchived cannot reveal inactive rows or bargaining floors. Private merchant suborders/store/returns/payout routes require own scope and deny foreign IDs.
- koffi has no persisted own order; positive order-detail read could not be demonstrated without creating one.
- Rider payout routes are self-only and have no courier-selector parameter; a forged query was ignored and returned the same own courier ID, while existing foreign-courier mission/staff routes denied access.

Detailed status/count/fixture-ID matrix: [recorded JSON](live-role-dispatch-matrix.json).

Follow-up GET confirms Dede has 5 return records with 9 relay custody history events. Parcel list responses omit history; authorized Esso detail reads return5 total parcel history events. Final checks:124 passed,0 failed. No business action in this follow-up.
