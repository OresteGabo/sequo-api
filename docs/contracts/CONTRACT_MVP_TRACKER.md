# SEQUO contract MVP tracker

Source contract: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/contracts/contrat_mvp_sequo.tex`

Scope rule for this file: this tracker lists only items that appear in the contract and its functional annex. Extra features requested later are intentionally excluded unless they overlap with contract scope.

Last audit: 2026-10-11 against the contract, project READMEs, current source scans, and the added `sequo-admin`, `sequo-developers`, and `sequo-services` folders.

Status legend:

- `[x]` implemented enough to count as present in code or a concrete app surface.
- `[ ]` not fully implemented yet, even if documented or partially scaffolded.
- `MVP` means the contract puts the item inside the MVP scope. Contract exclusions are listed separately.

## Project Map

| Project | Contract role | Observed state |
| --- | --- | --- |
| `sequo-api` | Central backend API | Broad domain coverage exists for auth, orders, delivery, merchant fulfillment, relay, returns, pricing, payments, subscriptions, referrals, bargaining, commissions, notifications, hub mobile, and admin monitoring. |
| `Sequo` | Customer mobile app iOS/Android | Customer shopping app exists with auth, catalog/home, basket, orders, account, notifications, pricing helpers and several UI flows. Checkout/payment, returns and bargaining are not fully end-to-end with backend actions yet. |
| `SequoVendor` | Merchant/vendor mobile app iOS/Android | Vendor app has auth, dashboard, order fulfillment, catalog browsing/editor UI, local store availability/hours, notifications, account/settings. Some catalog/store writes remain local because merchant-scoped backend routes are not present. |
| `SequoRider` | Courier mobile app iOS/Android | Mostly app scaffold plus network/theme and extensive dispatch/route/payout documentation. The operational mission app is not complete yet. |
| `SequoHub` | Relay-point module/app | Hub app exists with auth, dashboard, lockers, scan/receive, handover, activity, settings and relay workflow docs. Much of the current UI is local/sample state; backend hub/relay APIs exist separately. |
| `sequo-admin` | Internal SEQUO web admin interface | Vue/Vite/TypeScript admin template exists with broad operational demo screens and planning docs. It is not yet a production admin web app wired to real auth/API data. |

Additional folders found but not counted as contract MVP app deliverables: `sequo-developers` is a developer documentation site, and `sequo-services` is the public marketing/product website.

## Core Deliverables

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [x] | Yes | Central backend API | `sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain` | Keep endpoint polish ongoing, but the central API exists. |
| [x] | Yes | Customer mobile app for iOS and Android | `Sequo/shared`, `Sequo/androidApp`, `Sequo/iosApp` | Some server integrations may still need final production wiring. |
| [ ] | Yes | Rider mobile app for iOS and Android | `SequoRider/shared`, `SequoRider/androidApp`, `SequoRider/iosApp` | App shell/network/theme exist, but mission acceptance, pickup, delivery status, history and payout screens are not complete. |
| [x] | Yes | Vendor/merchant mobile app for iOS and Android | `SequoVendor/shared`, `SequoVendor/androidApp`, `SequoVendor/iosApp` | Seller catalog and store-hours writes are local/mock until backend routes exist. |
| [ ] | Yes | Internal SEQUO web admin interface | `sequo-admin` exists as a Vue/Vite/TypeScript admin template with demo operational screens and docs; backend admin monitoring exists in `sequo-api/domain/admin`. | Replace template/demo content with real admin auth, API-backed dashboards, users, merchants, orders, deliveries, payments, commissions, settings and relay controls. |
| [x] | Yes, if needed | Minimum relay-point module | `sequo-api/domain/relay`, `sequo-api/domain/hub`, `SequoHub/shared` | Dedicated relay app can be post-MVP by contract, but current `SequoHub` already covers much of the relay module. |

## Accounts, Roles, Access

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [x] | Yes | User creation and login | `sequo-api/domain/auth`, `Sequo/core/auth`, `SequoVendor/core/auth`, `SequoHub/core/auth` | Rider app auth is not yet implemented beyond general scaffold. |
| [x] | Yes | Base roles: client, courier, merchant/vendor, SEQUO admin | `sequo-api/domain/auth/RoleAuthorities.kt`, `AuthorizationPolicy.kt`, `MerchantMembership.kt` | Role-specific UI coverage is uneven, especially rider and admin web. |
| [x] | Yes, if relay included | Simplified relay role/access | `sequo-api/domain/hub`, `SequoHub/core/auth`, `SequoHub/App.kt` | Needs final backend-role checks across all hub endpoints if not already enforced everywhere. |
| [x] | Yes | Profile information needed for orders and deliveries | `sequo-api/domain/auth`, `sequo-api/domain/order`, `Sequo/ui/account`, `SequoVendor/ui/account` | Admin-facing profile management UI still belongs in web admin. |

## Catalog And Products

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [ ] | Yes | Merchants or SEQUO can add, edit and deactivate products | Read/catalog and local editor UI in `SequoVendor/core/catalog` and `SequoVendor/ui/catalog`; backend catalog/domain exists in `sequo-api/domain/commerce`, `domain/catalog`, `domain/platform` | Missing complete merchant-scoped product create/update/deactivate/upload API connected to vendor app. |
| [x] | Yes | Manage displayed price, SEQUO margin/commission and service fees | Backend commission/pricing exists in `sequo-api/domain/commission`, `domain/pricing`, commerce/order pricing models | Admin web UI for configuring all rates remains incomplete. |
| [x] | Yes | Product photos prioritize real-time photos when necessary | `sequo-api/domain/catalog/ProductMediaPolicy.kt`, `Sequo/CATALOG_AND_ORDERING.md`, `SequoVendor` product editor placeholder | Camera capture/upload enforcement needs final merchant write/upload endpoint. |
| [x] | Yes | Generic images may be allowed for standard products | `sequo-api/domain/catalog/ProductMediaPolicy.kt`, `Sequo` and `SequoVendor` catalog assets | Backend policy exists; production upload workflow still needs seller endpoint. |
| [x] | Yes | Food options, supplements, variants or toppings | `sequo-api/domain/catalog/FoodCustomization.kt`, related tests | Vendor-facing editing of food options still needs complete backend write flow. |

## Orders And Cart

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [ ] | Yes | Customer creates an order | `sequo-api/domain/order`, `Sequo/ui/basket`, `Sequo/logic/OrderLogic.kt` | Backend order logic and customer basket UI exist, but a verified customer-app order submission flow is not complete. |
| [ ] | Yes | Payment or payment validation before final confirmation | `sequo-api/domain/order/PendingPaymentCheckout.kt`, `sequo-api/domain/payment`, `Sequo/ui/basket`, `Sequo/PAYMENTS_AND_REFERRALS.md` | Backend/payment preparation and customer payment selector exist, but real Yas/Moov provider validation and end-to-end checkout are not complete. |
| [x] | Yes | Cart can contain multiple products | `Sequo/ui/basket`, `Sequo/model/SequoModels.kt`, `sequo-api/domain/order` | None identified from contract scope. |
| [x] | Yes | Products can be grouped before delivery | `sequo-api/domain/delivery/SequoConsolidation.kt`, `ConsolidationController.kt`, `domain/cooperative` | Rider/admin operational UI for consolidation remains partial. |
| [x] | Yes | Merchant prepares package and marks it ready | `sequo-api/domain/delivery/MerchantFulfillmentController.kt`, `SequoVendor/core/fulfillment`, `SequoVendor/ui/orders` | Vendor app has fulfillment actions; production depends on merchant context availability. |

## Delivery And Logistics

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [x] | Yes | Express, classic and multi-day delivery distinction | Backend delivery/order models and `SequoRider` docs | Full customer/admin/rider UI for all delivery types is not complete. |
| [x] | Yes | Salaried SEQUO couriers and independent couriers | `sequo-api/domain/delivery`, `SequoRider/README.md`, `FLEET_DISPATCH.md`, `PAYOUT_STRUCTURE.md` | Rider app operational implementation is still incomplete. |
| [x] | Yes | Prioritize SEQUO couriers for subscribed customers | `sequo-api/domain/subscription`, `domain/delivery`, `SequoRider` dispatch docs | Needs complete admin/rider UI visibility. |
| [x] | Yes | Fallback to independent couriers when SEQUO couriers unavailable | `sequo-api/domain/delivery/DeliveryAssignment.kt`, courier availability tests | Rider UI not complete. |
| [x] | Yes | Manage delivery distance and price formula | `sequo-api/domain/pricing/DeliveryPricing.kt`, `Sequo/domain/DeliveryPricing.kt` | Production map/distance provider integration may still need external setup. |
| [x] | Yes | Internal logistics tracking by SEQUO | `sequo-api/domain/delivery`, `domain/admin/AdminMonitoring.kt` | Full admin web tracking UI not found. |
| [x] | Yes | Group products from nearby merchants or cooperatives | `sequo-api/domain/cooperative`, `domain/delivery/SequoConsolidation.kt`, `SequoRider/ROUTE_OPTIMIZATION.md` | Admin validation UI and rider execution screens remain partial. |
| [x] | Yes | SEQUO validates cooperatives/groupings | `sequo-api/domain/cooperative`, `domain/delivery/ConsolidationController.kt` | Complete admin web workflow still needed. |

## Delivery Pricing

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [x] | Yes | 400 CFA for 5 km or less | `sequo-api/domain/pricing/DeliveryPricing.kt`, tests | None identified from contract scope. |
| [x] | Yes | 100 CFA per additional km | `sequo-api/domain/pricing/DeliveryPricing.kt`, tests | None identified from contract scope. |
| [x] | Yes | Subscription/discount rules configurable or prepared | `sequo-api/domain/subscription/SubscriptionBenefits.kt`, pricing config tests | Admin configuration UI not complete. |
| [x] | Yes | SEQUO covers shortfall when customer delivery fee is below freelancer pay | `sequo-api/domain/settlement`, `SequoRider/PAYOUT_STRUCTURE.md` | Rider/admin payout UI not complete. |
| [x] | Yes | Salaried couriers excluded from freelancer delivery-fee calculation | `sequo-api/domain/delivery`, `domain/settlement`, `SequoRider/PAYOUT_STRUCTURE.md` | Rider/admin payout UI not complete. |

## Payments

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [ ] | Yes | Integrate or prepare Yas Togo and Moov Africa payments | `sequo-api/domain/payment`, `Sequo/PAYMENTS_AND_REFERRALS.md` | Backend preparation exists, but production provider integration/credentials/callback validation are not fully confirmed in apps. |
| [ ] | Yes | Order must be paid before validation | `sequo-api/domain/order/PendingPaymentCheckout.kt`, payment/order tests | Rule exists in backend domain/tests, but production provider validation and customer checkout wiring are not complete. |
| [x] | Yes | Prioritize electronic transfers | `sequo-api/domain/payment`, `Sequo/PAYMENTS_AND_REFERRALS.md`, `SequoRider/PAYOUT_STRUCTURE.md` | Final provider setup still needed. |
| [ ] | Yes | Pay couriers through same systems when possible | `sequo-api/domain/settlement`, `SequoRider/PAYOUT_STRUCTURE.md` | Operational payout execution UI/provider integration not complete. |

## Subscriptions And Referral

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [x] | Yes | Technical base for monthly subscriptions | `sequo-api/domain/subscription/SubscriptionBenefits.kt` | Customer/admin UI for subscription management may need completion. |
| [x] | Yes | Automatic percentage discounts on per-km delivery for subscribers | `sequo-api/domain/subscription`, `domain/pricing` | Admin configuration UI not complete. |
| [x] | Yes | Lower rates for long-term subscribers, rule to be defined | `sequo-api/domain/subscription` | Business values still need SEQUO validation per contract. |
| [x] | Yes | Referral gives delivery credit, not cash | `sequo-api/domain/referral/ReferralDeliveryCredit.kt`, tests | Customer/admin UI may need completion. |

## Bargaining / Price Negotiation

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [ ] | Yes | Customer can propose a lower price for eligible products | `sequo-api/domain/bargaining/Bargaining.kt`, `sequo-api/domain/commerce/CommerceApi.kt`, `Sequo/BARGAINING_FLOW.md`, `Sequo` negotiation UI hooks | Backend bargaining routes/domain and product UI hook exist, but the customer app does not have a complete submit/status flow and vendor response UI is not implemented in `SequoVendor`. |
| [x] | Yes | Seller and customer have limited attempts, default 3 | `sequo-api/domain/bargaining/Bargaining.kt`, tests | Full app UX across both sides remains partial. |
| [x] | Yes | Lock bargaining after attempts are exhausted | `sequo-api/domain/bargaining/Bargaining.kt`, tests | Full app UX remains partial. |
| [x] | Yes | Accepted minimum price can remain locked 24 hours | `sequo-api/domain/bargaining/Bargaining.kt`, tests | Full checkout/application of locks needs final end-to-end confirmation. |
| [ ] | Yes | Seller can disable bargaining on products | Policy/model exists in bargaining/catalog docs and backend domain | Vendor product editor currently hides/disables bargaining; full merchant backend write route is missing. |
| [ ] | Optional if SEQUO validates | Show historical accepted minimum price | `Sequo/BARGAINING_FLOW.md` documents the rule | Not confirmed as implemented end-to-end. |

## Relay Points

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [x] | Yes | Relay point as alternative when customer unavailable | `sequo-api/domain/relay`, `SequoHub/README.md`, `LOCKER_WORKFLOWS.md` | Full customer/rider reroute UX needs final verification. |
| [x] | Yes | Exclude food, perishables and sensitive products | `sequo-api/domain/relay/RelayParcelPolicy.kt`, `SequoHub` docs | None identified from contract scope. |
| [x] | Yes | Pickup by numeric code, QR code and identity check | `sequo-api/domain/relay`, `SequoHub/feature/scan`, `feature/handover`, docs | Needs final backend-connected scanner flow if not already wired. |
| [x] | Yes | Track lockers/boxes as excluded hardware, not development cost | `SequoHub/README.md`, `LOCKER_WORKFLOWS.md` | Hardware purchase remains outside MVP price per contract. |
| [x] | Yes | Storage fees after more than two weeks | `sequo-api/domain/relay/RelayStorageFee.kt`, `SequoHub/STORAGE_PENALTIES.md` | Admin policy UI may need completion. |
| [x] | Yes | After about one month, return to seller or apply policy | `sequo-api/domain/relay`, `SequoHub/STORAGE_PENALTIES.md` | Admin policy UI may need completion. |

## Returns And Refunds

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [ ] | Yes | Return requests possible within 72 hours after delivery | `sequo-api/domain/returns`, `Sequo/RETURNS_POLICY.md`, tests, customer order return labels | Backend return rules exist, but the customer return request flow is not complete end-to-end in the app. |
| [ ] | Yes | Eligible returns can be dropped at relay point | `sequo-api/domain/returns`, `sequo-api/domain/relay`, `SequoHub/RETURNS_HANDLING.md`, `SequoHub/feature/handover` | Backend and hub UI surfaces exist, but full customer-to-hub return flow needs final wiring and verification. |
| [x] | Yes | SEQUO recovers returned product | `sequo-api/domain/returns`, `SequoHub/RETURNS_HANDLING.md` | Admin/collection UI may need completion. |
| [x] | Yes | Refund after SEQUO receives and checks product condition | `sequo-api/domain/returns/ReturnProcessing.kt`, tests | Payment provider refund execution needs final integration. |
| [x] | Yes | Refund instruction within reasonable delay, e.g. one week | `sequo-api/domain/returns`, `Sequo/RETURNS_POLICY.md` | Admin/payment execution UI remains partial. |

## Commissions And SEQUO Fees

| Done | MVP | Contract item | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [x] | Yes | SEQUO commission by percentage or price bands | `sequo-api/domain/commission` | Admin configuration UI incomplete. |
| [x] | Yes | Default commission proposed at 15 percent | `sequo-api/domain/commission`, commission tests | None identified from contract scope. |
| [x] | Yes | Configurable rates, e.g. 5 to 15 percent by merchant/category | `sequo-api/domain/commission/MerchantCommissionConfiguration.kt` | Admin UI incomplete. |
| [x] | Yes | Service fees, margins and delivery fees visible or calculable | `sequo-api/domain/commission`, `domain/pricing`, `SequoVendor/ui/orders` | Full admin reports UI incomplete. |

## Annex A: User Journeys

| Done | MVP | Contract journey | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [x] | Yes | Customer creates account or logs in | `sequo-api/domain/auth`, `Sequo/core/auth`, `Sequo/feature/auth` | None identified from contract scope. |
| [x] | Yes | Customer browses products and adds items to cart | `Sequo/ui/home`, `ui/catalog`, `ui/basket` | Production backend catalog sync may still need final data quality. |
| [ ] | Yes | Customer can negotiate when allowed | `sequo-api/domain/bargaining`, `sequo-api/domain/commerce/CommerceApi.kt`, `Sequo` negotiation UI hooks | Backend and product UI hook exist, but complete customer submit/status UX and vendor response UX are missing. |
| [ ] | Yes | Customer chooses home delivery or relay point | Relay backend/hub exist; customer app delivery choice needs final verification | Finish customer checkout delivery/relay selection if absent. |
| [ ] | Yes | Customer pays before order validation | Backend prepared; customer docs/UI prepared | Final payment provider integration still incomplete. |
| [x] | Yes | Customer tracks order and delivery | `Sequo/ui/orders`, `sequo-api/domain/delivery/DeliveryTrackingController.kt` | UI and backend tracking surface exist; final live tracking quality depends on rider/admin integration. |
| [ ] | Yes | Customer requests return in allowed delay | Backend and docs exist | Complete/verify customer return UI and endpoint wiring. |
| [ ] | Yes | Merchant adds products with prices, descriptions and photos | `SequoVendor/ui/catalog` local/editor UI | Backend merchant product write/upload route missing. |
| [ ] | Yes | Merchant indicates whether bargaining is allowed | `SequoVendor` hides/disables bargaining for now | Backend merchant catalog write route and vendor negotiation UI missing. |
| [x] | Yes | Merchant receives orders and prepares packages | `SequoVendor/ui/orders`, `SequoVendor/core/fulfillment`, `sequo-api/domain/delivery/MerchantFulfillmentController.kt` | Needs real merchant context in production accounts. |
| [x] | Yes | Merchant marks package ready for SEQUO pickup | `SequoVendor/core/fulfillment`, `MerchantFulfillmentController.kt` | None identified from contract scope. |
| [x] | Yes | Merchant follows sales, returns and payment statuses according to rights | `SequoVendor/ui/dashboard`, `ui/orders`, `ui/account`; backend orders/settlements/returns | Returns/payment visibility can be expanded after backend endpoints are fully exposed. |
| [ ] | Yes | Rider receives or accepts mission | `sequo-api/domain/delivery/DeliveryMissionController.kt`; `SequoRider` docs/scaffold | Rider app mission UI and API client incomplete. |
| [ ] | Yes | Rider picks up from merchant, SEQUO or relay | Backend delivery/hub/relay domains; `SequoRider` docs | Rider app pickup flow incomplete. |
| [ ] | Yes | Rider delivers to customer or deposits at relay | Backend delivery/relay domains; `SequoRider` docs | Rider app delivery/deposit flow incomplete. |
| [ ] | Yes | Rider validates delivery steps in app | Backend delivery/pin services; `SequoRider` docs | Rider app validation flow incomplete. |
| [ ] | Yes | Rider sees independent courier pay calculation | Backend settlement/delivery domains; `SequoRider/PAYOUT_STRUCTURE.md` | Rider payout UI incomplete. |
| [x] | Yes | Relay receives package and assigns code/QR/locker | `sequo-api/domain/hub`, `domain/relay`, `SequoHub/feature/hub`, `feature/scan` | Needs final backend connectivity verification. |
| [x] | Yes | Relay validates customer pickup and stores history | `SequoHub/feature/handover`, `feature/activity`, backend hub/relay domains | Needs final backend connectivity verification. |

## Annex A: Business Parameters

| Done | MVP | Configurable or prepared parameter | Where done | Remaining gap |
| --- | --- | --- | --- | --- |
| [x] | Yes | Minimum delivery price | `sequo-api/domain/pricing` | Admin UI incomplete. |
| [x] | Yes | Price per kilometer | `sequo-api/domain/pricing` | Admin UI incomplete. |
| [x] | Yes | Commission percentages | `sequo-api/domain/commission` | Admin UI incomplete. |
| [x] | Yes | Subscriber discount rates | `sequo-api/domain/subscription`, `domain/pricing` | Admin UI incomplete. |
| [x] | Yes | Referral credits | `sequo-api/domain/referral` | Admin UI incomplete. |
| [x] | Yes | Bargaining attempt count | `sequo-api/domain/bargaining` | Admin UI incomplete. |
| [x] | Yes | Negotiated price lock duration | `sequo-api/domain/bargaining` | Admin UI incomplete. |
| [x] | Yes | Return request delay | `sequo-api/domain/returns` | Admin UI incomplete. |
| [x] | Yes | Relay storage delay and fees | `sequo-api/domain/relay`, `SequoHub/STORAGE_PENALTIES.md` | Admin UI incomplete. |
| [x] | Yes | Product categories excluded from relay/returns | `sequo-api/domain/relay`, `domain/returns`, catalog policy | Admin UI incomplete. |

## Contract Exclusions

These items are explicitly excluded from the fixed MVP price unless there is a separate written agreement. They are not MVP implementation gaps.

| Excluded item | Status |
| --- | --- |
| Google Maps, map providers, SMS, email, notification-provider and external-service fees | Excluded cost |
| App Store, Google Play, domain, hosting, server and paid database fees | Excluded cost |
| Bank, mobile money, withdrawal, operator commission and tax fees | Excluded cost |
| Lockers, boxes, phones, motos, bicycles, delivery material and relay hardware | Excluded cost |
| SEQUO employee salaries and payments to independent couriers | Excluded cost |
| Drafting legal documents for end customers | Excluded service |
| Complete regulatory compliance across sales, delivery, payments, data or regulated goods | Excluded service |
| Maintenance after MVP delivery | Excluded service |
| Features requested after MVP scope validation | Excluded service |
| Large-scale performance, high availability and advanced security audits | Excluded service |

## Highest MVP Gaps To Close

Only contract-listed gaps are included here.

1. Finish the internal SEQUO web admin interface for users, merchants, orders, deliveries, payments, commissions, settings and relay controls.
2. Finish SequoRider as a real mission app: receive/accept missions, pickup, delivery/deposit, delivery validation, history and freelancer payout visibility.
3. Add merchant-scoped catalog write/upload/deactivate backend routes and wire them to `SequoVendor`.
4. Complete production payment provider integration for Yas Togo and Moov Africa, including order validation and refund/payout execution paths.
5. Complete cross-app bargaining UX, especially seller response and seller product-level bargaining enable/disable.
6. Finalize customer checkout relay selection, customer return request UI, and full customer-to-hub return flow.
7. Build/finish admin configuration UI for delivery pricing, commissions, subscription discounts, referral credits, relay storage fees, return windows and excluded categories.
