# Endpoint audit

This inventory was reviewed against the Spring MVC handler mappings on 2026-09-30.
The authoritative runtime check is `EndpointMappingAuditTest`, which fails when two
active handlers expose the same HTTP method and path pair.

## Authentication

| Method | Path |
| --- | --- |
| POST | `/api/auth/signup` |
| POST | `/api/auth/login` |
| POST | `/api/auth/login/social` |
| POST | `/api/auth/refresh` |
| POST | `/api/auth/logout` |
| POST | `/api/auth/logout-all` |
| GET | `/api/auth/sessions` |
| GET | `/api/auth/me` |
| DELETE | `/api/auth/sessions/{sessionId}` |
| POST | `/api/auth/forgot-password` |
| POST | `/api/auth/reset-password` |

## Catalog, cart and bargaining

| Method | Path |
| --- | --- |
| GET | `/api/catalog/home` |
| GET | `/api/catalog/categories` |
| GET | `/api/catalog/merchants` |
| GET | `/api/catalog/products` |
| GET | `/api/catalog/products/{productId}/related` |
| POST | `/api/catalog/products` |
| PATCH | `/api/catalog/products/{productId}` |
| POST | `/api/catalog/products/{productId}/archive` |
| GET | `/api/cart` |
| PUT | `/api/cart/items` |
| DELETE | `/api/cart/items/{itemId}` |
| DELETE | `/api/cart` |
| GET | `/api/bargaining/threads` |
| POST | `/api/bargaining/threads` |
| POST | `/api/bargaining/threads/{threadId}/offers` |
| GET | `/api/catalog/products/{productId}/customizations` |
| PUT | `/api/catalog/products/{productId}/customizations` |

## Customer account and notifications

| Method | Path |
| --- | --- |
| GET | `/api/customer/profile` |
| PUT | `/api/customer/profile` |
| GET | `/api/customer/addresses` |
| POST | `/api/customer/addresses` |
| PUT | `/api/customer/addresses/{addressId}` |
| GET | `/api/notifications/inbox` |
| PATCH | `/api/notifications/inbox/{messageId}/read` |
| POST | `/api/notifications/inbox/{messageId}/archive` |
| DELETE | `/api/notifications/inbox/{messageId}/archive` |
| POST | `/api/notifications/devices/fcm` |
| DELETE | `/api/notifications/devices/{appFamily}/{deviceId}` |
| GET | `/api/notifications/preferences/{appFamily}/effective` |
| PUT | `/api/notifications/preferences/{appFamily}` |
| GET | `/api/subscriptions/plans` |
| GET | `/api/subscriptions/me` |
| POST | `/api/subscriptions` |
| GET | `/api/referrals/credits` |
| POST | `/api/referrals` |
| GET | `/api/payments/providers` |
| GET | `/api/cooperatives` |
| POST | `/api/cooperatives` |
| POST | `/api/cooperatives/{cooperativeId}/members` |
| POST | `/api/cooperatives/{cooperativeId}/review` |

## Orders, returns and payments

| Method | Path |
| --- | --- |
| POST | `/api/orders/process` |
| GET | `/api/orders` |
| GET | `/api/orders/{orderId}` |
| POST | `/api/orders/{orderId}/pickup-confirmations` |
| GET | `/api/orders/{orderId}/pickup-confirmations` |
| POST | `/api/returns` |
| GET | `/api/returns` |
| GET | `/api/returns/{returnId}` |
| GET | `/api/returns/orders/{orderId}` |
| POST | `/api/returns/{returnId}/relay-dropoff` |
| POST | `/api/returns/{returnId}/physical-receipt` |
| POST | `/api/returns/{returnId}/refund` |
| POST | `/api/payments/webhooks/{provider}` |

## Merchant operations and settlements

| Method | Path |
| --- | --- |
| GET | `/api/merchant/sub-orders` |
| POST | `/api/merchant/sub-orders` |
| POST | `/api/merchant/sub-orders/sla/publish-overdue` |
| GET | `/api/merchant/sub-orders/{subOrderId}` |
| GET | `/api/merchant/sub-orders/{subOrderId}/sla` |
| GET | `/api/merchant/sub-orders/{subOrderId}/escalations` |
| POST | `/api/merchant/sub-orders/{subOrderId}/escalations` |
| POST | `/api/merchant/sub-orders/{subOrderId}/accept` |
| POST | `/api/merchant/sub-orders/{subOrderId}/start-preparation` |
| POST | `/api/merchant/sub-orders/{subOrderId}/mark-packed` |
| POST | `/api/merchant/sub-orders/{subOrderId}/handoff` |
| POST | `/api/merchant/sub-orders/{subOrderId}/reject` |
| GET | `/api/commissions/merchant-overrides/{merchantId}` |
| PUT | `/api/commissions/merchant-overrides/{merchantId}` |
| DELETE | `/api/commissions/merchant-overrides/{merchantId}` |
| GET | `/api/settlements/merchant-payouts` |
| GET | `/api/settlements/ledger` |
| POST | `/api/settlements/merchant-payouts/evaluate-eligible` |

## Delivery missions and tracking

| Method | Path |
| --- | --- |
| POST | `/api/delivery/missions` |
| GET | `/api/delivery/missions` |
| GET | `/api/delivery/missions/{missionId}` |
| POST | `/api/delivery/missions/dispatch-ready` |
| POST | `/api/delivery/missions/expire-stale` |
| POST | `/api/delivery/missions/couriers/pause` |
| POST | `/api/delivery/missions/couriers/unpause` |
| GET | `/api/delivery/missions/couriers/{courierId}/availability` |
| POST | `/api/delivery/missions/{missionId}/assign` |
| POST | `/api/delivery/missions/{missionId}/reassign` |
| POST | `/api/delivery/missions/{missionId}/offer` |
| POST | `/api/delivery/missions/{missionId}/delivery-pin` |
| POST | `/api/delivery/missions/{missionId}/cancel` |
| POST | `/api/delivery/missions/{missionId}/force-problem` |
| POST | `/api/delivery/missions/{missionId}/resolve-problem` |
| GET | `/api/delivery/missions/{missionId}/problem-resolutions` |
| POST | `/api/delivery/missions/{missionId}/accept` |
| POST | `/api/delivery/missions/{missionId}/pickup` |
| POST | `/api/delivery/missions/{missionId}/deliver` |
| POST | `/api/delivery/missions/{missionId}/relay-deposit` |
| POST | `/api/delivery/missions/{missionId}/relay-release` |
| POST | `/api/delivery/missions/{missionId}/problem` |
| GET | `/api/delivery/tracking/{deliveryCode}` |

## Consolidation and relay parcels

| Method | Path |
| --- | --- |
| POST | `/api/consolidations` |
| GET | `/api/consolidations/{manifestId}` |
| GET | `/api/consolidations/order/{orderId}` |
| POST | `/api/consolidations/{manifestId}/seller-packages/{subOrderId}/ready` |
| GET | `/api/consolidations/{manifestId}/tracking` |
| POST | `/api/consolidations/{manifestId}/seller-packages/{subOrderId}/collected` |
| POST | `/api/consolidations/{manifestId}/transitions` |
| POST | `/api/consolidations/{manifestId}/dispatch-final-package` |
| POST | `/api/relay/parcels` |
| GET | `/api/relay/parcels` |
| GET | `/api/relay/parcels/{parcelId}` |
| POST | `/api/relay/parcels/{parcelId}/pickup-code` |
| POST | `/api/relay/parcels/{parcelId}/release` |
| POST | `/api/relay/parcels/{parcelId}/problem` |
| POST | `/api/relay/parcels/{parcelId}/return-to-seller` |
| POST | `/api/relay/parcels/storage-fees/assess` |
| GET | `/api/relay/parcels/storage-fees` |

## Hub and administration

| Method | Path |
| --- | --- |
| POST | `/api/hub/scan/resolve` |
| GET | `/api/hub/summary` |
| POST | `/api/hub/lockers/{lockerId}/availability` |
| GET | `/api/hub/opening-hours` |
| PUT | `/api/hub/opening-hours` |
| GET | `/api/hub/control-state` |
| POST | `/api/hub/control-state` |
| GET | `/api/hub/control-state/history` |
| POST | `/api/account/deletion-requests` |
| GET | `/api/preferences` |
| PATCH | `/api/preferences` |
| GET | `/api/admin/monitoring/operations` |

## Removed duplicate surfaces

- Removed `GET /api/v1/products/{id}/related`. The feature now uses the general catalog route and the shared `CommerceProductRepository`.
- Removed `GET /api/customer/orders`. The canonical customer order resource is `GET /api/orders`.
- Removed `GET /api/customer/notifications`. The canonical inbox resource is `GET /api/notifications/inbox`.
- Removed the isolated `ProductRepository`, `ProductRelatedService`, `ProductRelatedController`, and `SequoProductDto` implementation.

The removed routes were not referenced by application code or tests in this repository. They are intentionally not kept as aliases so that clients do not receive two response contracts for the same resource.
