# API Data Source Audit

This note tracks whether API responses come from the database, from deterministic backend logic, or from intentionally generic/static responses.

## Connected Database

In Docker/production mode, the API is configured to use PostgreSQL:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB}
SPRING_DATASOURCE_USERNAME=${POSTGRES_USER}
SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
spring.flyway.enabled=true
```

With the current `docker-compose.yml`, the database service is named `postgres`, stores data in the `postgres-data` Docker volume, and is reachable by the API container at:

```text
jdbc:postgresql://postgres:5432/${POSTGRES_DB}
```

On the live VPS, the exact database name/user/password are in the server `.env`, not in Git. Because SSH is currently unavailable, the exact live `.env` values cannot be confirmed from the machine, but the deployed API is configured to use a real PostgreSQL database, not an in-memory database.

## Summary

Most controller endpoints call Spring services backed by `JpaRepository` repositories and therefore read/write real database tables.

The main exceptions are:

| Area | Status | Explanation |
| --- | --- | --- |
| Pricing calculations | Real DB plus safe fallback | In Spring/prod, delivery pricing loads active settings from `delivery_pricing_settings`. Pure unit tests can still use the service fallback defaults. |
| Payment provider actions | Partial external integration | Payment attempts/cancel/refund return deterministic provider messages and references; payment webhooks are persisted to DB, but paid provider adapters are not fully wired. |
| Password reset notification | Partial | `/api/auth/forgot-password` validates/rate-limits and checks DB, but returns a generic message and does not send email/SMS yet. |
| Error messages/status responses | Static by design | Validation errors, 401/404/409 messages, and security-safe generic messages are returned from code. |
| Dev seed data | Optional SQL seed | `src/main/resources/db/seed/dev_seed_data.sql` exists for development only; it is not automatically used by production Docker Compose. |

## Endpoint Data Source Map

| Endpoint group | Representative endpoints | Data source | Notes |
| --- | --- | --- | --- |
| Auth | `POST /api/auth/signup`, `POST /api/auth/login`, `GET /api/auth/me`, `GET /api/auth/sessions` | Real DB | Uses `UserRepository`, `RefreshSessionRepository`, `SocialIdentityRepository`, and `MerchantMembershipRepository`. Tokens are generated in code, but users/sessions/scopes are persisted. |
| Auth password reset | `POST /api/auth/forgot-password`, `POST /api/auth/reset-password` | Real DB plus static generic response | Looks up users and stores reset token hash in DB. `forgot-password` intentionally always returns a generic message for valid email format to avoid account enumeration. Email/SMS sending is not implemented yet. |
| Orders | `POST /api/orders/process`, `GET /api/orders`, `GET /api/orders/{orderId}` | Real DB for persistence/read; DB-backed pricing; code logic for payment decision | `GET /api/orders` and `GET /api/orders/{orderId}` read `customer_orders`, lines, events, pricing snapshots, and merchant sub-orders. `POST /process` loads active delivery pricing settings from DB, then persists accepted orders or pending payment checkouts. |
| Customer pickup | `POST /api/orders/{orderId}/pickup-confirmations`, `GET /api/orders/{orderId}/pickup-confirmations` | Real DB | Uses customer order and pickup confirmation repositories. |
| Merchant fulfillment | `/api/merchant/sub-orders/**` | Real DB | Reads/writes merchant sub-orders, SLA fields, and escalation records. |
| Delivery missions | `/api/delivery/missions/**` | Real DB | Reads/writes delivery missions, pins, courier availability, problem resolutions, dispatch runs, and idempotency records. Some transitions are business logic, but state is persisted. |
| Delivery tracking | `GET /api/delivery/tracking/{deliveryCode}` | Real DB | Finds mission by delivery code and order id. Returns 404 when no matching persisted mission exists. |
| Relay parcels | `/api/relay/parcels/**` | Real DB | Reads/writes relay parcels, pickup codes, custody events, and storage fee assessments. |
| Returns | `/api/returns/**` | Real DB | Reads/writes return requests and updates order state where relevant. Also checks order lines/categories from DB. |
| Settlements | `/api/settlements/**` | Real DB | Reads/writes merchant payout accruals and settlement ledger entries. |
| Payment webhooks | `POST /api/payments/webhooks/{provider}` | Real DB | Stores webhook events and publishes reconciliation events. Request validation is real; provider-side integration remains partial. |
| Product related | `GET /api/catalog/products/{id}/related` | Real DB | Queries `products` by category/status/created date. No hardcoded product list is returned by the endpoint. |
| Product/media policy services | No public upload controller found in this audit | Code policy, storage abstraction | Media validation checks content type and file bytes in code. A public controller for upload was not found in the current endpoint scan. |
| Notifications devices | `/api/notifications/devices/**` | Real DB | Stores encrypted FCM tokens and token statuses. |
| Notifications preferences | `/api/notifications/preferences/**` | Real DB | Reads/writes notification preferences, falls back to defaults in code when no DB row exists. |
| Notifications inbox | `/api/notifications/inbox/**` | Real DB | Reads/writes notification messages and delivery records. |
| Hub mobile | `/api/hub/**` | Real DB | Reads/writes lockers, opening hours, opening-hour exceptions, hub controls, parcels, pickup codes, and storage fees. Some summary fields are calculated from DB records. |
| Account/preferences | `/api/account/deletion-requests`, `/api/preferences` | Real DB | Stores account deletion requests and user app preferences. |
| Merchant commissions | `/api/commissions/merchant-overrides/**` | Real DB plus default policy | Overrides are stored in DB. If no override exists, service returns default commission rate from code. |
| Consolidations | `/api/consolidations/**` | Real DB | Reads/writes consolidation manifests and transition state. |
| Admin monitoring | `/api/admin/monitoring/operations` | Real DB plus aggregation logic | Aggregates data from operational repositories into monitoring snapshots. |

## Important Details For Testing With Postman

If an endpoint returns an empty list, it usually means the live PostgreSQL database has no rows for that authenticated user or query, not that the endpoint is fake.

For example:

```text
GET /api/orders
```

is backed by:

```text
OrderController
-> OrderFulfillmentPersistenceService.listForCustomer(userId)
-> CustomerOrderRecordRepository.findByCustomerIdOrderByCreatedAtDesc(userId)
-> customer_orders table
```

So it returns only orders persisted for the authenticated user id from the access token.

## Fictitious Or Placeholder Data

The API code still contains placeholder-like behavior in some integration areas, but not as fake `GET` endpoint lists:

| Item | Where | Meaning |
| --- | --- | --- |
| Payment provider messages | `PaymentProcessing.kt` | Provider attempt/cancel/refund behavior is deterministic until real paid adapters are fully connected. |
| Delivery pricing fallback | `DeliveryPricing.kt` | Fallback values remain for pure unit tests and safety, but production/Spring usage loads the active row from `delivery_pricing_settings`. |
| Password reset success message | `AuthController.kt` | Generic message is a security pattern, not evidence that email sending is implemented. |
| Dev seed SQL | `src/main/resources/db/seed/dev_seed_data.sql` | Optional development seed data, not automatically loaded in production. |
| Placeholder URLs/docs | Documentation/tests | Some placeholder URLs are documented separately in `docs/PLACEHOLDER_REPLACEMENT.md`. |

## Practical Conclusion

The live API is not just returning hardcoded sample lists. The main operational `GET` endpoints query real PostgreSQL tables through Spring Data JPA repositories.

However, the database may currently have little or no production data because the mobile apps are not fully connected yet and many records are created only after authenticated workflows run successfully.
