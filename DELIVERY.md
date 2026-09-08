# Delivery And Fulfillment

This document audits Sequo delivery coverage end to end: customer checkout, seller acceptance, packing, courier pickup, direct delivery, relay delivery, Sequo consolidation, proof, problems, and settlement hooks.

## Current Verdict

Delivery is only partially treated.

Implemented today:

- Delivery pricing with 400 CFA minimum and 100 CFA per extra km.
- Paid-order validation before seller handoff.
- Fulfillment planning for fast delivery, pickup, Point de Relai, and grouped Sequo routes.
- Multi-seller orders marked as requiring Sequo consolidation.
- Courier assignment policy for subscriber priority, express freelance moto preference, freelancer fallback, and Sequo shortfall calculation.
- Merchant packing workflow policy.
- Repository-backed merchant sub-order service for seller acceptance, preparation, packing, rejection, and courier handoff.
- Merchant, courier, relay, customer-tracking, and first admin-dispatch controllers with role checks and scoped reads.
- Repository-backed delivery mission service for list/detail, courier assignment, re-assignment before pickup, pickup, direct delivery, relay deposit/release, cancellation, and problem states.
- Direct delivery PIN creation and validation with hashed, one-time, expiring credentials.
- Proof-redacted customer delivery tracking by delivery code plus order reference.
- Relay policy blocking food/perishable relay pickup and identifying delayed parcels.
- Flyway migration for merchant sub-orders, delivery missions, delivery PINs, relay parcels, pickup codes, and relay custody events.
- Notification outbox worker, retry-ready listing, and after-commit workflow-event listener for committed notification enqueue.

Not implemented yet:

- Persisted merchant account-to-merchant/staff membership mapping beyond the current principal-to-merchant guard.
- Real assignment queue, courier availability, dispatch locking, and courier pause/penalty operations.
- Proof photo/signature/geolocation storage.
- Workflow-specific notification publishers, Firebase/WebSocket delivery, ETA, and route provider integration.
- Repository-backed settlement ledger posting for courier payable, relay payable, and shortfalls.

## Status Legend

- `[x] Implemented`: domain code exists and focused tests cover the rule.
- `[ ] Partial`: documented, specified, or partly modeled, but missing persistence, controllers, integrations, or full workflow.
- `[ ] Not implemented`: no meaningful backend implementation yet.
- `[ ] Decision needed`: product/owner decision still required.

## Delivery Modes

| Done | State | Mode | Backend rule | Evidence or gap |
| --- | --- | --- | --- | --- |
| [ ] | Partial | Fast direct delivery | Seller prepares package, courier picks it up, courier delivers to customer address with proof/PIN. | `MerchantFulfillmentController`, `DeliveryMissionController`, and `DeliveryTrackingController` expose the operational path with hashed PIN creation/validation; order-readiness dispatch and ETA remain. |
| [ ] | Partial | Express delivery | Non-subscriber express orders prefer freelance moto couriers. | `DeliveryAssignmentPolicy` implements selection; dispatch queue missing. |
| [ ] | Partial | Subscriber delivery | Subscriber orders prefer salaried Sequo delivery capacity before freelancers. | `DeliveryAssignmentPolicy` implements selection; subscription persistence and dispatch integration missing. |
| [ ] | Partial | Sequo direct delivery | Sequo salaried delivery capacity can handle priority or programmed deliveries without per-mission freelancer payable. | Assignment policy exists; payroll/capacity management missing. |
| [ ] | Partial | Grouped Sequo consolidation | Multi-seller or programmed orders pass through Sequo and become one customer-facing package. | `SequoConsolidationService` validates seller packages, waits for readiness, records Sequo custody, and creates the final package ID; persistence, pickup missions, and final dispatch remain. |
| [ ] | Partial | Point de Relai delivery | Eligible non-perishable package is deposited at relay and released to customer by code/QR plus ID validation. | `RelayParcelService`, repository persistence, and `RelayParcelController` cover eligible parcel creation, pickup-code creation, listing/detail, validated release, delayed-parcel evaluation, and admin monitoring; scheduler/fee automation remain. |
| [ ] | Partial | Customer pickup/click collect | Customer pickup has zero delivery fee and requires seller readiness confirmation. | Pickup route pricing exists; pickup confirmation workflow missing. |
| [ ] | Partial | Return relay intake | Returns are dropped at relay, collected by Sequo, then refunded after physical receipt. | `ReturnProcessingService` validates relay PINs, records physical receipt and responsibility, and blocks refund before receipt; repository/controller integration remains. |

## Happy Path: Direct Customer Delivery

| Done | State | Step | Required backend behavior | Evidence or gap |
| --- | --- | --- | --- | --- |
| [x] | Implemented | Customer pays before fulfillment | API must not create fulfillment handoff until wallet payment is validated. | `OrderProcessing` stops before seller handoff if payment is pending/failed. |
| [ ] | Partial | Paid order creates merchant sub-order | Persist order, immutable snapshots, and one sub-order per merchant. | `merchant_sub_orders` migration and service exist; order creation is not wired to create sub-orders yet. |
| [x] | Implemented | Seller accepts order | Seller must explicitly accept before packing. | `MerchantFulfillmentService` persists acceptance and tests cover merchant scope checks. |
| [x] | Implemented | Seller starts preparing | Accepted order can move to preparing. | `MerchantFulfillmentService` persists preparation timestamp. |
| [x] | Implemented | Seller marks package packed | Package cannot be picked up until seller marks at least one package ready. | `MerchantFulfillmentService` requires `packageCount > 0` and persists packed state. |
| [ ] | Partial | Courier mission is created | API creates a delivery mission after package readiness or according to dispatch policy. | `DeliveryMissionService` and `DeliveryMissionController` persist and expose mission creation/assignment; order-readiness trigger and dispatch queue remain. |
| [x] | Implemented | Courier assignment policy | Selects eligible courier based on subscriber/order channel/workforce/vehicle rules. | `DeliveryAssignmentPolicy` and tests. |
| [x] | Implemented | Courier accepts mission | Mission transition policy requires offer before acceptance. | `DeliveryMissionController` exposes role-protected accept and tests verify assigned-courier scope. |
| [x] | Implemented | Courier picks up package | Pickup requires proof before package leaves seller. | `DeliveryMissionController` exposes pickup with proof and persists actor metadata. |
| [x] | Implemented | Courier delivers to customer | Direct customer-address mission requires delivery proof/PIN. | `DeliveryMissionController` validates direct delivery PINs before delivery transitions and keeps the raw PIN out of responses. |
| [ ] | Partial | Order becomes delivered | Delivery completion should set order delivered, open 72-hour return window, and notify customer/merchant. | Target docs/schema exist; no persisted workflow service. |
| [ ] | Partial | Settlement starts | Courier payable, shortfall, merchant payout timing, and return hold are posted. | `SettlementLedgerService` posts merchant accrual, return/dispute holds, adjustments, and Sequo delivery shortfall snapshots; repository-backed courier/relay ledger posting remains. |

## Happy Path: Relay Delivery

| Done | State | Step | Required backend behavior | Evidence or gap |
| --- | --- | --- | --- | --- |
| [x] | Implemented | Reject food/perishable relay route | Food and perishables cannot be routed to Point de Relai by default. | `OrderProcessing` and `RelayParcelPolicy` enforce the rule. |
| [x] | Implemented | Create relay parcel | Eligible relay order creates parcel record and assigns relay point/locker. | `RelayParcelService` creates deposited relay parcels, assigns active free lockers, records deposit custody events, and rejects food/perishable categories. |
| [x] | Implemented | Courier deposits at relay | Relay mission can move to deposited only with proof. | `DeliveryMissionWorkflow` covers transition. |
| [x] | Implemented | Generate pickup code/QR | API must generate hashed numeric code and optional QR nonce with expiry/attempt limits. | `RelayParcelService` generates hashed numeric codes and QR nonces, verifies either credential, enforces expiry, one-time use, and attempt limits. |
| [x] | Implemented | Release requires code and identity validation | Relay release requires pickup code and identity validation. | `DeliveryMissionWorkflow` enforces both flags at policy level. |
| [ ] | Partial | Track delayed parcels | Parcels after 2 weeks become storage-fee candidates. | `RelayParcelPolicy`, `RelayParcelService`, `RelayParcelApplicationService`, and admin monitoring evaluate, persist, count, and list delayed/review parcels; scheduler, fee ledger, tariff decision, and notifications remain. |
| [ ] | Decision needed | Return to seller after extended delay | Owner note mentions another 2 weeks/1 month but final policy is discussable. | Product decision required before automation. |

## Grouped Sequo And Cooperative Delivery

| Done | State | Step | Required backend behavior | Evidence or gap |
| --- | --- | --- | --- | --- |
| [ ] | Partial | Multi-seller checkout detected | Backend detects multiple sellers and marks consolidation required. | `OrderProcessing` does this. |
| [x] | Implemented | Create consolidation manifest | API must create manifest with seller packages, Sequo hub custody, and final package ID. | `SequoConsolidationService` models the manifest and validates unique seller entries, complete readiness, custody timestamp, and final package ID; repository/schema integration remains. |
| [ ] | Not implemented | Sellers mark each sub-order ready | All merchants must accept and mark ready before Sequo pickup/consolidation. | Merchant workflow policy exists for one package; aggregate manifest workflow missing. |
| [ ] | Not implemented | Sequo collects from sellers | Sequo/courier missions collect each seller package into consolidation custody. | Missing. |
| [ ] | Not implemented | Final customer package dispatched | Consolidated package gets one final delivery or relay route. | Missing. |
| [ ] | Partial | Settlement remains per merchant | Even one package must preserve item ownership and per-merchant commission/refund liability. | Commission/schema docs exist; implementation missing. |

## Problem And Exception Flows

| Done | State | Case | Required backend behavior | Evidence or gap |
| --- | --- | --- | --- | --- |
| [ ] | Partial | Seller rejects order | Customer must be refunded or rerouted according to policy. | Merchant fulfillment service can persist rejection reason; refund orchestration missing. |
| [ ] | Partial | Seller delays packing | SLA timers, warnings, cancellation, reassign/support escalation. | `MerchantFulfillmentService.sla` records 24-hour response and 48-hour packing deadlines, while admin monitoring exposes seller backlog/ready/rejected queues; delivery missions can be cancelled/reassigned/forced to problem, but automated SLA actions and notifications remain. |
| [ ] | Partial | Courier reports problem | Mission can be moved to problem with reason. | `DeliveryMissionController` and `RelayParcelController` persist role-protected problem reports with actor, metadata, and idempotency; notifications and support resolution remain. |
| [ ] | Not implemented | Customer unavailable | Reschedule, fallback relay, support intervention, or failed delivery state. | Missing. |
| [ ] | Not implemented | Relay locker unavailable | Alternative locker/relay/manual custody workflow. | Missing. |
| [ ] | Not implemented | Package lost/damaged | Responsibility assignment, evidence, support investigation, ledger liability. | Return/settlement docs only. |
| [ ] | Partial | Courier no-show | Mission expiry, reassign, courier penalty/support workflow. | Admin can reassign missions before pickup and force a support problem state; expiry scheduler and penalty workflow remain. |
| [ ] | Partial | Duplicate pickup/delivery submission | Idempotency key plus state guard prevents duplicate side effects. | Delivery mission service rejects duplicate proof submissions; endpoint idempotency keys remain. |

## Delivery Data That Must Be Persisted

| Done | State | Data | Purpose | Evidence or gap |
| --- | --- | --- | --- | --- |
| [x] | Implemented | `merchant_sub_orders` | Seller acceptance/preparation/ready state per merchant. | Flyway table, JPA entity, repository, and service exist. |
| [x] | Implemented | `delivery_missions` | Courier assignment, pickup, delivery, route, cost, proof. | `DeliveryMission`, `DeliveryMissionRepository`, and `DeliveryMissionService` persist assignments, statuses, proof metadata, relay release checks, and shortfall values. |
| [x] | Implemented | `delivery_pins` | Direct delivery PIN validation and attempt control. | `DeliveryPinService` stores only hashed PINs and enforces expiry, one-time use, and a five-attempt limit; `/deliver` validates the PIN before the workflow transition. |
| [x] | Implemented | `relay_parcels` | Parcel custody at relay, locker, delay, pickup/release. | `RelayParcelPersistenceService` and `RelayParcelController` persist and expose parcel state, category, locker, source references, listing/detail, release, problems, and delayed evaluation. |
| [x] | Implemented | `relay_pickup_codes` | Hashed numeric/QR pickup credentials. | `RelayParcelPersistenceService` and `RelayParcelController` persist hashed numeric/QR credentials, expiry, usage counters, and safe public responses that never return raw secrets. |
| [ ] | Partial | `relay_custody_events` | Deposit, pickup, Sequo collection, lost/damaged evidence. | `RelayParcelPersistenceService` persists deposit and release events with actor and idempotency data; broader event handling remains. |
| [ ] | Partial | `order_events` | Immutable audit trail for order and delivery state changes. | Target schema only. |
| [x] | Implemented | `settlement_ledger_entries` | Courier/relay payable, shortfalls, holds, adjustments. | `SettlementPersistenceService` persists immutable ledger entries, idempotently records delivery shortfalls and adjustments, and reads entries by source. Automatic payout execution remains. |

## API Surface Required Before Delivery Apps Work

| Done | State | Endpoint family | Needed endpoints |
| --- | --- | --- | --- |
| [x] | Implemented | Merchant order workflow | List/detail/SLA plus accept, reject, start preparation, mark packed/ready, and handoff verification with admin/merchant role checks. |
| [x] | Implemented | Courier missions | List/detail assigned missions, accept, pickup with proof, deliver with proof/PIN, deposit at relay, relay release, and report problem. |
| [ ] | Partial | Relay operations | List parcels, deposit, validate pickup code/QR, release to customer, report problem, delayed parcel list. | Parcel creation, pickup-code, validation/release, detail/listing, delayed evaluation, and incident endpoints are available; scheduler and support resolution remain. |
| [ ] | Partial | Customer tracking | Read order delivery status, ETA, relay instructions, pickup code state, proof-safe delivery confirmation. | `/api/delivery/tracking/{deliveryCode}?orderId=...` returns proof-redacted delivery status and timestamps; ETA, relay instructions, and pickup-code state remain. |
| [ ] | Partial | Admin dispatch | Reassign courier, pause courier, force problem state, view capacity, view delayed parcels, resolve failed deliveries. | Admin can assign/reassign before pickup, cancel missions, force problem state, and view monitoring; courier pause and failed-delivery resolution remain. |

## Security Requirements For Delivery

| Done | State | Requirement | Why it matters |
| --- | --- | --- | --- |
| [ ] | Partial | Merchant ownership checks | Service rejects wrong merchant scope and controller requires non-admin merchant requests to match the authenticated merchant principal; persisted staff membership/merchant-scope claims remain. |
| [x] | Implemented | Courier mission ownership checks | A courier must not pickup/deliver another courier's assigned mission. | `DeliveryMissionService` rejects accept, pickup, delivery, relay deposit, and problem updates from a non-assigned courier. |
| [x] | Implemented | Relay scope checks | `RelayParcelService` rejects release when the relay actor is operating on another relay point's parcel. |
| [x] | Implemented | One-time pickup/delivery credentials | Relay pickup credentials and direct delivery PINs are one-time, hashed, expiring, and attempt-limited, with direct PIN validation integrated into `/deliver`. |
| [ ] | Partial | Proof tamper controls | Proof photos, GPS hints, timestamps, and actor ID must be immutable after submission. | Proof actor IDs and timestamps are persisted separately for pickup, relay deposit, and customer drop-off; service transitions reject duplicate proof submissions, while database-level audit/immutability remains. |
| [ ] | Partial | Idempotency | Relay release is idempotent by key in `RelayParcelService`; pickup, delivery, and problem endpoints remain. |

## Recommended Implementation Order

1. [x] Add Flyway migrations for `merchant_sub_orders`, `delivery_missions`, `delivery_pins`, `relay_parcels`, `relay_pickup_codes`, `relay_custody_events`, and required status constraints.
2. [x] Implement repository-backed merchant fulfillment service using `MerchantFulfillmentWorkflow`.
3. [x] Implement repository-backed delivery mission service using `DeliveryMissionWorkflow` and `DeliveryAssignmentPolicy`.
4. [x] Implement relay parcel service using `RelayParcelPolicy`, hashed pickup codes, locker assignment, and custody events.
5. [ ] Add merchant, courier, relay, customer tracking, and admin dispatch endpoints with RBAC/ownership checks. Merchant, courier, relay, customer tracking, and first admin-dispatch endpoints are implemented; courier pause/resolution and persisted merchant memberships remain.
6. [ ] Add notification/outbox events and settlement ledger posting. Outbox worker and after-commit event listener are implemented; workflow-specific publishers and repository-backed settlement posting remain.
7. [ ] Add problem handling, re-assignment, failed delivery, delayed relay fees, and return-to-seller automation after product thresholds are finalized. Problem states and pre-pickup re-assignment exist; expiry/fees/return automation remain.
