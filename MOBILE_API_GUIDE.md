# Sequo API Guide For Mobile Apps

This file is a compact integration guide for Sequo mobile applications and AI coding assistants.
It describes the API that is implemented in this repository today. Read this file before proposing
mobile networking code.

## Important Notices

1. The current API base path is `/api`, not `/api/v1`. Do not invent `/api/v1` routes unless versioning
   is added later.
2. The backend accepts JSON request bodies only. Send `Content-Type: application/json` and UTF-8 JSON.
   `application/*+json` is also accepted. Multipart upload is not currently exposed.
3. Protected routes require `Authorization: Bearer <accessToken>`.
4. Money is an integer number of CFA francs. Never use floating-point values for money.
5. Dates and times are ISO-8601 values, preferably UTC, for example `2026-09-09T10:00:00Z`.
6. The backend does not use a universal `{ data, meta }` response envelope. Follow the response shape
   of each endpoint and do not unwrap responses automatically.
7. Some operational routes are intended for Sequo Hub/Admin applications, not customer applications.
8. A `401` means the access token is absent or invalid. A `403` means the role or ownership is wrong.
9. A `429` means rate limiting. Read `Retry-After` and wait before retrying.
10. Mutating operations that contain an idempotency field must reuse the same key when a mobile request
    is retried. Do not generate a new key for the same user action.

## Common HTTP Rules

| Situation | Expected behavior |
| --- | --- |
| Successful JSON request | Usually `200 OK`; some accepted asynchronous work returns `202 Accepted`. |
| No response body | `204 No Content`. |
| Invalid JSON or invalid fields | Usually `400 Bad Request`. |
| Missing/invalid authentication | `401 Unauthorized`. |
| Wrong role or resource owner | `403 Forbidden`. |
| Resource not found | `404 Not Found`. |
| Conflict such as duplicate account | `409 Conflict`. |
| Unsupported body type | `415 Unsupported Media Type`. |
| Too many requests | `429 Too Many Requests` plus `Retry-After`. |

Use an HTTP client that does not silently convert non-2xx responses into successful values.

## Authentication

All authentication routes are public, but still require JSON bodies.

| Method | Path | Body | Purpose |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | `email`, `password`, `name?` | Create an email account. |
| POST | `/api/auth/login` | `email`, `password` | Login with email and password. |
| POST | `/api/auth/login/social` | `provider`, `token` | Login with a social provider. Provider enum: `GOOGLE`, `FACEBOOK`, `APPLE`. |
| POST | `/api/auth/refresh` | `refreshToken` | Obtain a new access token. |
| POST | `/api/auth/forgot-password` | `email` | Start password recovery. The response is intentionally generic. |
| POST | `/api/auth/reset-password` | `token`, `newPassword` | Complete password recovery. |

Successful login/signup responses contain:

```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "expiresIn": 3600
}
```

Store tokens in the mobile platform's secure storage. Never log access tokens, refresh tokens,
passwords, PINs, FCM tokens, or webhook signatures.

Authentication abuse protection is applied per IP and per safe hashed subject. Auth routes are limited
with a leaky-bucket policy and return a safe `rate_limited` response when exhausted.

## Customer Orders

| Method | Path | Body/query | Purpose |
| --- | --- | --- | --- |
| POST | `/api/orders/process` | `OrderProcessingRequest` | Validate payment and create the customer's fulfillment records. |
| POST | `/api/orders/{orderId}/pickup-confirmations` | `idempotencyKey`, `proofMetadata?` | Confirm customer pickup/click-and-collect. |
| GET | `/api/orders/{orderId}/pickup-confirmations` | none | Read pickup confirmations for the authenticated customer. |

`OrderProcessingRequest` fields:

```json
{
  "checkoutId": "checkout-123",
  "customerId": "ignored-by-server",
  "serviceLevel": "Regular",
  "route": "FastDelivery",
  "lines": [
    {
      "productId": "product-1",
      "sellerId": "merchant-1",
      "sellerName": "Seller",
      "productName": "Rice",
      "category": "GeneralGoods",
      "quantity": 2,
      "unitPriceCfa": 1500,
      "negotiatedUnitPriceCfa": null,
      "photoEvidence": { "type": "GenericCatalogImage" }
    }
  ],
  "deliveryDistanceKm": 4.5,
  "referralCreditCfa": 0,
  "paymentProvider": { "value": "yas_togo" },
  "paymentReference": "provider-reference",
  "countryCode": "TG"
}
```

Important order enums:

- `serviceLevel`: `Regular`, `PrimeMonthly`, `PrimeMultiYear`.
- `route`: `FastDelivery`, `GroupedSequo`, `Pickup`, `PointDeRelai`.
- `category`: `Food`, `Perishable`, `GeneralGoods`, `GenericSealedItem`.
- `photoEvidence`: live camera evidence, `GenericCatalogImage`, `Missing`, or `GalleryUpload`.
  Seller-specific products require live camera evidence; gallery uploads are rejected.
- `paymentProvider.value`: `yas_togo` or `moov_africa`.

The server replaces `customerId` with the authenticated user ID. A successful payment can return `200`;
a provider-pending payment can return `202`; a rejected order returns `400`.

## Delivery Tracking

| Method | Path | Query | Purpose |
| --- | --- | --- | --- |
| GET | `/api/delivery/tracking/{deliveryCode}` | required `orderId` | Read proof-redacted tracking for a delivery mission. |
| GET | `/api/consolidations/{manifestId}/tracking` | none | Read tracking for a dispatched final consolidation package. |
| GET | `/api/consolidations/{manifestId}` | none | Read the consolidation manifest when the caller is the customer owner or an operator. |
| GET | `/api/consolidations/order/{orderId}` | none | Read the customer's consolidation manifest by order. |

Tracking responses expose status, current/next step, destination type, timestamps, and whether a courier
is assigned. They do not expose raw delivery PINs or private proof details.

## Merchant Fulfillment

These routes are for merchant owner/staff applications and authorized operators. A non-admin merchant
must send its own merchant ID and must be authenticated as that merchant.

| Method | Path | Body/query | Purpose |
| --- | --- | --- | --- |
| GET | `/api/merchant/sub-orders` | `merchantId`, `status?` | List merchant sub-orders. |
| GET | `/api/merchant/sub-orders/{subOrderId}` | `merchantId?` | Read one sub-order. |
| GET | `/api/merchant/sub-orders/{subOrderId}/sla` | `merchantId?` | Read response and packing deadlines. |
| GET | `/api/merchant/sub-orders/{subOrderId}/escalations` | none | Read support escalations. |
| POST | `/api/merchant/sub-orders` | sub-order command | Create a sub-order; normally an admin/internal operation. |
| POST | `/api/merchant/sub-orders/{subOrderId}/accept` | `merchantId` | Accept the order. |
| POST | `/api/merchant/sub-orders/{subOrderId}/start-preparation` | `merchantId` | Start preparation. |
| POST | `/api/merchant/sub-orders/{subOrderId}/mark-packed` | `merchantId`, `packageCount` | Mark packages ready for pickup. `packageCount` must be positive. |
| POST | `/api/merchant/sub-orders/{subOrderId}/handoff` | `merchantId` | Confirm merchant handoff. |
| POST | `/api/merchant/sub-orders/{subOrderId}/reject` | `merchantId`, `reason` | Reject the sub-order. |
| POST | `/api/merchant/sub-orders/{subOrderId}/escalations` | `reason`, `note` | Create an operator escalation. |

## Courier And Operations Missions

These routes are used by Sequo Rider, Sequo Hub, and admin applications.

| Method | Path | Body/query | Purpose |
| --- | --- | --- | --- |
| GET | `/api/delivery/missions` | `courierId?`, `status?` | List assigned courier missions or operational missions. |
| GET | `/api/delivery/missions/{missionId}` | none | Read a mission with ownership checks. |
| GET | `/api/delivery/missions/couriers/{courierId}/availability` | none | Read courier pause/availability; admin only. |
| POST | `/api/delivery/missions` | mission command | Create a mission; admin only. |
| POST | `/api/delivery/missions/dispatch-ready?limit=50` | none | Create missions from packed merchant sub-orders; admin only. |
| POST | `/api/delivery/missions/expire-stale` | expiry request | Expire stale offers/pickups; admin only. |
| POST | `/api/delivery/missions/couriers/pause` | `courierId`, `reason`, `pausedUntil?` | Pause courier; admin only. |
| POST | `/api/delivery/missions/couriers/unpause` | `courierId` | Unpause courier; admin only. |
| POST | `/api/delivery/missions/{missionId}/assign` | `courierId` | Assign courier; admin only. |
| POST | `/api/delivery/missions/{missionId}/reassign` | `courierId` | Reassign before pickup; admin only. |
| POST | `/api/delivery/missions/{missionId}/offer` | none | Offer mission to courier; admin only. |
| POST | `/api/delivery/missions/{missionId}/accept` | none | Courier accepts its offered mission. |
| POST | `/api/delivery/missions/{missionId}/pickup` | `proofMetadata?`, `idempotencyKey?` | Courier picks up from seller. Proof is required by workflow policy. |
| POST | `/api/delivery/missions/{missionId}/deliver` | `proofMetadata?`, `deliveryPin?`, `idempotencyKey?` | Complete direct delivery with proof and PIN validation. |
| POST | `/api/delivery/missions/{missionId}/delivery-pin` | `rawPin`, `expiresAt` | Create direct delivery PIN; admin only. Raw PIN is never returned later. |
| POST | `/api/delivery/missions/{missionId}/relay-deposit` | `proofMetadata?`, `idempotencyKey?` | Deposit parcel at a relay point. |
| POST | `/api/delivery/missions/{missionId}/relay-release` | `pickupCodeValidated`, `identityValidated`, `proofMetadata?`, `idempotencyKey?` | Complete relay release workflow. |
| POST | `/api/delivery/missions/{missionId}/problem` | `reason`, `idempotencyKey?` | Report a courier/mission problem. |
| POST | `/api/delivery/missions/{missionId}/cancel` | `reason` | Cancel mission; operator/admin. |
| POST | `/api/delivery/missions/{missionId}/force-problem` | `reason` | Force problem state; operator/admin. |
| POST | `/api/delivery/missions/{missionId}/resolve-problem` | `action`, `reason`, `replacementCourierId?` | Requeue or cancel a problem mission. |
| GET | `/api/delivery/missions/{missionId}/problem-resolutions` | none | Read resolution history; operator/admin. |

Mission enums include `STANDARD`, `EXPRESS`, `PROGRAMMED`, `CLICK_COLLECT`, `RELAY` for mode and
`CUSTOMER_ADDRESS`, `RELAY_POINT`, `SEQUO_CONSOLIDATION` for destination. Mission states include
`CREATED`, `OFFERED_TO_COURIER`, `ACCEPTED_BY_COURIER`, `PICKED_UP_FROM_SELLER`, `DEPOSITED_AT_RELAY`,
`DELIVERED_TO_CUSTOMER`, `RELEASED_BY_RELAY`, `PROBLEM_REPORTED`, and `CANCELLED`.

## Consolidation

| Method | Path | Body | Purpose |
| --- | --- | --- | --- |
| POST | `/api/consolidations` | `manifestId`, `orderId`, `customerId`, `sellerPackages[]` | Create a manifest; admin/operator only. |
| POST | `/api/consolidations/{manifestId}/seller-packages/{subOrderId}/ready` | `merchantId`, `at?` | Merchant confirms its package is ready. |
| POST | `/api/consolidations/{manifestId}/seller-packages/{subOrderId}/collected` | `at?` | Operator records collection. |
| POST | `/api/consolidations/{manifestId}/transitions` | `event`, `at?`, `finalPackageId?` | Apply an admin consolidation transition. |
| POST | `/api/consolidations/{manifestId}/dispatch-final-package` | `customerDeliveryFeeCfa`, `courierFeeCfa`, `at?` | Create one idempotent final customer mission. |

Seller package fields are `subOrderId`, `merchantId`, `packageCount`, `ready`, and `collected`.
The final dispatch is only valid after all packages are collected and a final package ID exists.

## Relay Parcels

| Method | Path | Body/query | Purpose |
| --- | --- | --- | --- |
| GET | `/api/relay/parcels` | `relayPointId`, `status?` | List parcels for a relay point. |
| GET | `/api/relay/parcels/{parcelId}` | `relayPointId?` | Read parcel; relay partners are scoped to their point. |
| POST | `/api/relay/parcels` | parcel creation command | Create eligible non-food parcel and reserve a free locker. |
| POST | `/api/relay/parcels/{parcelId}/pickup-code` | `codeId`, `rawNumericCode`, `rawQrNonce?`, `identityCheckRequired`, `expiresAt` | Create hashed pickup credentials. Numeric code must be six digits. |
| POST | `/api/relay/parcels/{parcelId}/release` | relay point, credential, identity, event/idempotency fields | Release parcel after code/QR and identity validation. |
| POST | `/api/relay/parcels/{parcelId}/problem` | `eventId`, `idempotencyKey`, `metadata` | Report a relay problem. |
| POST | `/api/relay/parcels/{parcelId}/return-to-seller` | `eventId`, `idempotencyKey`, `metadata`, `returnedAt?` | Admin closes an approved return-to-seller review. |
| POST | `/api/relay/parcels/storage-fees/assess` | `relayPointId`, `dailyFeeCfa`, `evaluatedAt?` | Assess delayed storage fees; admin only. |
| GET | `/api/relay/parcels/storage-fees` | `relayPointId` | Read fee assessments; admin only. |

Food and perishable parcels are rejected for relay pickup. Raw pickup codes and QR secrets are stored
only as hashes; mobile apps must never log them.

## Returns

| Method | Path | Body/query | Purpose |
| --- | --- | --- | --- |
| POST | `/api/returns` | return request | Customer creates a return request. |
| GET | `/api/returns` | customer `customerId?`, operator `status?` | List own returns or operational returns. |
| GET | `/api/returns/{returnId}` | none | Read return with ownership checks. |
| GET | `/api/returns/orders/{orderId}` | none | Operator lists returns for an order. |
| POST | `/api/returns/{returnId}/relay-dropoff` | `relayPointId`, `rawReturnPin`, `droppedAt?` | Relay records return drop-off. |
| POST | `/api/returns/{returnId}/physical-receipt` | `receivedAt?`, `conditionAssessment`, `responsibility`, `idempotencyKey` | Sequo confirms physical receipt. |
| POST | `/api/returns/{returnId}/refund` | `amountCfa`, `idempotencyKey` | Finance admin triggers refund after receipt. |

Customer return requests include `returnId`, `orderId`, `customerId`, `reason`, `requestedRefundCfa`,
`rawReturnPin`, `requestedAt?`, `productReturnable`, `merchantAllowsReturn`, and `adminOverride`.
The return window is 72 hours after delivery. Refund must not be requested before physical receipt.

## Notifications

| Method | Path | Body/query | Purpose |
| --- | --- | --- | --- |
| POST | `/api/notifications/devices/fcm` | `deviceId`, `fcmToken`, `appFamily`, `platform`, optional app metadata | Register or rotate an FCM device token. |
| DELETE | `/api/notifications/devices/{appFamily}/{deviceId}` | none | Revoke the current user's device token. |
| GET | `/api/notifications/inbox` | `includeArchived?`, `limit?` | Read the user's in-app inbox. |
| PATCH | `/api/notifications/inbox/{messageId}/read` | none | Mark notification as read. |
| POST | `/api/notifications/inbox/{messageId}/archive` | none | Archive notification. |
| DELETE | `/api/notifications/inbox/{messageId}/archive` | none | Unarchive notification. |

`appFamily` values: `SEQUO_CUSTOMER`, `SEQUO_MERCHANT`, `SEQUO_HUB`, `SEQUO_RIDER`, `SEQUO_ADMIN`.
`platform` values: `ANDROID`, `IOS`, `WEB`. FCM delivery is server-controlled; never send directly
from a mobile app with server credentials.

## Payment Webhooks

These are provider-to-server routes, not mobile-app routes.

| Method | Path | Required headers | Body |
| --- | --- | --- | --- |
| POST | `/api/payments/webhooks/yas_togo` | `X-Sequo-Webhook-Timestamp`, `X-Sequo-Webhook-Signature` | JSON provider event. |
| POST | `/api/payments/webhooks/moov_africa` | same | JSON provider event. |

Webhook JSON fields are `eventId`, `checkoutId`, `paymentReference`, `amountCfa`, `status`, and
`occurredAt`. The signature is HMAC-protected, timestamp-limited, replay-protected, and idempotent.
Mobile applications must not call these routes.

## Admin, Commission And Settlement

These routes belong to Sequo Hub/Admin applications and must not be placed in customer navigation.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/admin/monitoring/operations` | Operational counts for orders, missions, relay, returns, and settlements. |
| GET | `/api/commissions/merchant-overrides/{merchantId}` | Read merchant commission override; admin only. |
| PUT | `/api/commissions/merchant-overrides/{merchantId}` | Set `commissionRateBps` and optional `reason`; admin only. |
| DELETE | `/api/commissions/merchant-overrides/{merchantId}` | Clear commission override; admin only. |
| GET | `/api/settlements/merchant-payouts` | Query `merchantId`, optional `status`; merchant/admin scope. |
| GET | `/api/settlements/ledger` | Query `sourceType`, `sourceId`; admin only. |
| POST | `/api/settlements/merchant-payouts/evaluate-eligible` | Optional query `evaluatedAt`; admin only. |

## Health

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/actuator/health` | No | Liveness/health response. |
| GET | `/actuator/health/**` | No | Health detail endpoints when enabled by configuration. |

## Mobile Implementation Checklist

- Use one shared HTTP client and one authentication interceptor.
- Add `Content-Type: application/json` to every request with a body.
- Add `Authorization: Bearer ...` to every protected request.
- On `401`, refresh once, then retry the original request once; avoid infinite refresh loops.
- On `429`, honor `Retry-After` and apply client-side backoff.
- Persist and reuse idempotency keys for retried actions.
- Display server error codes and messages safely; do not expose stack traces.
- Treat enum values as exact strings, including capitalization.
- Never trust client-supplied customer IDs, merchant IDs, roles, fees, or ownership claims.
- Never put admin, settlement, webhook, or provider-secret operations in a customer mobile app.
- Keep this file beside the mobile project and update it when an endpoint contract changes.

## Current Limitations

The following are not mobile-ready API contracts yet: WebSocket/STOMP realtime messaging, Firebase
provider delivery configuration, real Yas/Moov payment initiation adapters, persisted merchant staff
membership, customer address management, and a complete product/catalog browsing API. Do not create
mobile calls for these features based only on the design documents; wait for implemented controllers.
