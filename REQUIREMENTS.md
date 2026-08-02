# Requirements

This file tracks API-related requirements extracted from the latest owner interview notes and reconciles them with the current Spring Boot backend implementation.

Detailed delivery and fulfillment coverage is tracked in [DELIVERY.md](DELIVERY.md).

## Status Legend

- `[x] Implemented`: domain code exists and has focused tests.
- `[ ] Partial`: documented or partly modeled, but missing persistence, controller, integration, scheduler, or full workflow.
- `[ ] Not implemented`: no meaningful backend implementation yet.
- `[ ] Decision needed`: owner/product decision is still needed before implementation.

## Owner-Note API Requirements

| Done | State | Area | Requirement | Evidence or gap |
| --- | --- | --- | --- | --- |
| [x] | Implemented | Pricing | Standard delivery price is 400 CFA for trips up to 5 km, then 100 CFA per extra rounded-up km. | `DeliveryPricingService` and tests cover the rule. |
| [ ] | Partial | Pricing | Monthly subscribers receive percentage discounts on eligible delivery per-km fees. | Prime monthly discount exists in order pricing; subscription billing, tier persistence, and customer subscription APIs remain. |
| [ ] | Partial | Pricing | Multi-year subscribers receive stronger loyalty discounts than monthly subscribers. | Multi-year discount policy exists; loyalty tenure rules and billing renewal are target-schema work. |
| [x] | Implemented | Delivery finance | If the customer delivery fee is lower than freelancer courier cost, Sequo pays the difference. | `DeliveryAssignmentPolicy` calculates `sequoShortfallCfa`; ledger persistence remains future settlement work. |
| [x] | Implemented | Delivery assignment | Subscriber orders prefer Sequo salaried deliverers before freelancers. | `DeliveryAssignmentPolicy` selects salaried Sequo capacity first for subscriber orders. |
| [x] | Implemented | Delivery assignment | Freelancers are the fallback when Sequo salaried capacity is unavailable. | Policy falls back to available freelancer capacity. |
| [x] | Implemented | Delivery finance | Salaried Sequo deliverers do not create per-mission freelancer payable entries. | Salaried assignments return `freelancerPayableCfa = 0`. Payroll is outside delivery mission fees. |
| [x] | Implemented | Delivery assignment | Express local delivery should prefer freelance moto couriers. | Policy prefers `Freelancer` + `Moto` for express non-subscriber orders. |
| [x] | Implemented | Delivery assignment | Freelance delivery pool supports motos and bicycles. | Delivery assignment model supports moto and bicycle vehicle types. |
| [ ] | Partial | Logistics | Amazon-like/non-urgent items can take days and should be handled through Sequo-controlled consolidation. | `GroupedSequo` order route and `ProgrammedConsolidation` assignment channel exist; scheduling, persistence, and admin tools remain. |
| [x] | Implemented | Checkout | Customer must pay before order validation/handoff to merchant fulfillment. | `OrderProcessing` validates payment before confirmation and merchant handoff events. |
| [ ] | Partial | Wallets | Integrate Yas Togo and Moov Africa digital wallets. | Provider abstractions exist; real adapters, signatures, callbacks, and reconciliation remain. |
| [x] | Implemented | Wallets | Do not implement cash withdrawal operations. | Payment model rejects cash withdrawal by design; docs and payment domain enforce zero-cash policy. |
| [x] | Implemented | Referrals | Referral/parrainage rewards are delivery credits only, not withdrawable money. | Pricing input applies delivery-only referral credit; durable referral credit ledger remains. |
| [ ] | Partial | Bargaining | Customer can propose a lower product price to a merchant. | API and schema are documented; bargaining domain service is not implemented yet. |
| [ ] | Not implemented | Bargaining | Enforce 3 bargaining attempts per customer/seller/product context. | Requires bargaining service, persistence, and tests. |
| [ ] | Not implemented | Bargaining | Accepted minimum price locks for 24 hours. | Requires accepted price lock service and checkout consumption. |
| [ ] | Not implemented | Bargaining | Track historical minimum accepted prices. | Target schema exists; no persistence/service implementation yet. |
| [ ] | Partial | Bargaining | Merchant can disable bargaining per product. | Catalog docs/schema include `bargaining_enabled`; product controller and enforcement remain. |
| [ ] | Partial | Apps and roles | Support separate customer, seller, courier, and future relay apps against one API. | Roles are documented; RBAC, scoped permissions, and app-specific claims remain incomplete. |
| [ ] | Partial | Admin operations | Admin can use a web monitoring surface instead of a desktop app. | Monitoring endpoints are now specified; implementation remains. |
| [x] | Implemented | Relay logistics | Point de Relai is not allowed for food or perishable products. | `OrderProcessing` rejects relay routing and `RelayParcelPolicy` rejects relay pickup for food/perishable lines. |
| [ ] | Partial | Relay logistics | Customers can choose relay pickup for eligible deliveries. | `PointDeRelai` route, relay release policy, and relay parcel migration exist; relay availability, assignment service, and customer pickup service remain. |
| [ ] | Partial | Relay logistics | Relay locations manage lockers/cases for parcels. | Schema documents `relay_lockers`; implementation remains. |
| [ ] | Partial | Relay logistics | Pickup uses numeric code or QR code plus identity validation. | Delivery workflow and `relay_pickup_codes` table exist; hashed code generation, QR payload, attempt limits, and relay endpoint remain. |
| [ ] | Partial | Relay logistics | Parcels staying more than 2 weeks can start storage fees. | `RelayParcelPolicy` identifies fee-eligible parcels; scheduler, fee ledger, and notification workflow remain. |
| [ ] | Decision needed | Relay logistics | After another 2 weeks, parcel can be returned to seller; 1-month timing is still discussable. | Owner decision needed before final threshold and fee policy are locked. |
| [ ] | Partial | Commissions | Default merchant commission is 15%. | Commission docs and schema constrain default; service/controller implementation remains. |
| [ ] | Partial | Commissions | Merchant commission can be configured from 5% to 15%. | Schema constraint and admin API are specified; implementation remains. |
| [ ] | Partial | Pricing | Listing price is `(base price + platform margin) + service fees + delivery fee`. | Pricing docs/schema model this; full checkout quote persistence remains. |
| [ ] | Partial | Cooperatives | Group merchants into market cooperatives such as Marche de Mulhouse. | Schema/API/docs exist; cooperative service implementation remains. |
| [ ] | Not implemented | Cooperatives | Merchants or cooperative actors request cooperative creation, then Sequo validates. | Requires request/approval workflow and scoped permissions. |
| [ ] | Partial | Consolidation | Multi-merchant purchases pass through Sequo and arrive as one package to the customer. | Order processor marks multi-seller orders as requiring consolidation; fulfillment workflow remains. |
| [ ] | Partial | Merchant fulfillment | Merchant prepares package in a reasonable delay and marks it ready for Sequo pickup. | Repository-backed `MerchantFulfillmentService` exists; controller, RBAC, order wiring, and SLA tracking remain. |
| [x] | Implemented | Catalog media | Seller-specific product photos should be real-time camera captures, not gallery or web images. | `ProductPhotoEvidence` rejects gallery uploads for seller-specific goods. |
| [x] | Implemented | Catalog media | Generic sealed products can use reference/catalog images. | `GenericCatalogImage` is accepted only for `GenericSealedItem`. |
| [ ] | Not implemented | Food catalog | Food items need toppings/customizations/options. | Requires customization group/option model, price deltas, and order snapshots. |
| [ ] | Partial | Returns | Customers drop returns at Point de Relai; Sequo later collects them. | Return and relay docs/schema exist; return intake service remains. |
| [ ] | Partial | Returns | Return window is 72 hours after delivery/customer receipt. | Docs/schema specify the rule; eligibility service remains. |
| [ ] | Partial | Refunds | Refund is triggered only after Sequo physically receives the returned item. | Docs/API specify physical receipt; refund orchestration remains. |
| [ ] | Partial | Settlements | Merchant payout should happen within 1 week after Sequo receives package/custody. | Settlement docs/schema specify schedule; payout scheduler remains. |
| [ ] | Partial | Logistics platform | Sequo API owns logistics orchestration across customer, seller, courier, relay, and admin workflows. | Delivery workflow policy and Flyway delivery tables exist; controllers, repositories, dispatch, tracking, and settlement modules remain. |
| [ ] | Decision needed | Routing cost | Google Maps or another routing provider cost must be controlled for courier distance estimation. | Need provider choice, quota policy, caching strategy, and fallback/manual distance policy. |

## Immediate Implementation Backlog

### Priority 0 - Security and startup safety

- [ ] Production startup guardrails for missing JWT secrets, wallet secrets, CORS origins, and database configuration.
- [ ] Auth rate limiting for login, refresh, password reset, social login, and OTP endpoints.
- [ ] RBAC plus ownership checks for merchant, relay, courier, admin, and customer resources.
- [ ] Social identity linking table to safely handle Google login and password login for the same verified email.
- [ ] Flyway migrations for roles, refresh sessions, social identities, and audit logs.

### Priority 1 - Owner-note core domain workflows

- [ ] Bargaining service with 3-attempt limit, accepted lock TTL, historical minimum price records, and merchant toggle enforcement.
- [ ] Relay parcel service with locker assignment, hashed pickup codes, QR payloads, ID validation events, delayed parcel fees, and return-to-seller workflow.
- [ ] Cooperative request/approval workflow where sellers can request a cooperative and Sequo validates it.
- [ ] Return eligibility service enforcing 72-hour window, relay drop-off, Sequo physical receipt, and refund trigger idempotency.
- [ ] Settlement scheduler for merchant payout eligibility within 1 week and shortfall ledger posting.
- [ ] Real Yas Togo and Moov Africa adapters with signed callbacks, provider references, reconciliation, and webhook idempotency.

### Priority 2 - Product and operations depth

- [ ] Subscription billing tiers, renewal state, per-km discount caps, and multi-year loyalty multipliers.
- [ ] Referral delivery credit wallet with expiry, non-cash constraints, and application only to delivery fees.
- [ ] Food customization/topping groups with required/optional choices, price deltas, and order snapshots.
- [ ] Product media upload policy with live-camera metadata, generic catalog references, moderation, and storage integration.
- [ ] Admin monitoring APIs for operations, delivery capacity, delayed relay parcels, payout queues, and return bottlenecks.
- [ ] Routing provider abstraction with distance caching, quota protection, manual fallback, and audit of estimated versus actual distance.
