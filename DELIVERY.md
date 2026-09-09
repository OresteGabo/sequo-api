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
- Real assignment queue, richer courier availability/capacity planning, dispatch locking, and courier penalty operations.
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
| [ ] | Partial | Fast direct delivery | Seller prepares package, courier picks it up, courier delivers to customer address with proof/PIN. | `MerchantFulfillmentController`, `DeliveryMissionController`, `DeliveryTrackingController`, and `DeliveryReadinessDispatchService` expose the operational path with hashed PIN creation/validation, courier pause guards, and ready-suborder dispatch; ETA remains. |
| [ ] | Partial | Express delivery | Non-subscriber express orders prefer freelance moto couriers. | `DeliveryAssignmentPolicy` implements selection; dispatch queue missing. |
| [ ] | Partial | Subscriber delivery | Subscriber orders prefer salaried Sequo delivery capacity before freelancers. | `DeliveryAssignmentPolicy` implements selection; subscription persistence and dispatch integration missing. |
| [ ] | Partial | Sequo direct delivery | Sequo salaried delivery capacity can handle priority or programmed deliveries without per-mission freelancer payable. | Assignment policy exists; payroll/capacity management missing. |
| [ ] | Partial | Grouped Sequo consolidation | Multi-seller or programmed orders pass through Sequo and become one customer-facing package. | `SequoConsolidationService` validates seller packages, waits for readiness, records Sequo custody, and creates the final package ID; persistence, pickup missions, and final dispatch remain. |
| [ ] | Partial | Point de Relai delivery | Eligible non-perishable package is deposited at relay and released to customer by code/QR plus ID validation. | `RelayParcelService`, repository persistence, and `RelayParcelController` cover eligible parcel creation, pickup-code creation, listing/detail, validated release, delayed-parcel evaluation, and admin monitoring; scheduler/fee automation remain. |
| [ ] | Partial | Customer pickup/click collect | Customer pickup has zero delivery fee and requires seller readiness confirmation. | Pickup route pricing exists; pickup confirmation workflow missing. |
| [x] | Implemented | Return relay intake | Returns are dropped at relay, collected by Sequo, then refunded after physical receipt. | `ReturnProcessingService`, `ReturnPersistenceService`, `ReturnController`, and `return_requests` persist customer return requests, validate relay PINs, record physical receipt/responsibility, block refund before receipt, and publish return/refund outbox events. |

## Happy Path: Direct Customer Delivery

| Done | State | Step | Required backend behavior | Evidence or gap |
| --- | --- | --- | --- | --- |
| [x] | Implemented | Customer pays before fulfillment | API must not create fulfillment handoff until wallet payment is validated. | `OrderProcessing` stops before seller handoff if payment is pending/failed. |
| [x] | Implemented | Paid order creates merchant sub-order | Persist order, immutable snapshots, and one sub-order per merchant. | `customer_orders`, `customer_order_lines`, `merchant_sub_orders`, and `OrderFulfillmentPersistenceService` persist paid orders, immutable line snapshots, and idempotent merchant sub-orders grouped by seller. |
| [x] | Implemented | Seller accepts order | Seller must explicitly accept before packing. | `MerchantFulfillmentService` persists acceptance and tests cover merchant scope checks. |
| [x] | Implemented | Seller starts preparing | Accepted order can move to preparing. | `MerchantFulfillmentService` persists preparation timestamp. |
| [x] | Implemented | Seller marks package packed | Package cannot be picked up until seller marks at least one package ready. | `MerchantFulfillmentService` requires `packageCount > 0` and persists packed state. |
| [x] | Implemented | Courier mission is created | API creates a delivery mission after package readiness or according to dispatch policy. | `DeliveryReadinessDispatchService` and `/api/delivery/missions/dispatch-ready` create idempotent unassigned missions from packed merchant sub-orders, choosing customer, relay, or Sequo-consolidation destination from the persisted order route. |
| [x] | Implemented | Courier assignment policy | Selects eligible courier based on subscriber/order channel/workforce/vehicle rules. | `DeliveryAssignmentPolicy` and tests. |
| [x] | Implemented | Courier accepts mission | Mission transition policy requires offer before acceptance. | `DeliveryMissionController` exposes role-protected accept and tests verify assigned-courier scope. |
| [x] | Implemented | Courier picks up package | Pickup requires proof before package leaves seller. | `DeliveryMissionController` exposes pickup with proof and persists actor metadata. |
| [x] | Implemented | Courier delivers to customer | Direct customer-address mission requires delivery proof/PIN. | `DeliveryMissionController` validates direct delivery PINs before delivery transitions and keeps the raw PIN out of responses. |
| [x] | Implemented | Order becomes delivered | Delivery completion should set order delivered, open 72-hour return window, and notify customer/merchant. | `OrderDeliveryLifecycleService` marks persisted orders delivered from completed missions, opens the 72-hour return window, writes immutable order events, and publishes a notification outbox event for customer/merchant recipients. |
| [ ] | Partial | Settlement starts | Courier payable, shortfall, merchant payout timing, and return hold are posted. | Delivery completion now starts idempotent merchant payout accruals with a 72-hour return hold and immutable ledger entries. Settlement also persists delivery shortfalls/adjustments, secured payout reads, admin ledger inspection, and eligibility promotion; courier/relay payout posting and provider payout execution remain. |

## Happy Path: Relay Delivery

| Done | State | Step | Required backend behavior | Evidence or gap |
| --- | --- | --- | --- | --- |
| [x] | Implemented | Reject food/perishable relay route | Food and perishables cannot be routed to Point de Relai by default. | `OrderProcessing` and `RelayParcelPolicy` enforce the rule. |
| [x] | Implemented | Create relay parcel | Eligible relay order creates parcel record and assigns relay point/locker. | `RelayParcelService` creates deposited relay parcels, assigns active free lockers, records deposit custody events, and rejects food/perishable categories. |
| [x] | Implemented | Courier deposits at relay | Relay mission can move to deposited only with proof. | `DeliveryMissionWorkflow` covers transition. |
| [x] | Implemented | Generate pickup code/QR | API must generate hashed numeric code and optional QR nonce with expiry/attempt limits. | `RelayParcelService` generates hashed numeric codes and QR nonces, verifies either credential, enforces expiry, one-time use, and attempt limits. |
| [x] | Implemented | Release requires code and identity validation | Relay release requires pickup code and identity validation. | `DeliveryMissionWorkflow` enforces both flags at policy level. |
| [ ] | Partial | Track delayed parcels | Parcels after 2 weeks become storage-fee candidates. | `RelayParcelPolicy`, `RelayParcelService`, `RelayParcelApplicationService`, opt-in scheduler, and admin monitoring evaluate, persist, count, and list delayed/review parcels. Delay/review outbox events, storage-fee assessments, and idempotent ledger deltas are persisted; final tariff decision remains. |
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
| [ ] | Partial | Courier reports problem | Mission can be moved to problem with reason. | `DeliveryMissionController` and `RelayParcelController` persist role-protected problem reports with actor, metadata, and idempotency. Delivery mission support can requeue pre-pickup problem missions or cancel them with an auditable resolution record, and assigned delivery problems publish `DELIVERY_PROBLEM_REPORTED` outbox events; broader investigation workflow and provider delivery remain. |
| [ ] | Not implemented | Customer unavailable | Reschedule, fallback relay, support intervention, or failed delivery state. | Missing. |
| [ ] | Not implemented | Relay locker unavailable | Alternative locker/relay/manual custody workflow. | Missing. |
| [ ] | Not implemented | Package lost/damaged | Responsibility assignment, evidence, support investigation, ledger liability. | Return/settlement docs only. |
| [ ] | Partial | Courier no-show | Mission expiry, reassign, courier penalty/support workflow. | Admin can reassign missions before pickup, force a support problem state, run automatic expiry/no-show scans that move stale offered or accepted missions to `PROBLEM_REPORTED`, and requeue or cancel problem missions with a persisted support resolution; courier penalty and full investigation workflow remain. |
| [x] | Implemented | Duplicate pickup/delivery submission | Idempotency key plus state guard prevents duplicate side effects. | `delivery_mission_idempotency_keys` and `DeliveryMissionService.transitionIdempotent` replay successful pickup, delivery, relay-deposit, relay-release, and problem operations while rejecting reused keys for different operations. Delivery PIN retries replay before consuming the PIN again. |

## Delivery Data That Must Be Persisted

| Done | State | Data | Purpose | Evidence or gap |
| --- | --- | --- | --- | --- |
| [x] | Implemented | `merchant_sub_orders` | Seller acceptance/preparation/ready state per merchant. | Flyway table, JPA entity, repository, and service exist. |
| [x] | Implemented | `delivery_missions` | Courier assignment, pickup, delivery, route, cost, proof. | `DeliveryMission`, `DeliveryMissionRepository`, and `DeliveryMissionService` persist assignments, statuses, proof metadata, relay release checks, and shortfall values. |
| [x] | Implemented | `delivery_pins` | Direct delivery PIN validation and attempt control. | `DeliveryPinService` stores only hashed PINs and enforces expiry, one-time use, and a five-attempt limit; `/deliver` validates the PIN before the workflow transition. |
| [x] | Implemented | `relay_parcels` | Parcel custody at relay, locker, delay, pickup/release. | `RelayParcelPersistenceService` and `RelayParcelController` persist and expose parcel state, category, locker, source references, listing/detail, release, problems, and delayed evaluation. |
| [x] | Implemented | `relay_pickup_codes` | Hashed numeric/QR pickup credentials. | `RelayParcelPersistenceService` and `RelayParcelController` persist hashed numeric/QR credentials, expiry, usage counters, and safe public responses that never return raw secrets. |
| [ ] | Partial | `relay_custody_events` | Deposit, pickup, Sequo collection, lost/damaged evidence. | `RelayParcelPersistenceService` persists deposit and release events with actor and idempotency data; broader event handling remains. |
| [x] | Implemented | `order_events` | Immutable audit trail for order and delivery state changes. | `V10__add_order_delivery_lifecycle.sql` and `CustomerOrderEventRecordRepository` persist accepted, delivered, and return-window-opened events with source and actor references. |
| [x] | Implemented | `settlement_ledger_entries` | Courier/relay payable, shortfalls, holds, adjustments. | `SettlementPersistenceService` persists immutable ledger entries, idempotently records delivery shortfalls and adjustments, and reads entries by source. Automatic payout execution remains. |

## API Surface Required Before Delivery Apps Work

| Done | State | Endpoint family | Needed endpoints |
| --- | --- | --- | --- |
| [x] | Implemented | Merchant order workflow | List/detail/SLA plus accept, reject, start preparation, mark packed/ready, and handoff verification with admin/merchant role checks. |
| [x] | Implemented | Courier missions | List/detail assigned missions, accept, pickup with proof, deliver with proof/PIN, deposit at relay, relay release, and report problem. |
| [ ] | Partial | Relay operations | List parcels, deposit, validate pickup code/QR, release to customer, report problem, delayed parcel list. | Parcel creation, pickup-code, validation/release, detail/listing, delayed evaluation, storage-fee assessment/listing, and incident endpoints are available; scheduler and support resolution remain. |
| [ ] | Partial | Customer tracking | Read order delivery status, ETA, relay instructions, pickup code state, proof-safe delivery confirmation. | `/api/delivery/tracking/{deliveryCode}?orderId=...` returns proof-redacted delivery status and timestamps; ETA, relay instructions, and pickup-code state remain. |
| [ ] | Partial | Admin dispatch | Reassign courier, pause courier, force problem state, view capacity, view delayed parcels, resolve failed deliveries. | Admin can assign/reassign before pickup, pause/unpause couriers, block assignment to paused couriers, surface active courier pauses in monitoring, cancel missions, force problem state, expire stale missions, requeue/cancel problem missions with audit history, and view monitoring; courier penalties and richer failed-delivery resolution remain. |

## Security Requirements For Delivery

| Done | State | Requirement | Why it matters |
| --- | --- | --- | --- |
| [ ] | Partial | Merchant ownership checks | Service rejects wrong merchant scope and controller requires non-admin merchant requests to match the authenticated merchant principal; persisted staff membership/merchant-scope claims remain. |
| [x] | Implemented | Courier mission ownership checks | A courier must not pickup/deliver another courier's assigned mission. | `DeliveryMissionService` rejects accept, pickup, delivery, relay deposit, and problem updates from a non-assigned courier. |
| [x] | Implemented | Relay scope checks | `RelayParcelService` rejects release when the relay actor is operating on another relay point's parcel. |
| [x] | Implemented | One-time pickup/delivery credentials | Relay pickup credentials and direct delivery PINs are one-time, hashed, expiring, and attempt-limited, with direct PIN validation integrated into `/deliver`. |
| [ ] | Partial | Proof tamper controls | Proof photos, GPS hints, timestamps, and actor ID must be immutable after submission. | Proof actor IDs and timestamps are persisted separately for pickup, relay deposit, and customer drop-off; service transitions reject duplicate proof submissions, while database-level audit/immutability remains. |
| [x] | Implemented | Idempotency | Relay release is idempotent by key in `RelayParcelService`; delivery mission pickup, delivery, relay-deposit, relay-release, and problem endpoints use persisted idempotency keys. |

## Recommended Implementation Order

1. [x] Add Flyway migrations for `merchant_sub_orders`, `delivery_missions`, `delivery_pins`, `relay_parcels`, `relay_pickup_codes`, `relay_custody_events`, and required status constraints.
2. [x] Implement repository-backed merchant fulfillment service using `MerchantFulfillmentWorkflow`.
3. [x] Implement repository-backed delivery mission service using `DeliveryMissionWorkflow` and `DeliveryAssignmentPolicy`.
4. [x] Implement relay parcel service using `RelayParcelPolicy`, hashed pickup codes, locker assignment, and custody events.
5. [ ] Add merchant, courier, relay, customer tracking, and admin dispatch endpoints with RBAC/ownership checks. Merchant, courier, relay, customer tracking, and admin-dispatch endpoints are implemented, including courier pause guards, stale mission expiry, and audited problem requeue/cancel; persisted merchant memberships remain.
6. [ ] Add notification/outbox events and settlement ledger posting. Delivery completion, delivery problem, relay delay/review, and return/refund workflows now publish workflow-specific outbox events; broader workflow publishers and provider delivery remain.
7. [ ] Add problem handling, re-assignment, failed delivery, delayed relay fees, and return-to-seller automation after product thresholds are finalized. Problem states, pre-pickup re-assignment, audited problem requeue/cancel, mission expiry/no-show scans, delayed relay fee assessments, and return intake/receipt automation exist; courier penalties, richer support investigation, and final return-to-seller thresholds remain.
