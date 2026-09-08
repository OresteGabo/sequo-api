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
| [x] | Implemented | Pricing | Monthly subscribers receive percentage discounts on eligible delivery per-km fees. | `SubscriptionBenefitsService` applies configurable tier discounts to eligible delivery modes, enforces monthly caps, and ignores inactive, expired, wrong-customer, or ineligible subscriptions. |
| [x] | Implemented | Pricing | Multi-year subscribers receive stronger loyalty discounts than monthly subscribers. | `SubscriptionBenefitsService` applies configured loyalty multipliers after subscription discounts based on paid tenure, with per-order caps and renewal-state tests. |
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
| [x] | Implemented | Referrals | Referral/parrainage rewards are delivery credits only, not withdrawable money. | `ReferralDeliveryCreditService` issues expiring delivery-only credits, applies them only to delivery fees, preserves source/remaining snapshots, and rejects cash, transfer, refund, item, margin, service-fee, tip, merchant-payout, and courier-payout uses. |
| [x] | Implemented | Bargaining | Customer can propose a lower product price to a merchant. | `BargainingService` accepts lower customer offers, rejects non-discount offers, and records pending offer snapshots with focused tests. |
| [x] | Implemented | Bargaining | Enforce 3 bargaining attempts per customer/seller/product context. | `BargainingService` counts customer attempts per session scope and locks the session after the third rejected attempt. |
| [x] | Implemented | Bargaining | Accepted minimum price locks for 24 hours. | `BargainingService` creates accepted price locks for merchant-accepted offers and customer-accepted counters, with 24-hour expiry tests. Checkout consumption remains future integration work. |
| [x] | Implemented | Bargaining | Track historical minimum accepted prices. | `BargainingService` stores historical minimum accepted prices on the session model and keeps them after lock expiry; persistence remains future repository work. |
| [x] | Implemented | Bargaining | Merchant can disable bargaining per product. | `BargainingService` enforces `bargainingEnabled=false`; product controller wiring remains future API work. |
| [ ] | Partial | Apps and roles | Support separate customer, seller, courier, and future relay apps against one API. | Roles are documented; RBAC, scoped permissions, and app-specific claims remain incomplete. |
| [ ] | Partial | Admin operations | Admin can use a web monitoring surface instead of a desktop app. | Monitoring endpoints are now specified; implementation remains. |
| [x] | Implemented | Relay logistics | Point de Relai is not allowed for food or perishable products. | `OrderProcessing` rejects relay routing and `RelayParcelPolicy` rejects relay pickup for food/perishable lines. |
| [ ] | Partial | Relay logistics | Customers can choose relay pickup for eligible deliveries. | `PointDeRelai` route, relay release policy, and relay parcel migration exist; relay availability, assignment service, and customer pickup service remain. |
| [ ] | Partial | Relay logistics | Relay locations manage lockers/cases for parcels. | Schema documents `relay_lockers`; implementation remains. |
| [ ] | Partial | Relay logistics | Pickup uses numeric code or QR code plus identity validation. | Delivery workflow and `relay_pickup_codes` table exist; hashed code generation, QR payload, attempt limits, and relay endpoint remain. |
| [ ] | Partial | Relay logistics | Parcels staying more than 2 weeks can start storage fees. | `RelayParcelPolicy` identifies fee-eligible parcels; scheduler, fee ledger, and notification workflow remain. |
| [ ] | Decision needed | Relay logistics | After another 2 weeks, parcel can be returned to seller; 1-month timing is still discussable. | Owner decision needed before final threshold and fee policy are locked. |
| [x] | Implemented | Commissions | Default merchant commission is 15%. | `MerchantCommissionService` resolves the 15% default and snapshots commission, merchant net, and Sequo item revenue with focused tests. |
| [ ] | Partial | Commissions | Merchant commission can be configured from 5% to 15%. | `MerchantCommissionService` validates 5%-15% overrides; admin API and persistence remain. |
| [ ] | Partial | Pricing | Listing price is `(base price + platform margin) + service fees + delivery fee`. | Pricing docs/schema model this; full checkout quote persistence remains. |
| [ ] | Partial | Cooperatives | Group merchants into market cooperatives such as Marche de Mulhouse. | Schema/API/docs exist; cooperative service implementation remains. |
| [ ] | Not implemented | Cooperatives | Merchants or cooperative actors request cooperative creation, then Sequo validates. | Requires request/approval workflow and scoped permissions. |
| [ ] | Partial | Consolidation | Multi-merchant purchases pass through Sequo and arrive as one package to the customer. | Order processor marks multi-seller orders as requiring consolidation; fulfillment workflow remains. |
| [ ] | Partial | Merchant fulfillment | Merchant prepares package in a reasonable delay and marks it ready for Sequo pickup. | Repository-backed `MerchantFulfillmentService` exists; controller, RBAC, order wiring, and SLA tracking remain. |
| [x] | Implemented | Catalog media | Seller-specific product photos should be real-time camera captures, not gallery or web images. | `ProductPhotoEvidence` rejects gallery uploads for seller-specific goods; `ProductMediaPolicyService` validates live-camera metadata, safe filenames, image signatures, hashes, moderation, and storage handoff. |
| [x] | Implemented | Catalog media | Generic sealed products can use reference/catalog images. | `GenericCatalogImage` is accepted only for `GenericSealedItem`. |
| [x] | Implemented | Food catalog | Food items need toppings/customizations/options. | `FoodCustomizationService` validates required/optional choices, price deltas, unavailable options, and order snapshots with focused tests. |
| [ ] | Partial | Returns | Customers drop returns at Point de Relai; Sequo later collects them. | Return and relay docs/schema exist; return intake service remains. |
| [x] | Implemented | Returns | Return window is 72 hours after delivery/customer receipt. | `ReturnProcessingService` accepts requests at exactly 72 hours, rejects late requests, and has focused tests. |
| [ ] | Partial | Refunds | Refund is triggered only after Sequo physically receives the returned item. | `ReturnProcessingService` blocks refund before physical receipt and handles idempotent refund trigger; wallet provider execution remains. |
| [ ] | Partial | Settlements | Merchant payout should happen within 1 week after Sequo receives package/custody. | Settlement docs/schema specify schedule; payout scheduler remains. |
| [ ] | Partial | Logistics platform | Sequo API owns logistics orchestration across customer, seller, courier, relay, and admin workflows. | Delivery workflow policy and Flyway delivery tables exist; controllers, repositories, dispatch, tracking, and settlement modules remain. |
| [ ] | Decision needed | Routing cost | Google Maps or another routing provider cost must be controlled for courier distance estimation. | Routing abstraction now covers quota, cache, manual fallback, and audit; provider choice remains a product/ops decision. |

## Notification And Realtime Requirements

| Done | State | Area | Requirement | Evidence or gap |
| --- | --- | --- | --- | --- |
| [x] | Implemented | FCM | Store and manage FCM device tokens per user, device, platform, and app family. | `V3__create_notification_tables.sql`, `DeviceTokenService`, `DeviceTokenController`, and tests register, rotate, revoke, encrypt, and list active tokens. Firebase sending remains separate. |
| [ ] | Partial | Push routing | Route notifications separately for customer, merchant, relay, rider, support, admin, and super admin roles. | App-family channel planning exists; role/ownership recipient routing service remains. |
| [ ] | Partial | In-app inbox | Persist notification history with read/archive state. | `notification_messages` and `NotificationDispatchService` persist message history; read/list/archive APIs remain. |
| [ ] | Partial | SMS fallback | Use SMS backup for critical PIN, blocked delivery, relay pickup, and urgent payment/refund events. | `NotificationChannelPolicy` gates SMS behind critical events, explicit fallback, user preference, push failure/unavailability, and remaining budget. Paid provider adapter is intentionally not wired yet. |
| [ ] | Not implemented | WebSocket/STOMP | Configure `/ws` with JWT-authenticated STOMP for active mobile clients. | Architecture documented in [WEBSOCKET_ARCHITECTURE.md](WEBSOCKET_ARCHITECTURE.md); dependencies/config not implemented. |
| [ ] | Not implemented | Realtime authorization | Authorize every STOMP subscription by role and ownership. | Topic policy documented; interceptors not implemented. |
| [ ] | Not implemented | Rider radar | Publish live mission offers and high-priority subscriber orders to eligible riders. | Topics and payloads documented; service not implemented. |
| [ ] | Not implemented | Bargaining realtime | Publish active offer/counter/accept/reject updates to bargaining participants. | Topics documented; bargaining service not implemented. |
| [ ] | Partial | Event-driven triggers | Fire notifications from committed domain events/outbox, not controller side effects. | Notification outbox table and idempotent dispatch service exist; event listener hooks and worker remain. |

## Immediate Implementation Backlog

### Priority 0 - Security and startup safety

- [x] Production startup guardrails for missing JWT secrets, notification token encryption secrets, OAuth placeholders, wallet secrets, explicit CORS origins, and unsafe database configuration.
- [x] First-pass in-memory auth rate limiting for signup, login, social login, refresh, forgot-password, and reset-password endpoints.
- [x] Placeholder replacement tracker and production readiness check for fake URLs, provider IDs, local defaults, and test-only secrets.
- [ ] Distributed/gateway rate limiting for multi-instance production and future OTP endpoints.
- [ ] RBAC plus ownership checks for merchant, relay, courier, admin, and customer resources.
- [ ] Social identity linking table to safely handle Google login and password login for the same verified email.
- [ ] Flyway migrations for roles, refresh sessions, social identities, and audit logs.

### Priority 1 - Owner-note core domain workflows

- [ ] Notification routing service for customer, merchant, rider, relay, support, admin, and super-admin scopes.
- [ ] Notification outbox worker plus `@TransactionalEventListener` hooks from order, merchant fulfillment, delivery, relay, return, refund, and settlement workflows.
- [ ] Firebase Admin SDK adapter with stale-token pruning, retries, and provider delivery audit.
- [ ] WebSocket/STOMP runtime with JWT handshake, authorized subscriptions, and mobile active-session tracking.
- [x] Bargaining service with 3-attempt limit, accepted lock TTL, historical minimum price records, and merchant toggle enforcement.
- [ ] Relay parcel service with locker assignment, hashed pickup codes, QR payloads, ID validation events, delayed parcel fees, and return-to-seller workflow.
- [ ] Cooperative request/approval workflow where sellers can request a cooperative and Sequo validates it.
- [x] Return eligibility service enforcing 72-hour window, relay drop-off, Sequo physical receipt, and refund trigger idempotency.
- [ ] Settlement scheduler for merchant payout eligibility within 1 week and shortfall ledger posting.
- [ ] Real Yas Togo and Moov Africa adapters with signed callbacks, provider references, reconciliation, and webhook idempotency.

### Priority 2 - Product and operations depth

- [x] Subscription billing tiers, renewal state, per-km discount caps, and multi-year loyalty multipliers.
- [x] Referral delivery credit wallet with expiry, non-cash constraints, and application only to delivery fees.
- [x] Food customization/topping groups with required/optional choices, price deltas, and order snapshots.
- [x] Product media upload policy with live-camera metadata, generic catalog references, safe filename/content validation, moderation, and storage integration.
- [ ] Admin monitoring APIs for operations, delivery capacity, delayed relay parcels, payout queues, and return bottlenecks.
- [x] Routing provider abstraction with distance caching, quota protection, manual fallback, and audit of estimated versus actual distance.
