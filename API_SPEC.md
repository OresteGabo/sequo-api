# API Specification

This document describes the target REST API shape for Sequo API. Controllers are not implemented yet, so this is a production contract guide for future implementation and OpenAPI generation.

## API Conventions

| Convention | Rule |
| --- | --- |
| Base path | `/api/v1` |
| Transport | HTTPS outside local development |
| Auth | `Authorization: Bearer <access_token>` |
| Content type | JSON, UTF-8 |
| Currency | CFA integer minor units, no floating point money |
| Time | ISO-8601 UTC instants |
| Idempotency | Required for checkout, payment confirmation, refunds, returns, payouts |
| Pagination | Cursor-based for large operational lists |
| Errors | Stable application error codes with user-safe messages |

## Required Headers

| Header | Required for | Purpose |
| --- | --- | --- |
| `Authorization` | Protected endpoints | JWT access token |
| `Idempotency-Key` | Mutating financial/workflow endpoints | Prevent duplicate side effects |
| `X-Request-Id` | All clients when available | Trace request across logs |
| `X-Client-Version` | Mobile clients | Operational debugging |
| `X-Device-Id` | Auth/session endpoints | Session risk and revocation |

## Response Envelope

Successful response:

```json
{
  "data": {},
  "meta": {
    "requestId": "req_123",
    "apiVersion": "v1"
  }
}
```

Error response:

```json
{
  "error": {
    "code": "ORDER_INVALID_TRANSITION",
    "message": "This order cannot move to the requested status.",
    "details": {}
  },
  "meta": {
    "requestId": "req_123",
    "apiVersion": "v1"
  }
}
```

## Error Code Families

| Prefix | Meaning |
| --- | --- |
| `AUTH_*` | Login, token, session, role, account status |
| `VALIDATION_*` | Request body, query, path, enum, amount, date |
| `CATALOG_*` | Product, stock, delivery eligibility, price snapshot |
| `PRICING_*` | Quote, subscription, distance, fee calculation |
| `BARGAINING_*` | Attempt limit, locked price, expired offer, disabled bargaining |
| `ORDER_*` | Order state, ownership, cancellation, merchant action |
| `DELIVERY_*` | Courier mission, PIN, pickup, drop-off, shortfall |
| `RELAY_*` | Relay location, locker, parcel custody, return drop-off |
| `WALLET_*` | Payment intent, provider callback, refund, reconciliation |
| `RETURN_*` | Eligibility, receipt, refund trigger, window expiry |
| `SETTLEMENT_*` | Ledger, payout, hold, commission, reconciliation |
| `ADMIN_*` | Override, configuration, audit, permission |

## Endpoint Families

### Health

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/health` | No | API liveness |
| `GET` | `/ready` | No/internal | Dependency readiness |

### Authentication

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/auth/login` | No | Password/OTP/social login entry point |
| `POST` | `/auth/refresh` | Refresh token | Rotate refresh token and issue access token |
| `POST` | `/auth/logout` | Yes | Revoke current session |
| `POST` | `/auth/logout-all` | Yes | Revoke all sessions for actor |
| `GET` | `/auth/me` | Yes | Current user profile and roles |

### Users And Addresses

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/users/me` | Yes | Current user profile |
| `PATCH` | `/users/me` | Yes | Update own profile |
| `GET` | `/users/me/addresses` | Customer | List saved addresses |
| `POST` | `/users/me/addresses` | Customer | Create address with neighborhood/landmark |
| `PATCH` | `/users/me/addresses/{addressId}` | Customer owner | Update address |
| `DELETE` | `/users/me/addresses/{addressId}` | Customer owner | Archive address |

### Merchants And Catalog

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/merchants` | Optional | Browse public active merchants |
| `GET` | `/merchants/{merchantId}` | Optional | Merchant storefront |
| `POST` | `/admin/merchants` | Admin | Create/approve merchant |
| `PATCH` | `/admin/merchants/{merchantId}/commission` | Admin | Set commission between 5% and 15% |
| `GET` | `/merchant/products` | Merchant | Own catalog |
| `POST` | `/merchant/products` | Merchant | Create product |
| `PATCH` | `/merchant/products/{productId}` | Merchant owner | Update product |
| `PATCH` | `/merchant/products/{productId}/bargaining` | Merchant owner | Toggle bargaining |
| `POST` | `/merchant/products/{productId}/media/live-captures` | Merchant owner | Attach real-time camera evidence |
| `POST` | `/merchant/products/{productId}/media/catalog-reference` | Merchant owner/admin | Attach approved generic catalog image |
| `POST` | `/merchant/products/{productId}/customization-groups` | Merchant owner | Create food topping/option group |
| `PATCH` | `/merchant/products/{productId}/customization-groups/{groupId}` | Merchant owner | Update topping/option group |

### Cooperative Markets

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/cooperatives` | Optional | Browse cooperative markets |
| `GET` | `/cooperatives/{cooperativeId}` | Optional | Cooperative storefront |
| `POST` | `/cooperatives/requests` | Merchant owner | Request cooperative creation or membership |
| `GET` | `/merchant/cooperatives/requests` | Merchant owner | Read own cooperative requests |
| `POST` | `/admin/cooperatives` | Admin | Create cooperative |
| `POST` | `/admin/cooperatives/requests/{requestId}/approve` | Admin | Approve cooperative request |
| `POST` | `/admin/cooperatives/requests/{requestId}/reject` | Admin | Reject cooperative request |
| `POST` | `/admin/cooperatives/{cooperativeId}/members` | Admin | Add merchant member |
| `DELETE` | `/admin/cooperatives/{cooperativeId}/members/{merchantId}` | Admin | Remove member |

### Pricing And Checkout

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/pricing/quote` | Customer | Price cart/order before payment |
| `POST` | `/checkout` | Customer | Create checkout session and payment intent |
| `GET` | `/checkout/{checkoutId}` | Customer owner | Read checkout status |
| `POST` | `/checkout/{checkoutId}/confirm` | Customer owner | Confirm selected wallet payment flow |
| `GET` | `/subscriptions/tiers` | Optional | List active subscription tiers and delivery discounts |
| `GET` | `/users/me/subscription` | Customer | Read own subscription status |
| `POST` | `/users/me/subscription` | Customer | Start or change monthly subscription |
| `GET` | `/users/me/referral-credit` | Customer | Read delivery-only referral credit balance |

### Bargaining

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/bargaining/offers` | Customer | Submit price proposal |
| `GET` | `/bargaining/sessions/{sessionId}` | Participant | Read bargaining session |
| `POST` | `/merchant/bargaining/offers/{offerId}/accept` | Merchant owner | Accept offer and lock price |
| `POST` | `/merchant/bargaining/offers/{offerId}/reject` | Merchant owner | Reject offer |
| `POST` | `/merchant/bargaining/offers/{offerId}/counter` | Merchant owner | Counter-offer |
| `POST` | `/bargaining/offers/{offerId}/accept-counter` | Customer | Accept counter-offer |

### Orders

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/orders` | Customer | Own orders |
| `GET` | `/orders/{orderId}` | Owner/support/admin | Order detail |
| `GET` | `/orders/{orderId}/tracking` | Customer owner/support/admin | Delivery tracking, relay instructions, safe proof status |
| `POST` | `/orders/{orderId}/cancel` | Customer/support/admin | Cancel when policy allows |
| `GET` | `/merchant/orders` | Merchant | Own merchant orders/sub-orders |
| `POST` | `/merchant/orders/{subOrderId}/accept` | Merchant owner | Accept merchant sub-order |
| `POST` | `/merchant/orders/{subOrderId}/reject` | Merchant owner | Reject merchant sub-order |
| `POST` | `/merchant/orders/{subOrderId}/start-preparation` | Merchant owner | Mark accepted order as being prepared |
| `POST` | `/merchant/orders/{subOrderId}/ready` | Merchant owner | Mark ready for pickup/deposit |
| `POST` | `/merchant/orders/{subOrderId}/handoff` | Merchant owner/courier | Confirm courier collected packed package |

### Delivery And Relay

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/courier/missions` | Courier | Offered/assigned missions |
| `GET` | `/courier/missions/{missionId}` | Courier owner/support/admin | Mission details and current state |
| `POST` | `/courier/missions/{missionId}/accept` | Courier | Accept mission |
| `POST` | `/courier/missions/{missionId}/pickup` | Courier | Mark picked up with seller handoff proof |
| `POST` | `/courier/missions/{missionId}/deliver` | Courier | Complete with proof/PIN |
| `POST` | `/courier/missions/{missionId}/deposit-relay` | Courier | Deposit relay-bound parcel at Point de Relai |
| `POST` | `/courier/missions/{missionId}/problem` | Courier | Report pickup/drop-off/routing problem |
| `GET` | `/relay/parcels` | Relay partner | Parcels at assigned relay |
| `POST` | `/relay/parcels/deposit` | Relay partner | Receive parcel or return |
| `POST` | `/relay/parcels/{parcelId}/validate-pickup-code` | Relay partner | Validate pickup numeric code or QR payload |
| `POST` | `/relay/parcels/{parcelId}/release` | Relay partner | Release to customer/Sequo agent |
| `POST` | `/relay/parcels/{parcelId}/problem` | Relay partner | Report lost, damaged, delayed, or identity mismatch case |
| `POST` | `/admin/delivery-missions/{missionId}/reassign` | Admin/support | Reassign delivery mission to another courier |
| `POST` | `/admin/delivery-missions/{missionId}/cancel` | Admin/support | Cancel mission with audited reason |
| `GET` | `/admin/relay/parcels/delayed` | Admin/support | List delayed relay parcels for fee/return workflow |

### Returns

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/orders/{orderId}/returns` | Customer owner | Create return request within 72 hours |
| `GET` | `/returns/{returnId}` | Participant/support/admin | Return status |
| `POST` | `/relay/returns/{returnId}/receive` | Relay partner | Validate customer drop-off |
| `POST` | `/admin/returns/{returnId}/physical-receipt` | Admin | Confirm Sequo physical receipt |
| `POST` | `/admin/returns/{returnId}/refund` | Finance admin | Trigger refund |

### Wallets

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/wallets/me` | Yes | Own wallet summary |
| `GET` | `/wallets/me/transactions` | Yes | Own wallet history |
| `POST` | `/wallets/payment-intents` | Customer | Create Yas/Moov payment intent |
| `POST` | `/webhooks/wallets/yas` | Provider | Yas Togo callback |
| `POST` | `/webhooks/wallets/moov` | Provider | Moov Africa callback |

No endpoint should support cash withdrawal.

### Settlements And Admin Finance

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/merchant/settlements` | Merchant owner | Own settlement records |
| `GET` | `/admin/settlements` | Finance admin | Settlement search |
| `POST` | `/admin/payout-batches` | Finance admin | Create payout batch |
| `POST` | `/admin/payout-batches/{batchId}/approve` | Finance admin | Approve payout batch |
| `POST` | `/admin/settlements/{settlementId}/hold` | Finance admin | Place hold with reason |
| `POST` | `/admin/settlements/{settlementId}/release` | Finance admin | Release hold |
| `GET` | `/admin/monitoring/operations` | Admin/support | Operational dashboard for orders, returns, relays, payouts |
| `GET` | `/admin/monitoring/delivery-capacity` | Admin/support | Courier capacity and subscriber-priority delivery view |

## Idempotency Rules

Required idempotency keys:

- Checkout session creation.
- Payment intent creation.
- Provider webhook processing.
- Order creation after payment.
- Bargaining acceptance.
- Return request creation.
- Physical receipt confirmation.
- Refund trigger.
- Payout batch creation and approval.

The API should store:

- Key.
- Actor or provider identity.
- Request hash.
- Response body or terminal result.
- Expiry.
- Conflict state when same key is reused with a different request hash.
