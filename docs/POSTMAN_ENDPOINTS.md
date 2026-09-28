# Sequo API Postman Test Guide

This guide lists the REST endpoints implemented by the current Spring controllers and gives Postman-ready examples.

## Environment

Create a Postman environment with these variables:

| Variable | Value |
| --- | --- |
| `base_url` | `https://api.sequoservice.com` |
| `local_url` | `http://localhost:8082` |
| `access_token` | Set after login/signup |
| `refresh_token` | Set after login/signup |
| `customer_id` | `seed-customer-1` |
| `admin_id` | `seed-admin-1` |
| `merchant_id` | `seed-merchant-1` |
| `courier_id` | `seed-courier-1` |
| `relay_point_id` | `seed-relay-1` |
| `order_id` | `seed-order-1` |
| `sub_order_id` | `seed-sub-order-1` |
| `mission_id` | `seed-mission-1` |
| `delivery_code` | `DLV-SEED-001` |
| `parcel_id` | `seed-parcel-1` |
| `return_id` | `seed-return-1` |

Use these headers for JSON requests:

```http
Content-Type: application/json
Accept: application/json
```

Use this header for protected routes:

```http
Authorization: Bearer {{access_token}}
```

Postman test script for login/signup/refresh responses:

```javascript
const body = pm.response.json();
if (body.accessToken) pm.environment.set("access_token", body.accessToken);
if (body.refreshToken) pm.environment.set("refresh_token", body.refreshToken);
```

## Quick Smoke Test

1. `GET {{base_url}}/actuator/health` should return `200` and `{"status":"UP"}`.
2. `POST {{base_url}}/api/auth/signup` creates a normal test user and returns tokens.
3. `GET {{base_url}}/api/auth/me` with `Authorization: Bearer {{access_token}}` confirms the token works.
4. `POST {{base_url}}/api/auth/refresh` rotates the refresh token.

## Authentication

| Method | Endpoint | Auth | Expected |
| --- | --- | --- | --- |
| `POST` | `/api/auth/signup` | No | `200` with `accessToken`, `refreshToken`, `expiresIn` |
| `POST` | `/api/auth/login` | No | `200` with tokens, or `401` for bad password |
| `POST` | `/api/auth/login/social` | No | `200` with tokens for a real Google ID token |
| `POST` | `/api/auth/refresh` | No | `200` with rotated tokens |
| `POST` | `/api/auth/logout` | No | `204`, revokes one refresh token |
| `POST` | `/api/auth/logout-all` | Yes | `204`, revokes all user sessions |
| `GET` | `/api/auth/sessions` | Yes | Session metadata, no token material |
| `GET` | `/api/auth/me` | Yes | Current user |
| `DELETE` | `/api/auth/sessions/{sessionId}` | Yes | `204` or `404` |
| `POST` | `/api/auth/forgot-password` | No | Generic success message |
| `POST` | `/api/auth/reset-password` | No | Success or invalid-token message |

Example signup:

```json
{
  "email": "postman.customer@example.test",
  "password": "Cobalt-Violet-47!",
  "name": "Postman Customer"
}
```

Example email login:

```json
{
  "email": "postman.customer@example.test",
  "password": "Cobalt-Violet-47!"
}
```

Example Google login:

```json
{
  "provider": "GOOGLE",
  "token": "PASTE_GOOGLE_ID_TOKEN_HERE"
}
```

Example refresh:

```json
{
  "refreshToken": "{{refresh_token}}"
}
```

## Orders

| Method | Endpoint | Auth | How to test |
| --- | --- | --- | --- |
| `POST` | `/api/orders/process` | Yes | Create/process a checkout order |
| `GET` | `/api/orders` | Yes | List orders for current user |
| `GET` | `/api/orders/{{order_id}}` | Yes | Read one order |
| `POST` | `/api/orders/{{order_id}}/pickup-confirmations` | Yes | Confirm customer pickup |
| `GET` | `/api/orders/{{order_id}}/pickup-confirmations` | Yes | List pickup confirmations |

Pickup confirmation body:

```json
{
  "idempotencyKey": "postman-pickup-001",
  "proofMetadata": "{\"source\":\"postman\"}"
}
```

## Merchant Fulfillment

| Method | Endpoint | Auth | Role |
| --- | --- | --- | --- |
| `POST` | `/api/merchant/sub-orders` | Yes | Admin |
| `GET` | `/api/merchant/sub-orders?merchantId={{merchant_id}}` | Yes | Merchant/admin |
| `GET` | `/api/merchant/sub-orders/{{sub_order_id}}?merchantId={{merchant_id}}` | Yes | Merchant/admin |
| `GET` | `/api/merchant/sub-orders/{{sub_order_id}}/sla?merchantId={{merchant_id}}` | Yes | Merchant/admin |
| `POST` | `/api/merchant/sub-orders/{{sub_order_id}}/accept` | Yes | Merchant/admin |
| `POST` | `/api/merchant/sub-orders/{{sub_order_id}}/start-preparation` | Yes | Merchant/admin |
| `POST` | `/api/merchant/sub-orders/{{sub_order_id}}/mark-packed` | Yes | Merchant/admin |
| `POST` | `/api/merchant/sub-orders/{{sub_order_id}}/handoff` | Yes | Merchant/admin |
| `POST` | `/api/merchant/sub-orders/{{sub_order_id}}/reject` | Yes | Merchant/admin |
| `POST` | `/api/merchant/sub-orders/sla/publish-overdue` | Yes | Support/admin |
| `GET` | `/api/merchant/sub-orders/{{sub_order_id}}/escalations` | Yes | Support/admin |
| `POST` | `/api/merchant/sub-orders/{{sub_order_id}}/escalations` | Yes | Support/admin |

Common merchant body:

```json
{
  "merchantId": "{{merchant_id}}"
}
```

Mark packed body:

```json
{
  "merchantId": "{{merchant_id}}",
  "packageCount": 1
}
```

Reject body:

```json
{
  "merchantId": "{{merchant_id}}",
  "reason": "Out of stock"
}
```

## Delivery Missions

| Method | Endpoint | Auth | Role |
| --- | --- | --- | --- |
| `POST` | `/api/delivery/missions` | Yes | Admin |
| `GET` | `/api/delivery/missions` | Yes | Courier/support/admin |
| `GET` | `/api/delivery/missions?courierId={{courier_id}}` | Yes | Courier/support/admin |
| `GET` | `/api/delivery/missions/{{mission_id}}` | Yes | Courier/support/admin |
| `POST` | `/api/delivery/missions/{{mission_id}}/assign` | Yes | Admin |
| `POST` | `/api/delivery/missions/{{mission_id}}/reassign` | Yes | Admin |
| `POST` | `/api/delivery/missions/{{mission_id}}/offer` | Yes | Admin |
| `POST` | `/api/delivery/missions/{{mission_id}}/accept` | Yes | Courier |
| `POST` | `/api/delivery/missions/{{mission_id}}/pickup` | Yes | Courier |
| `POST` | `/api/delivery/missions/{{mission_id}}/deliver` | Yes | Courier |
| `POST` | `/api/delivery/missions/{{mission_id}}/relay-deposit` | Yes | Courier |
| `POST` | `/api/delivery/missions/{{mission_id}}/relay-release` | Yes | Relay partner/admin |
| `POST` | `/api/delivery/missions/{{mission_id}}/problem` | Yes | Courier/relay/support/admin |
| `POST` | `/api/delivery/missions/{{mission_id}}/delivery-pin` | Yes | Admin |
| `POST` | `/api/delivery/missions/{{mission_id}}/cancel` | Yes | Admin |
| `POST` | `/api/delivery/missions/{{mission_id}}/force-problem` | Yes | Admin |
| `POST` | `/api/delivery/missions/{{mission_id}}/resolve-problem` | Yes | Admin |
| `GET` | `/api/delivery/missions/{{mission_id}}/problem-resolutions` | Yes | Admin |
| `POST` | `/api/delivery/missions/dispatch-ready?limit=50` | Yes | Admin |
| `POST` | `/api/delivery/missions/expire-stale` | Yes | Admin |
| `POST` | `/api/delivery/missions/couriers/pause` | Yes | Admin |
| `POST` | `/api/delivery/missions/couriers/unpause` | Yes | Admin |
| `GET` | `/api/delivery/missions/couriers/{{courier_id}}/availability` | Yes | Admin |

Create mission body:

```json
{
  "deliveryCode": "DLV-POSTMAN-001",
  "orderId": "{{order_id}}",
  "merchantSubOrderId": "{{sub_order_id}}",
  "deliveryMode": "STANDARD",
  "destinationType": "CUSTOMER_ADDRESS",
  "customerDeliveryFeeCfa": 1200,
  "courierFeeCfa": 900,
  "shortfallCfa": 0
}
```

Proof body:

```json
{
  "proofMetadata": "{\"photo\":\"postman-proof\"}",
  "deliveryPin": "123456",
  "idempotencyKey": "postman-proof-001"
}
```

## Delivery Tracking

| Method | Endpoint | Auth | How to test |
| --- | --- | --- | --- |
| `GET` | `/api/delivery/tracking/{{delivery_code}}?orderId={{order_id}}` | Yes | Returns tracking for customer/support/admin |

## Relay Parcels

| Method | Endpoint | Auth | Role |
| --- | --- | --- | --- |
| `POST` | `/api/relay/parcels` | Yes | Relay/courier/admin |
| `GET` | `/api/relay/parcels?relayPointId={{relay_point_id}}` | Yes | Relay/admin |
| `GET` | `/api/relay/parcels/{{parcel_id}}?relayPointId={{relay_point_id}}` | Yes | Relay/admin |
| `POST` | `/api/relay/parcels/{{parcel_id}}/pickup-code` | Yes | Relay/admin |
| `POST` | `/api/relay/parcels/{{parcel_id}}/release` | Yes | Relay/admin |
| `POST` | `/api/relay/parcels/{{parcel_id}}/problem` | Yes | Relay/admin |
| `POST` | `/api/relay/parcels/{{parcel_id}}/return-to-seller` | Yes | Support/admin |
| `POST` | `/api/relay/parcels/storage-fees/assess` | Yes | Support/admin |
| `GET` | `/api/relay/parcels/storage-fees?relayPointId={{relay_point_id}}` | Yes | Support/admin |

Release body:

```json
{
  "relayPointId": "{{relay_point_id}}",
  "rawNumericCode": "246810",
  "identityDocumentMatched": true,
  "eventId": "postman-release-001",
  "idempotencyKey": "postman-release-001"
}
```

## Returns

| Method | Endpoint | Auth | Role |
| --- | --- | --- | --- |
| `POST` | `/api/returns` | Yes | Customer/support/admin |
| `GET` | `/api/returns` | Yes | Customer/support/admin |
| `GET` | `/api/returns/{{return_id}}` | Yes | Customer/relay/support/admin |
| `GET` | `/api/returns/orders/{{order_id}}` | Yes | Support/admin |
| `POST` | `/api/returns/{{return_id}}/relay-dropoff` | Yes | Relay/admin |
| `POST` | `/api/returns/{{return_id}}/physical-receipt` | Yes | Support/admin |
| `POST` | `/api/returns/{{return_id}}/refund` | Yes | Admin |

Create return body:

```json
{
  "returnId": "postman-return-001",
  "orderId": "{{order_id}}",
  "customerId": "{{customer_id}}",
  "reason": "Wrong item",
  "requestedRefundCfa": 2500,
  "rawReturnPin": "135790",
  "productReturnable": true,
  "merchantAllowsReturn": true
}
```

## Consolidations

| Method | Endpoint | Auth | Role |
| --- | --- | --- | --- |
| `POST` | `/api/consolidations` | Yes | Support/admin |
| `GET` | `/api/consolidations/{manifestId}` | Yes | Customer/support/admin |
| `GET` | `/api/consolidations/order/{{order_id}}` | Yes | Customer/support/admin |
| `GET` | `/api/consolidations/{manifestId}/tracking` | Yes | Customer/support/admin |
| `POST` | `/api/consolidations/{manifestId}/seller-packages/{subOrderId}/ready` | Yes | Merchant/admin |
| `POST` | `/api/consolidations/{manifestId}/seller-packages/{subOrderId}/collected` | Yes | Support/admin |
| `POST` | `/api/consolidations/{manifestId}/transitions` | Yes | Support/admin |
| `POST` | `/api/consolidations/{manifestId}/dispatch-final-package` | Yes | Support/admin |

## Settlements

| Method | Endpoint | Auth | Role |
| --- | --- | --- | --- |
| `GET` | `/api/settlements/merchant-payouts?merchantId={{merchant_id}}` | Yes | Merchant/admin |
| `GET` | `/api/settlements/ledger?sourceType=OrderItem&sourceId={{sub_order_id}}` | Yes | Admin |
| `POST` | `/api/settlements/merchant-payouts/evaluate-eligible` | Yes | Admin |

## Commissions

| Method | Endpoint | Auth | Role |
| --- | --- | --- | --- |
| `GET` | `/api/commissions/merchant-overrides/{{merchant_id}}` | Yes | Admin |
| `PUT` | `/api/commissions/merchant-overrides/{{merchant_id}}` | Yes | Admin |
| `DELETE` | `/api/commissions/merchant-overrides/{{merchant_id}}` | Yes | Admin |

PUT body:

```json
{
  "commissionRateBps": 1200,
  "reason": "Postman test override"
}
```

## Notifications

| Method | Endpoint | Auth | How to test |
| --- | --- | --- | --- |
| `POST` | `/api/notifications/devices/fcm` | Yes | Register/rotate FCM token |
| `DELETE` | `/api/notifications/devices/{appFamily}/{deviceId}` | Yes | Revoke device token |
| `GET` | `/api/notifications/preferences/{appFamily}/effective?eventType=RIDER_PICKED_UP` | Yes | Resolve effective preference |
| `PUT` | `/api/notifications/preferences/{appFamily}` | Yes | Save preference |
| `GET` | `/api/notifications/inbox?includeArchived=false&limit=50` | Yes | List inbox |
| `PATCH` | `/api/notifications/inbox/{messageId}/read` | Yes | Mark read |
| `POST` | `/api/notifications/inbox/{messageId}/archive` | Yes | Archive |
| `DELETE` | `/api/notifications/inbox/{messageId}/archive` | Yes | Unarchive |

Register FCM body:

```json
{
  "deviceId": "postman-device-1",
  "fcmToken": "fake-fcm-token-for-postman",
  "appFamily": "SEQUO_CUSTOMER",
  "platform": "ANDROID",
  "appVersion": "1.0.0",
  "locale": "fr-TG",
  "timezone": "Africa/Lome"
}
```

Preference body:

```json
{
  "eventType": "ALL",
  "pushEnabled": true,
  "inAppEnabled": true,
  "smsEnabled": false,
  "quietHoursStart": "22:00:00",
  "quietHoursEnd": "07:00:00"
}
```

## Hub, Account, Preferences

| Method | Endpoint | Auth | Role |
| --- | --- | --- | --- |
| `POST` | `/api/hub/scan/resolve` | Yes | Relay/admin |
| `GET` | `/api/hub/summary?hubId={{relay_point_id}}` | Yes | Relay/admin |
| `POST` | `/api/hub/lockers/{lockerId}/availability` | Yes | Relay/admin |
| `GET` | `/api/hub/opening-hours?relayPointId={{relay_point_id}}` | Yes | Relay/admin |
| `PUT` | `/api/hub/opening-hours` | Yes | Relay/admin |
| `GET` | `/api/hub/control-state?relayPointId={{relay_point_id}}` | Yes | Relay/support/admin |
| `POST` | `/api/hub/control-state` | Yes | Support/admin/system |
| `GET` | `/api/hub/control-state/history?relayPointId={{relay_point_id}}` | Yes | Relay/support/admin |
| `POST` | `/api/account/deletion-requests` | Yes | Any logged-in user |
| `GET` | `/api/preferences?appFamily=SEQUO_HUB` | Yes | Any logged-in user |
| `PATCH` | `/api/preferences?appFamily=SEQUO_HUB` | Yes | Any logged-in user |

Hub scan body:

```json
{
  "hubId": "{{relay_point_id}}",
  "credential": "DEP-SEED-001",
  "credentialType": "AUTO",
  "idempotencyKey": "postman-scan-001"
}
```

Preference patch body:

```json
{
  "theme": "DARK",
  "language": "fr",
  "quickScanOnOpen": true,
  "soundFeedback": true,
  "largeLockerLabels": true
}
```

## Payments And Admin Monitoring

| Method | Endpoint | Auth | How to test |
| --- | --- | --- | --- |
| `POST` | `/api/payments/webhooks/{provider}` | Provider headers | Use `yas-togo` or `moov-africa` plus webhook headers |
| `GET` | `/api/admin/monitoring/operations` | Support/admin | Operational snapshot |

Webhook headers:

```http
X-Sequo-Webhook-Timestamp: 1790420000
X-Sequo-Webhook-Signature: test-signature
```

Webhook body:

```json
{
  "eventId": "postman-webhook-001",
  "checkoutId": "seed-checkout-1",
  "paymentReference": "PAY-SEED-001",
  "providerReference": "YAS-POSTMAN-001",
  "amountCfa": 10200,
  "status": "VALIDATED",
  "occurredAt": "2026-09-26T12:00:00Z"
}
```

## Notes

- `401` means missing/invalid access token.
- `403` means the token is valid but does not have the required role or merchant scope.
- Google login needs a Google ID token from the client app, not a Firebase paid backend.
- Seed rows in `src/main/resources/db/seed/dev_seed_data.sql` are for database/workflow testing. For real login testing, use `/api/auth/signup` or a real Google ID token.
