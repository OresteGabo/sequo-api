# Database Schema

This is the target logical relational schema for Sequo API. PostgreSQL is the recommended database. Exact column types can be refined in migrations, but the entities, constraints, and financial audit model should remain stable.

## Schema Principles

- Use UUID primary keys internally.
- Use human-readable operational IDs for orders, deliveries, deposits, returns, payments, and settlements.
- Store money as integer CFA amounts.
- Store immutable snapshots for paid orders.
- Use append-only ledgers for money movement.
- Use append-only events for workflow history.
- Use soft-delete/archive flags for business records that must remain auditable.
- Use optimistic locking on mutable aggregates.

## Core Enums

| Enum | Values |
| --- | --- |
| `user_status` | `PENDING`, `ACTIVE`, `LOCKED`, `SUSPENDED`, `DELETED` |
| `role_code` | `CUSTOMER`, `MERCHANT_OWNER`, `MERCHANT_STAFF`, `COURIER`, `RELAY_PARTNER`, `SUPPORT_AGENT`, `ADMIN`, `SUPER_ADMIN` |
| `merchant_status` | `PENDING_REVIEW`, `ACTIVE`, `PAUSED`, `SUSPENDED`, `REJECTED` |
| `merchant_type` | `LOGISTICS`, `STANDARD_RESTAURANT`, `SERVICE`, `COOPERATIVE_MEMBER` |
| `delivery_mode` | `STANDARD`, `EXPRESS`, `PROGRAMMED`, `CLICK_COLLECT`, `RELAY` |
| `wallet_provider` | `YAS_TOGO`, `MOOV_AFRICA`, `SEQUO_INTERNAL` |
| `order_status` | `DRAFT`, `PRICE_QUOTED`, `PAYMENT_PENDING`, `PAID`, `MERCHANT_PENDING`, `ACCEPTED`, `PREPARING`, `READY_FOR_PICKUP`, `IN_TRANSIT`, `RELAY_DEPOSITED`, `DELIVERED`, `RETURN_WINDOW_OPEN`, `RETURN_REQUESTED`, `REFUNDED`, `SETTLED`, `CANCELLED`, `REJECTED`, `DELIVERY_PROBLEM` |
| `return_status` | `REQUESTED`, `AWAITING_RELAY_DROPOFF`, `DROPPED_AT_RELAY`, `IN_SEQUO_COLLECTION`, `RECEIVED_BY_SEQUO`, `REFUND_APPROVED`, `REFUND_PENDING`, `REFUNDED`, `REJECTED`, `EXPIRED`, `DISPUTED` |
| `bargaining_status` | `OPEN`, `CUSTOMER_OFFERED`, `MERCHANT_COUNTERED`, `ACCEPTED`, `REJECTED`, `LOCKED_ATTEMPTS_EXHAUSTED`, `EXPIRED` |
| `payment_status` | `CREATED`, `PENDING_PROVIDER`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `CANCELLED`, `REFUNDED`, `PARTIALLY_REFUNDED` |
| `payout_status` | `ACCRUED`, `HELD_RETURN_WINDOW`, `HELD_DISPUTE`, `ELIGIBLE`, `BATCHED`, `APPROVED`, `SENT_TO_PROVIDER`, `PAID`, `FAILED`, `ADJUSTED` |

## Identity And Access

### `users`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `public_id` | Stable public user reference |
| `phone_e164` | Nullable, unique when present |
| `email` | Nullable, unique when present |
| `display_name` | User-facing name |
| `password_hash` | Nullable if password login disabled |
| `status` | `user_status` |
| `preferred_language` | Default `fr` |
| `created_at`, `updated_at` | Timestamps |
| `version` | Optimistic lock |

### `user_roles`

| Column | Notes |
| --- | --- |
| `user_id` | FK `users.id` |
| `role_code` | `role_code` |
| `scope_type` | Optional, for merchant/relay scoped roles |
| `scope_id` | Optional scoped entity ID |
| `granted_by` | FK `users.id` |
| `granted_at` | Timestamp |

Unique: `(user_id, role_code, scope_type, scope_id)`.

### `refresh_sessions`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `user_id` | FK |
| `token_hash` | Hashed refresh token |
| `family_id` | Session family for reuse detection |
| `device_id` | Client device ID |
| `user_agent_hash` | Privacy-preserving hint |
| `ip_hint` | Optional |
| `issued_at`, `expires_at` | Timestamps |
| `revoked_at` | Nullable |
| `replaced_by_session_id` | Nullable FK |

Index: `(user_id, revoked_at, expires_at)`.

## Profiles And Actors

### `customer_profiles`

| Column | Notes |
| --- | --- |
| `user_id` | PK/FK `users.id` |
| `default_wallet_provider` | `wallet_provider` |
| `created_at`, `updated_at` | Timestamps |

### `customer_addresses`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `customer_id` | FK `users.id` |
| `label` | Home/work/etc. |
| `country_code` | Default `TG` unless expansion |
| `city` | City |
| `neighborhood` | Required for local addressing |
| `landmark` | Required or strongly recommended |
| `notes` | Delivery notes |
| `latitude`, `longitude` | Nullable |
| `phone_e164` | Contact phone |
| `is_default` | Boolean |
| `archived_at` | Nullable |

### `merchants`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `merchant_code` | Human-readable code |
| `owner_user_id` | FK `users.id` |
| `name` | Merchant display name |
| `type` | `merchant_type` |
| `status` | `merchant_status` |
| `commission_rate_bps` | 500 to 1500, default 1500 |
| `wallet_provider` | Yas/Moov preference |
| `wallet_account_ref` | Provider account reference |
| `phone_public_policy` | `MASKED`, `PUBLIC`, `SEQUO_ONLY` |
| `created_at`, `updated_at` | Timestamps |

Constraint: `commission_rate_bps between 500 and 1500`.

### `merchant_staff`

| Column | Notes |
| --- | --- |
| `merchant_id` | FK |
| `user_id` | FK |
| `permissions` | JSONB or normalized table |
| `status` | Active/suspended |
| `created_at` | Timestamp |

Unique: `(merchant_id, user_id)`.

### `couriers`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `user_id` | FK |
| `status` | Active/unavailable/suspended |
| `vehicle_type` | Moto/car/bike/etc. |
| `kyc_status` | Review state |
| `wallet_provider` | Yas/Moov |
| `wallet_account_ref` | Provider reference |

### `relay_points`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `relay_code` | Human-readable code |
| `name` | Partner shop name |
| `operator_user_id` | FK `users.id` |
| `city`, `neighborhood`, `landmark` | Location |
| `latitude`, `longitude` | Nullable |
| `status` | Active/suspended |
| `wallet_provider` | Yas/Moov |
| `wallet_account_ref` | Provider reference |

### `relay_lockers`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `relay_point_id` | FK |
| `locker_code` | Example `C01` |
| `status` | `FREE`, `OCCUPIED`, `DELAYED`, `OUT_OF_SERVICE` |

Unique: `(relay_point_id, locker_code)`.

## Catalog And Cooperatives

### `products`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `merchant_id` | FK |
| `sku` | Merchant SKU |
| `name` | Product name |
| `description` | Text |
| `category_id` | FK |
| `base_price_cfa` | Merchant base price |
| `platform_margin_cfa` | Sequo margin |
| `stock_quantity` | Nullable for services |
| `stock_status` | In stock/low/out |
| `weight_grams` | Nullable |
| `bargaining_enabled` | Boolean |
| `returnable` | Boolean |
| `status` | Draft/active/paused/archived |
| `created_at`, `updated_at` | Timestamps |

### `product_variants`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `product_id` | FK |
| `variant_name` | Color/size/etc. |
| `base_price_cfa` | Optional override |
| `platform_margin_cfa` | Optional override |
| `stock_quantity` | Variant stock |
| `weight_grams` | Optional override |

### `product_delivery_modes`

| Column | Notes |
| --- | --- |
| `product_id` | FK |
| `delivery_mode` | `delivery_mode` |
| `enabled` | Boolean |

Unique: `(product_id, delivery_mode)`.

### `cooperative_markets`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `cooperative_code` | Human-readable code |
| `name` | Example `Marche de Mulhouse` |
| `city`, `neighborhood` | Location |
| `status` | Pending/active/suspended |
| `created_by_admin_id` | FK `users.id` |
| `created_at`, `updated_at` | Timestamps |

### `cooperative_members`

| Column | Notes |
| --- | --- |
| `cooperative_id` | FK |
| `merchant_id` | FK |
| `member_status` | Pending/active/suspended |
| `joined_at` | Timestamp |

Unique: `(cooperative_id, merchant_id)`.

## Subscriptions And Loyalty

### `subscription_tiers`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `tier_code` | Stable code |
| `name` | Display name |
| `monthly_price_cfa` | Monthly bill |
| `per_km_discount_bps` | Percentage discount, basis points |
| `eligible_delivery_modes` | JSONB or join table |
| `max_discount_cfa_per_month` | Nullable |
| `active` | Boolean |

### `customer_subscriptions`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `customer_id` | FK `users.id` |
| `tier_id` | FK |
| `status` | Active/cancelled/past_due/expired |
| `started_at`, `renews_at`, `cancelled_at` | Timestamps |

### `loyalty_multiplier_rules`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `min_months_active` | Tenure threshold |
| `multiplier_bps` | 10000 means 1.0 |
| `max_discount_cfa_per_order` | Nullable |
| `active` | Boolean |

## Orders And Pricing

### `checkout_quotes`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `customer_id` | FK |
| `quote_code` | Human-readable |
| `pricing_version` | Formula/config version |
| `currency` | CFA |
| `raw_distance_meters` | Nullable |
| `billable_km` | Nullable |
| `subtotal_cfa` | Item subtotal |
| `service_fee_cfa` | Service fees |
| `delivery_fee_before_discount_cfa` | Standard fee |
| `subscription_discount_cfa` | Discount |
| `loyalty_discount_cfa` | Discount |
| `customer_delivery_fee_cfa` | Final delivery fee |
| `total_cfa` | Customer total |
| `expires_at` | Quote TTL |
| `created_at` | Timestamp |

### `orders`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `order_code` | `CMD-...`, unique |
| `customer_id` | FK |
| `cooperative_id` | Nullable FK |
| `status` | `order_status` |
| `currency` | CFA |
| `total_cfa` | Paid/authorized total |
| `service_fee_cfa` | Snapshot |
| `delivery_fee_cfa` | Customer-facing |
| `courier_fee_cfa` | Actual/estimated courier cost |
| `delivery_shortfall_cfa` | Sequo expense |
| `payment_intent_id` | FK |
| `delivered_at` | Nullable |
| `return_window_expires_at` | Nullable |
| `created_at`, `updated_at` | Timestamps |
| `version` | Optimistic lock |

### `order_items`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `order_id` | FK |
| `merchant_id` | FK |
| `product_id`, `variant_id` | Nullable snapshots reference |
| `product_name_snapshot` | Text |
| `quantity` | Integer |
| `base_price_cfa` | Original base |
| `platform_margin_cfa` | Original margin |
| `effective_base_cfa` | After bargaining allocation |
| `effective_margin_cfa` | After bargaining allocation |
| `bargaining_lock_id` | Nullable |
| `returnable` | Snapshot |
| `line_total_cfa` | Quantity total |

### `merchant_sub_orders`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `sub_order_code` | `SC-...`, unique |
| `order_id` | FK |
| `merchant_id` | FK |
| `status` | Merchant workflow status |
| `item_subtotal_cfa` | Merchant item subtotal |
| `commission_rate_bps` | Snapshot |
| `commission_cfa` | Snapshot |
| `merchant_net_cfa` | Before holds/refunds |

### `order_events`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `order_id` | FK |
| `from_status`, `to_status` | Nullable/status |
| `actor_user_id` | Nullable |
| `event_type` | Text |
| `reason_code` | Nullable |
| `metadata` | JSONB |
| `created_at` | Timestamp |

Index: `(order_id, created_at)`.

## Bargaining

### `bargaining_sessions`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `customer_id` | FK |
| `merchant_id` | FK |
| `product_id`, `variant_id` | FK nullable variant |
| `status` | `bargaining_status` |
| `customer_attempts_used` | 0 to 3 |
| `merchant_counter_attempts_used` | 0 to 3 |
| `created_at`, `updated_at`, `expires_at` | Timestamps |

Unique active index: `(customer_id, merchant_id, product_id, variant_id)` for non-terminal sessions.

### `bargaining_offers`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `session_id` | FK |
| `offered_by_user_id` | FK |
| `offer_type` | Customer offer/merchant counter |
| `amount_cfa` | Offered amount |
| `status` | Pending/accepted/rejected/expired |
| `created_at`, `responded_at` | Timestamps |

### `accepted_price_locks`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `session_id` | FK |
| `customer_id`, `merchant_id`, `product_id`, `variant_id` | Scope |
| `accepted_amount_cfa` | Locked price |
| `accepted_at` | Timestamp |
| `expires_at` | Accepted at + 24h |
| `consumed_order_item_id` | Nullable |

Index: `(customer_id, merchant_id, product_id, expires_at)`.

### `historical_minimum_prices`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `merchant_id`, `product_id`, `variant_id` | Scope |
| `customer_id` | Optional for per-customer history |
| `accepted_amount_cfa` | Accepted minimum |
| `accepted_at` | Timestamp |

## Delivery And Relay

### `delivery_missions`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `delivery_code` | `LIV-...`, unique |
| `order_id` | FK |
| `courier_id` | Nullable FK |
| `delivery_mode` | `delivery_mode` |
| `status` | Offered/accepted/picked_up/delivered/problem |
| `customer_delivery_fee_cfa` | Customer paid |
| `courier_fee_cfa` | Courier payable |
| `shortfall_cfa` | Sequo expense |
| `pickup_at`, `delivered_at` | Nullable |
| `proof_metadata` | JSONB |

### `delivery_pins`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `delivery_mission_id` | FK |
| `pin_hash` | Hashed PIN |
| `expires_at` | Timestamp |
| `used_at` | Nullable |
| `attempt_count` | Integer |

### `relay_parcels`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `relay_point_id` | FK |
| `locker_id` | Nullable FK |
| `order_id` | Nullable FK |
| `return_id` | Nullable FK |
| `deposit_code` | `DEP-...`, nullable unique |
| `status` | Deposited/picked_up/collected/delayed/problem |
| `deposited_at`, `picked_up_at`, `collected_at` | Nullable |

### `relay_custody_events`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `relay_parcel_id` | FK |
| `actor_user_id` | FK |
| `event_type` | Deposit/pickup/collection/return_dropoff/problem |
| `metadata` | JSONB |
| `created_at` | Timestamp |

## Returns And Refunds

### `return_requests`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `return_code` | `RET-...`, unique |
| `order_id` | FK |
| `customer_id` | FK |
| `status` | `return_status` |
| `reason_code` | Text |
| `requested_at` | Timestamp |
| `dropoff_deadline_at` | Timestamp |
| `physical_received_at` | Nullable |
| `refund_approved_at` | Nullable |
| `responsible_party` | Merchant/Sequo/courier/customer/disputed |

### `return_items`

| Column | Notes |
| --- | --- |
| `return_id` | FK |
| `order_item_id` | FK |
| `quantity` | Integer |
| `requested_refund_cfa` | Amount |

### `return_pins`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `return_id` | FK |
| `pin_hash` | Hashed PIN |
| `expires_at` | Timestamp |
| `used_at` | Nullable |

## Wallets, Payments, And Settlements

### `payment_intents`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `payment_code` | `PAY-...`, unique |
| `customer_id` | FK |
| `provider` | `wallet_provider` |
| `provider_reference` | Nullable |
| `status` | `payment_status` |
| `amount_cfa` | Amount |
| `idempotency_key` | Unique per actor/operation |
| `created_at`, `updated_at` | Timestamps |

### `wallet_transactions`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `provider` | `wallet_provider` |
| `provider_reference` | Unique nullable |
| `direction` | Debit/credit/refund/payout |
| `amount_cfa` | Amount |
| `status` | Pending/succeeded/failed |
| `source_type`, `source_id` | Order/refund/payout/etc. |
| `created_at`, `settled_at` | Timestamps |

### `settlement_ledger_entries`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `ledger_account` | Text |
| `debit_cfa` | Integer default 0 |
| `credit_cfa` | Integer default 0 |
| `currency` | CFA |
| `source_type`, `source_id` | Source domain |
| `actor_user_id` | Nullable |
| `idempotency_key` | Nullable |
| `reason_code` | Text |
| `metadata` | JSONB |
| `created_at` | Timestamp |

Constraint: exactly one of debit/credit should be positive.

### `payout_batches`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `payout_code` | `SET-...`, unique |
| `provider` | Yas/Moov |
| `status` | `payout_status` |
| `total_cfa` | Amount |
| `created_by`, `approved_by` | FK users nullable |
| `created_at`, `approved_at`, `sent_at`, `paid_at` | Timestamps |

### `payout_items`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `batch_id` | FK |
| `payee_type` | Merchant/courier/relay |
| `payee_id` | UUID |
| `amount_cfa` | Amount |
| `status` | `payout_status` |
| `provider_reference` | Nullable |
| `source_metadata` | JSONB |

## Platform Tables

### `idempotency_records`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `idempotency_key` | Client/provider key |
| `actor_key` | User/provider scope |
| `request_hash` | Hash of request payload |
| `response_status` | HTTP/status code |
| `response_body` | JSONB |
| `expires_at` | Timestamp |
| `created_at` | Timestamp |

Unique: `(actor_key, idempotency_key)`.

### `audit_logs`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `actor_user_id` | Nullable |
| `actor_role` | Nullable |
| `action` | Text |
| `target_type`, `target_id` | Target |
| `ip_hint` | Nullable |
| `user_agent_hash` | Nullable |
| `metadata` | JSONB |
| `created_at` | Timestamp |

### `outbox_events`

| Column | Notes |
| --- | --- |
| `id` | UUID primary key |
| `aggregate_type`, `aggregate_id` | Source |
| `event_type` | Text |
| `payload` | JSONB |
| `status` | Pending/published/failed |
| `attempt_count` | Integer |
| `next_attempt_at` | Timestamp |
| `created_at`, `published_at` | Timestamps |

## Critical Indexes

- `orders(order_code) unique`.
- `orders(customer_id, created_at desc)`.
- `orders(status, updated_at)`.
- `merchant_sub_orders(merchant_id, status, created_at desc)`.
- `products(merchant_id, status)`.
- `products(bargaining_enabled, status)`.
- `bargaining_sessions(customer_id, merchant_id, product_id, variant_id, status)`.
- `accepted_price_locks(customer_id, merchant_id, product_id, expires_at)`.
- `delivery_missions(courier_id, status)`.
- `relay_parcels(relay_point_id, status, deposited_at)`.
- `return_requests(order_id)`.
- `return_requests(status, requested_at)`.
- `payment_intents(provider, provider_reference)`.
- `wallet_transactions(provider, provider_reference) unique where provider_reference is not null`.
- `settlement_ledger_entries(source_type, source_id)`.
- `audit_logs(target_type, target_id, created_at desc)`.
- `outbox_events(status, next_attempt_at)`.

## Migration Policy

- Use Flyway or Liquibase.
- Never edit applied migrations.
- Add backfills as separate migrations.
- Add constraints after data is backfilled.
- Financial tables require migration review before production deploy.
- Enum changes require backward-compatible rollout planning.

