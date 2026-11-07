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

## Customer basket and delivery quote

All routes use the signed-in customer's basket.

| Method | Route | Input |
| --- | --- | --- |
| GET | `/api/cart` | No parameters |
| PUT | `/api/cart/items` | `productId`, absolute `quantity` (1–99), optional `selections` with `groupId` and `optionIds` |
| DELETE | `/api/cart/items/{itemId}` | Basket line ID from the cart response |
| GET | `/api/cart/quote?addressId={addressId}` | An active saved home-delivery address owned by the customer, with coordinates |

Reads use current catalog prices and option availability. Unavailable lines stay visible with no line total so the customer can remove or replace them. The basket subtotal covers available lines. A delivery quote requires a nonempty basket with every line available.

Quotes return product, option, service, and delivery amounts in XOF, plus the address ID, estimated distance in metres, distance source, and billable kilometres. The current customer service fee is zero. The existing delivery engine rounds up to whole kilometres. With default pricing, 4,999 m and 5,000 m cost 400 FCFA, and 5,001 m costs 500 FCFA. Merchant commissions use the existing settlement calculation.

For a local fixture, set the Spring property `sequo.cart.straight-line-dispatch-origin=0,0` and use an owned address at latitude 0 and longitude `0.0449750735` (approximately 5,001 m). Without a configured origin, the quote returns an error. Confirm the production origin, distance source, and fractional-kilometre rule with SEQUO before enabling production estimates. Checkout validates the reviewed quote revision before submitting.

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

### Customer basket checkout

1. Read `GET /api/cart/quote?addressId={owned_address_id}` and show the amounts to the customer.
2. Submit `POST /api/orders/checkouts` with `addressId`, the reviewed `quoteRevision`, and `paymentProvider` (`yas_togo` or `moov_africa`).
3. Read `GET /api/orders/checkouts` or `GET /api/orders/checkouts/{checkoutId}` after a refresh or restart. Read `GET /api/orders` for subsequent paid order states.

The authenticated customer and reviewed quote revision form the stable retry identity. Retry the same body after a timeout. An existing checkout is returned even after catalog changes. Changing its provider or destination requires a new reviewed basket. A changed basket, address, or price before first submission returns HTTP 409 with `quote_changed`; show the fresh quote and ask the customer to submit again.

HTTP 202 means payment is pending, HTTP 422 means the checkout failed, and HTTP 200 means payment has been verified. Pending and accepted submissions clear the submitted basket transactionally. Failed submissions preserve it for correction. Re-adding products creates new basket line IDs and allows a new order. Client totals, payment success flags, and customer IDs have no authority on this endpoint.

The server persists a home destination snapshot, real product quantities, prices, and option IDs in the existing pending-checkout payload. Legacy payloads without these optional fields remain readable. Pending payments create no merchant fulfillment. The existing signed webhook reconciliation creates the paid order. Customer checkout reads return a destination label and product quantities; full contact details remain in the server snapshot.

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

The default fee deadline is 8 elapsed days from deposit. The fee can accrue strictly after the deadline. Set `sequo.relay.storage-fee-after` to an ISO-8601 duration to change the deadline for new deposits. Existing deposits without a recorded deadline are excluded from assessment.

The assessment body accepts `relayPointId`, `dailyFeeCfa`, and optional `evaluatedAt` (defaults to the current time). Use an approved daily rate. A zero rate posts nothing. The first positive assessment records the parcel rate for later assessments. Replays post no ledger delta.

Review becomes due at 30 elapsed days from deposit. Set `sequo.relay.review-after` to override the review duration. Set `RELAY_DELAYED_PARCEL_SCHEDULER_ENABLED=true` to run periodic review scans. The default interval is one hour. An authorized operator records a seller return separately.

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


## Isolated payment verification

The deterministic payment exercise lives in the test source set. Run it with Java 21 and local Gradle dependencies.

```sh
./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest' --tests '*PaymentSimulationGuardTest' --tests '*PaymentWebhookServiceTest' --tests '*PendingPaymentCheckoutServiceTest'
```

The HTTP fixture submits an owned basket, signs events with a test-only secret, and sends them to `POST /api/payments/webhooks/yas_togo`. It checks pending, wrong amount, wrong reference, wrong currency, forged signature, success, duplicate success, failure, and late success. Each accepted fixture goes through the existing webhook service and reconciliation listener. A pending or failed checkout has no merchant fulfillment.

The webhook route authenticates the raw body using `X-Sequo-Webhook-Timestamp` (epoch seconds) and `X-Sequo-Webhook-Signature` (HMAC-SHA256 of `timestamp.rawBody`). Set a dedicated staging webhook secret through the existing wallet environment configuration. Tracked development placeholder secrets are rejected. Events with an explicit currency must use `XOF`. Legacy events that omit currency retain the existing XOF interpretation.

Runtime payment simulation is disabled. Setting `sequo.payments.simulator.enabled=true` fails application initialization in every profile, including production with the development-security override enabled. Test classes and fixture credentials are excluded from the production application artifact.

The public provider read exposes `apiConfigured` and `checkoutConnected` booleans. Configured credentials can be reported while provider execution is still being prepared. The current wallet adapters report `checkoutConnected=false`. The customer UI shows this configuration state before submission and reloads pending, failed, or paid states from authenticated checkout/order reads.

For the device check, connect the customer debug app and API to the same isolated staging database, sign in as a staging customer, and add a published product with valid media. Select an owned home address with coordinates and review the quote. Submit and open Orders to see pending confirmation. Restart the app and refresh Orders. Send a signed staging event using the displayed checkout ID as both `checkoutId` and `paymentReference`, the stored checkout amount as `amountCfa`, `currency=XOF`, a unique `eventId`, and `status=VALIDATED`. Refresh Orders and verify one paid order. Repeat the same signed body with a fresh signature timestamp and verify the same order. Use a separate checkout with `status=FAILED` to verify the failed state. Device and deployed staging checks require authorized environment access.


### Checkout and payment verification results

The local verification on 2026-11-07 passed 44 API tests, the production JAR build, and both customer app compilation targets.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest' --tests '*CustomerCheckoutRestartTest' --tests '*PaymentSimulationGuardTest' --tests '*PaymentWebhookServiceTest' --tests '*PendingPaymentCheckoutServiceTest' --tests '*OrderFulfillmentPersistenceServiceTest' --tests '*OrderProcessingTest' --tests '*CommerceServiceTest' --tests '*PlatformCommerceServiceTest' --tests '*AuthControllerSecurityTest' bootJar
```

From the Sequo repository, the compiled targets were checked with `./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64`. The existing edit in `DeliveryPricingTest.kt` contains a split function name, so the customer unit-test suite remains blocked by that source syntax error. Device and deployed staging checks remain unverified.

The restart test starts the API twice against a temporary file-backed H2 database and confirms that pending checkout data, destination snapshot, and retry identity survive. The production artifact check confirms that checkout fixture classes and their webhook credential are absent. The simulator guard test rejects enablement under the production profile even with `sequo.security.allow-dev-defaults=true`.


## Merchant preparation exercise

Contract clauses 30, 118, and 119 cover receiving the merchant's order, preparing it, and reporting readiness. The Vendor queue uses the authenticated merchant membership and the existing fulfillment routes.

`GET /api/merchant/sub-orders?merchantId={merchantId}` returns paid order lines for that merchant, including product name, quantity, unit/line amounts, and any saved food option label. Detail and action responses use the same fields. Vendor displays these quantities and the server's response/packing deadlines. New checkout payloads preserve selected option names for preparation. Older payloads and responses remain readable with optional line/option fields.

The server requires a verified paid, active parent order before merchant actions. It serializes actions on the sub-order row. Repeating accept, start preparation, or ready returns the recorded state and preserves timestamps. Repeating ready with a different package count is rejected. Merchant preparation and dispatch reject unpaid parent orders, including legacy ready records.

The local regression submits a two-product basket (quantities 2 and 3, including Plantain), confirms payment through the signed fixture webhook, and follows the same order through merchant acceptance, preparation, ready, operations readback, and dispatch. It also exercises concurrent repeated actions, forged merchant IDs, customer-role denial, and unpaid action denial.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*MerchantFulfillment*Test' --tests '*DeliveryReadinessDispatchServiceTest' --tests '*DeliveryMissionControllerTest' --tests '*CustomerCartCheckoutHttpTest' --tests '*CustomerCheckoutRestartTest' --tests '*PendingPaymentCheckoutServiceTest' --tests '*OrderFulfillmentPersistenceServiceTest' --tests '*MerchantCatalogHttpTest'
```

From SequoVendor, run `./gradlew --offline :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64`. The Vendor wire tests verify authenticated merchant requests, line quantities, food option labels, the preparation target, ready package count, and server-error propagation.

For the unverified device/staging check, use the isolated setup from the payment exercise. Sign into Vendor as the merchant for that paid order. Refresh Orders, open the order, and compare product quantities and options with the customer's submitted basket. Check the displayed packing target. Tap Accept, Start preparation, enter the actual logical package count, and tap Mark ready. Refresh detail and confirm `PACKED_READY`. An authorized administrator can read `GET /api/merchant/sub-orders/{id}` and invoke `POST /api/delivery/missions/dispatch-ready`; the resulting mission must carry the same `orderId` and `merchantSubOrderId`. Repeat each action and check stable timestamps. Sign into the other merchant account and verify queue, detail, and action denial. Repeat with a pending checkout and verify that it produces no actionable merchant sub-order.

Authenticated Vendor sessions display their latest server snapshot and an error state on refresh failure. Demo orders are confined to local mode, and actions are disabled while the queue or detail is offline. A successful detail refresh restores actions.


Local verification on 2026-11-07 passed 57 API tests and 14 Vendor host tests, plus Vendor Android and iOS simulator compilation. The API suite includes migration-backed schema validation. That validation exposed a JPEG payload mapping mismatch; the entity now explicitly uses the existing PostgreSQL `bytea` column definition. The preparation flow uses the existing server targets of 24 hours for seller response and 48 hours for packing.


## Rider home-delivery and tracking exercise

Contract clauses 13, 36, 105, 114, and 121–124 cover rider missions, pickup, delivery validation, history, and customer/operations tracking. The local HTTP journey uses persisted customer, merchant, administrator, and two courier accounts. The courier account IDs intentionally differ from their login user IDs.

The mission acceptance route locks its row before claiming an offered mission. Repeating acceptance by the winning rider returns the recorded state. Pickup and delivery use the existing idempotency keys. Delivery completion holds the mission lock across PIN verification, completion, and order lifecycle changes. Wrong PIN attempts are recorded, and a repeated successful completion consumes the PIN once.

Rider reads resolve courier membership from the authenticated login. They include completed missions for history, actual package count, seller name, and the saved destination area. Full home destination contact details are exposed to the assigned rider during an active mission. Completed history omits those details. Legacy records remain readable with optional route fields.

Customer order list/detail responses include sanitized `deliveries` from persisted mission data. Each has the delivery code, state, timestamps, and progress fields. Customer tracking excludes courier proof metadata and recipient contact data. Existing authenticated admin delivery reads use the same persisted mission state and list recently updated missions first.

The local fixture verifies exactly one winner from concurrent rider acceptance, unauthorized pickup denial, missing pickup proof, a wrong PIN, repeated pickup/completion, a stale step after completion, final rider history, customer ownership denial, and matching customer/admin states at acceptance, pickup, and completion.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*CustomerCartCheckoutHttpTest' --tests '*CustomerCheckoutRestartTest' --tests '*DeliveryMission*Test' --tests '*DeliveryPinServiceTest' --tests '*DeliveryFulfillmentWorkflowTest' --tests '*DeliveryReadinessDispatchServiceTest' --tests '*OrderDeliveryLifecycleNotificationTest' --tests '*AdminWebControllerAuthorizationTest' --tests '*AdminWebReadHttpTest'
```

From SequoRider, run `./gradlew --offline :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64`. The loopback HTTP tests verify authenticated mission reads, actual route/package data, completed history, JSON proof requests, stable retry keys, and propagation of rejected/offline requests. From Sequo, run `./gradlew --offline :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64`. Its existing pricing test edit still prevents the customer unit-test suite from compiling.

From sequo-admin, run `node --experimental-strip-types --test tests/deliveryReads.test.mjs`, `npm run type-check`, and `npm run build-only`. These check that refreshed API states render for the same order and that failed authentication reaches the existing error handling.

For device/staging verification, configure debug builds of Sequo and SequoRider to use the same isolated API through each app's existing `core/network/NetworkConfig.kt` base URL. Set sequo-admin's existing `VITE_SEQUO_API_BASE_URL` environment value to that isolated API. Use staging accounts for customer, merchant, administrator, and two riders whose courier records point to their login IDs through `couriers.user_id`. Complete the paid merchant-preparation exercise, then offer its dispatched mission. Refresh both Rider accounts and attempt acceptance together. Verify one winner and one rejection. On the winning device, check the package count and destination, submit pickup with an empty proof note to verify rejection, then submit the actual custody note.

As administrator, issue a six-digit delivery PIN through `POST /api/delivery/missions/{missionId}/delivery-pin` using an expiry in the future. Give the fixture PIN to the staging customer. Try a wrong PIN on the winning Rider device, then use the correct PIN and repeat completion. Refresh customer Orders and admin Deliveries after each transition. Verify completed Rider history after app restart, and verify that the other rider cannot open the claimed mission. Disconnect the winning device while refreshing and confirm the offline state, disabled actions, and absence of demo rows; reconnect and refresh to restore actions.

Device and deployed staging checks remain unverified. The local exercise validates persisted state and role boundaries without provider execution.


Local Task 12 verification on 2026-11-07 passed 48 API tests, 5 Rider host tests, 2 admin delivery-rendering tests, Rider/customer Android and iOS simulator compilation, the admin type check, and the admin production build. The customer unit-test suite remains blocked by the existing split function name in `DeliveryPricingTest.kt`. Physical-device and deployed staging checks remain unverified. Rider fleet, service-zone and KYC fields display unavailable when no authoritative profile value is supplied.
