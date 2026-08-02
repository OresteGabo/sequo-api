# Domain Services

This document defines the backend domain services that should exist in Sequo API. Each service owns business rules that must be testable without HTTP controllers.

## Service Map

| Service | Primary responsibility |
| --- | --- |
| Auth Service | Login, JWT issuance, refresh rotation, logout, account lock |
| RBAC Service | Role and ownership checks for every protected action |
| Merchant Service | Merchant onboarding, KYC, staff, commission settings, payout preferences |
| Catalog Service | Products, variants, stock, delivery eligibility, platform margin, bargaining toggle |
| Cooperative Market Service | Market cooperative creation, member mapping, storefront aggregation, package consolidation |
| Cart Service | Draft baskets, delivery choices, checkout snapshot creation |
| Order Workflow Service | Order state machine, merchant actions, support interventions, immutable history |
| Pricing Service | Delivery fee, service fee, subscription discount, loyalty multiplier, final customer total |
| Commission Service | Merchant commission, margin attribution, cooperative split, payout net amount |
| Bargaining Service | Price proposals, attempts, accepted locks, historical minimums |
| Delivery Service | Courier missions, assignment, pickup, drop-off, proof, delivery cost |
| Relay Service | Point de Relai intake, lockers, pickup, return drop-off, delay tracking |
| Return Service | 72-hour eligibility, reason capture, relay drop-off, physical receipt, refund decision |
| Wallet Service | Yas Togo and Moov Africa payment intents, callbacks, refunds, wallet ledger |
| Settlement Service | Merchant/courier/relay payouts, holds, shortfalls, adjustments, reconciliation |
| Notification Service | Push, SMS, email, WhatsApp provider abstraction |
| Audit Service | Security, admin, financial, support, and domain audit events |

## Auth And Roles

Roles:

| Role | Description |
| --- | --- |
| `CUSTOMER` | Buyer placing orders and returns |
| `MERCHANT_OWNER` | Merchant account owner |
| `MERCHANT_STAFF` | Staff member allowed to manage orders/catalog by permission |
| `COURIER` | Rider or delivery actor |
| `RELAY_PARTNER` | Point de Relai operator |
| `SUPPORT_AGENT` | Internal support user with limited operational access |
| `ADMIN` | Internal operator with broad operational and financial permissions |
| `SUPER_ADMIN` | Highest-trust user for platform security and configuration |

Authorization must combine role checks and ownership checks. A merchant role alone does not grant access to another merchant's catalog, orders, payout records, or bargaining offers.

## Merchant Service

Responsibilities:

- Create merchant profiles after manual or admin-assisted onboarding.
- Track merchant type: logistics, standard/restaurant, service, cooperative member.
- Store default commission override between 5% and 15%.
- Store payout wallet provider preference, but do not implement cash withdrawal.
- Manage staff permissions per merchant.
- Expose merchant status: pending, active, paused, suspended, rejected.
- Track KYC review and supporting documents.

Invariants:

- A suspended merchant cannot accept new orders.
- A merchant commission rate outside 5% to 15% is invalid.
- Commission changes require admin authorization and audit.
- Merchant payout preferences must reference supported zero-cash wallet providers.

## Catalog Service

Responsibilities:

- Manage products, variants, prices, stock, images, service categories, and delivery modes.
- Store `base_price`, `platform_margin`, service fee policy, and bargaining eligibility.
- Preserve price snapshots on checkout and order items.
- Enforce product stock rules and delivery eligibility.
- Enforce media evidence policy: seller-specific goods require real-time camera evidence, while approved generic sealed items can use catalog/reference images.
- Support food customization groups for toppings, options, required choices, optional choices, and price deltas.

Delivery modes:

| Mode | Meaning |
| --- | --- |
| `STANDARD` | Immediate local delivery, often restaurants or urgent goods |
| `EXPRESS` | Courier delivery from one merchant to customer |
| `PROGRAMMED` | Scheduled/logistics route with consolidation |
| `CLICK_COLLECT` | Customer pickup at merchant or relay |
| `RELAY` | Delivery to Point de Relai for pickup |

Invariants:

- Reduced price must be lower than base price when promotions are implemented.
- A product with bargaining disabled cannot receive offers.
- Listing price calculations must come from the pricing engine, not ad hoc controller code.
- Gallery uploads or web images must not replace required live product evidence for seller-specific goods.
- Food customization selections must be copied into checkout and order item snapshots.

## Cooperative Market Service

The cooperative model groups independent sellers into a shared storefront while preserving individual seller ownership and settlement.

Responsibilities:

- Create admin-approved cooperatives such as Marche de Mulhouse.
- Manage cooperative membership and member status.
- Aggregate member products into a cooperative storefront.
- Produce consolidation manifests for single-package handoff to Sequo before delivery.
- Preserve per-item merchant ownership for commissions, refunds, and payouts.

Rules:

- A cooperative is not a financial merchant by default. It is a grouping and fulfillment abstraction.
- Each product and order item must retain `merchant_id`.
- Customer can see one cooperative package, while backend tracks multiple sub-orders.
- Revenue is distributed to individual merchants based on item ownership.
- Cooperative-level reporting can show aggregate GMV without exposing one member's private financial details to other members.

## Cart And Order Services

Checkout stages:

1. Customer builds cart.
2. Pricing engine returns a quote.
3. Customer selects delivery mode, address, and wallet provider.
4. API creates a payment intent.
5. Wallet provider confirms payment.
6. API creates immutable order, sub-orders, ledger reservations, and notifications.

Order invariants:

- Order creation is idempotent by checkout key.
- Paid order snapshots never depend on current catalog prices.
- Merchant acceptance is required before preparation.
- Invalid state transitions fail with typed business errors.
- Support/admin overrides require reason and audit.

## Pricing Service

Responsibilities:

- Calculate standard delivery fee: 400 CFA up to 5 km, plus 100 CFA for each extra rounded-up km.
- Apply monthly subscription percentage discounts to eligible per-km fees.
- Apply multi-year loyalty multipliers to eligible per-km fees.
- Calculate listing price from base price, platform margin, service fees, and delivery fee.
- Record the pricing version and inputs used for every checkout quote.

The full formula is documented in [PRICING_ENGINE.md](PRICING_ENGINE.md).

## Commission Service

Responsibilities:

- Resolve merchant commission rate: merchant override or default 15%.
- Validate merchant override is between 5% and 15%.
- Calculate Sequo commission on merchant base amount.
- Attribute platform margin separately from commission.
- Split cooperative orders by item owner.
- Feed settlement ledger entries.

The full model is documented in [COMMISSION_MODEL.md](COMMISSION_MODEL.md).

## Bargaining Service

Responsibilities:

- Enforce merchant bargaining toggle per product.
- Track 3 bargaining attempts per customer/seller pair.
- Support customer offer, merchant accept, merchant reject, and merchant counter-offer.
- Lock accepted minimum price for 24 hours.
- Track historical accepted minimum prices for analytics and future recommendation.

Attempt scope:

| Dimension | Rule |
| --- | --- |
| Customer | The buyer making offers |
| Seller | The merchant owning the product |
| Product | Attempts should include product or variant context when applicable |
| Window | Attempts persist for the session/policy window configured by the backend |

Accepted price lock:

- Created only when merchant accepts an offer or customer accepts a counter-offer.
- Expires after 24 hours.
- Can be consumed only by the matching customer, merchant, and product/variant.
- Does not change catalog base price.
- Must be copied into checkout and order item snapshots if used.

## Delivery Service

Responsibilities:

- Create courier missions for standard and express orders.
- Support Sequo-controlled scheduled tours for cooperative and programmed logistics.
- Prefer freelance moto couriers for express local deliveries unless subscriber or Sequo-capacity policy takes priority.
- Prefer salaried Sequo delivery capacity for subscriber orders and programmed/consolidated deliveries, with freelancer fallback when needed.
- Enforce merchant fulfillment transitions: seller acceptance, preparation, packed-ready state, and courier collection.
- Enforce delivery mission transitions: offered, accepted, picked up, direct delivered, relay deposited, relay released, problem, and cancelled.
- Store estimated distance, actual courier cost, customer delivery fee, and delivery shortfall.
- Enforce pickup and drop-off proof rules.
- Validate single-use delivery PINs when used.

Delivery shortfall rule:

- If `customer_delivery_fee < courier_fee`, Sequo covers the difference.
- The shortfall is posted to settlement ledger as Sequo expense.
- Merchant payout is not reduced unless explicitly configured by an admin policy.
- Salaried Sequo staff do not create per-mission courier payable entries; their compensation is handled outside delivery mission settlement.

## Relay Service

Responsibilities:

- Manage Point de Relai locations, operators, lockers/cases, and parcel state.
- Accept merchant deposits, courier deposits, customer pickup, Sequo collection, and return drop-offs.
- Assign one parcel to one available locker/case.
- Track delay and custody chain.
- Reject relay pickup routing for food and perishable products unless a future admin exception policy explicitly allows it.
- Generate and validate hashed pickup codes represented to the customer as numeric codes or QR payloads.
- Apply late parcel policy after configured thresholds, currently proposed as storage fees after 2 weeks and return-to-seller review after another 2 weeks.

Return drop-off:

- Customer presents return reference and return PIN.
- Relay validates active return.
- Physical receipt event starts the refund evaluation workflow.

## Return Service

Current authoritative return window: 72 hours after delivery or customer receipt.

Responsibilities:

- Determine return eligibility.
- Capture reason and evidence.
- Generate return reference and return PIN.
- Require Point de Relai drop-off.
- Trigger refund only after Sequo receives the physical item.
- Assign responsibility to Sequo, merchant, courier, or unresolved dispute.

Full details are documented in [SETTLEMENTS_AND_RETURNS.md](SETTLEMENTS_AND_RETURNS.md).

## Wallet Service

Supported providers:

- Yas Togo.
- Moov Africa.

Responsibilities:

- Create payment intents.
- Verify provider callbacks.
- Record external references.
- Post wallet ledger entries.
- Trigger refunds through supported provider rails.
- Reject cash withdrawal operations.

Invariants:

- Provider callbacks are idempotent.
- Provider signature verification is mandatory.
- Payment confirmation must never be accepted from the client alone.
- Wallet operations that move money require settlement ledger entries.

## Settlement Service

Responsibilities:

- Create immutable ledger entries for commissions, platform margin, merchant payable, courier payable, relay payable, refunds, holds, and shortfalls.
- Schedule merchant payouts within 1 week of package receipt.
- Hold disputed or return-eligible amounts where policy requires.
- Net merchant refunds against future payouts when merchant is responsible.
- Reconcile payout batches against wallet provider transaction references.

## Audit Service

Every sensitive action must emit an audit event:

- Login, refresh-token reuse, logout, account lock.
- Role changes and permission changes.
- Merchant commission updates.
- Product price or margin updates.
- Wallet callback acceptance or rejection.
- Refund approval or rejection.
- Payout creation, approval, failure, retry.
- Admin override of order, delivery, return, or settlement state.
