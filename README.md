# Sequo API

Sequo API is the Spring Boot backend service for SEQUO, a local commerce, logistics, wallet, commission, and settlement platform. The service is responsible for trusted server-side state: authentication, role authorization, catalog and order workflows, pricing, bargaining, cooperative markets, relay logistics, digital wallet payments, returns, refunds, and merchant/courier payout accounting.

This repository is currently a Spring Boot 4.1 / Kotlin / JVM 21 starter. The documentation in this repository defines the intended production backend contract before domain modules are implemented.

## Source Material Inspected

The backend documentation was generated from the legacy Sequo product folder and its planning documents:

| Legacy source | Backend relevance |
| --- | --- |
| `README.md` | Product purpose, actors, order lifecycle, operational risks |
| `docs/TECHNICAL_README.md` | Early API, auth, environment, and testing direction |
| `docs/FEATURES_AND_REQUIREMENTS.md` | MVP roles, catalog, order, delivery, admin, support requirements |
| `docs/USER_ROLES_AND_PERMISSIONS.md` | Role permissions and access boundaries |
| `docs/ORDER_LIFECYCLE.md` | Normal, pickup, rejection, cancellation, payment failure, and dispute flows |
| `docs/MARKET_LOME_NOTES.md` | Local logistics, landmark addressing, manual verification, support needs |
| `docs/REALISTIC_SCENARIOS.md` | Edge cases for stock, bad addresses, fake orders, traffic, and offline users |
| `docs/PRODUCT_VALIDATION_BRIEF.md` | Core value, risks, revenue model candidates |
| `docs/TASKS.md` and `docs/ROADMAP.md` | Backend backlog, auth, database, pricing, payment, refund, audit, background jobs |
| `docs/original/*.md` | French source rules for baskets, IDs, relay points, returns, pricing, commissions, bargaining, payouts |
| `docs/diagrams/plantuml/auth/*.md` | JWT, refresh token rotation, RBAC, audit, security boundary requirements |

## Authoritative Business Rules

The current backend scope supersedes older legacy notes where they conflict:

| Topic | Current backend rule |
| --- | --- |
| Payment model | Zero-cash by default. Integrate Yas Togo and Moov Africa wallets. Do not implement cash withdrawal operations. |
| Delivery pricing | Minimum 400 CFA for trips up to 5 km, then 100 CFA per extra km. The standard per-km economic model is 100 CFA/km. |
| Subscriptions | Monthly billing tiers can discount per-km fees. Multi-year loyalty multipliers further reduce eligible per-km fees. |
| Merchant commissions | Default commission is 15%, configurable per merchant from 5% to 15%. |
| Listing price | Listing price equals `(base price + platform margin) + service fees + delivery fee`. |
| Bargaining | 3 attempts per customer/seller pair, accepted minimum prices lock for 24 hours, historical accepted minima are retained, merchants can toggle bargaining per item. |
| Cooperative markets | Independent sellers can be grouped into market cooperatives, for example Marche de Mulhouse, with consolidated packaging before final delivery. |
| Returns | 72-hour return window. Point de Relai drop-off is required for return intake. Refund triggers after physical item receipt. |
| Settlements | Merchant payouts are scheduled within 1 week of package receipt, net of commissions, refunds, disputes, and adjustments. |
| Shortfalls | If customer delivery payment is lower than courier cost, Sequo covers the shortfall through an explicit settlement ledger. |

## Documentation Map

| File | Purpose |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Spring Boot backend architecture, modules, state machines, integrations, deployment assumptions |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Owner-note API requirement matrix with implementation checkboxes and backlog priorities |
| [DOMAIN_SERVICES.md](DOMAIN_SERVICES.md) | Domain service responsibilities and workflow rules |
| [DELIVERY.md](DELIVERY.md) | Delivery and fulfillment audit: direct delivery, relay delivery, Sequo consolidation, proof, gaps, and build order |
| [NOTIFICATION_SYSTEM.md](NOTIFICATION_SYSTEM.md) | FCM, in-app notifications, SMS fallback, device tokens, event listeners, routing, delivery audit |
| [WEBSOCKET_ARCHITECTURE.md](WEBSOCKET_ARCHITECTURE.md) | Spring WebSocket/STOMP realtime channels, auth, topics, rider radar, bargaining, relay, admin dashboards |
| [API_SPEC.md](API_SPEC.md) | REST API conventions, endpoint families, idempotency, response envelope, error model |
| [PRICING_ENGINE.md](PRICING_ENGINE.md) | Delivery, subscription, loyalty, item, service fee, and pricing audit rules |
| [COMMISSION_MODEL.md](COMMISSION_MODEL.md) | Merchant commission, platform margin, cooperative split, payout, and shortfall accounting |
| [SETTLEMENTS_AND_RETURNS.md](SETTLEMENTS_AND_RETURNS.md) | 72-hour return flow, relay intake, refund triggers, payout timing, financial settlement lifecycle |
| [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) | Logical relational schema, tables, enums, indexes, and constraints |
| [SECURITY.md](SECURITY.md) | Auth, RBAC, secrets, wallet safety, PII, audit, idempotency, and operational security |
| [CI_CD.md](CI_CD.md) | GitHub Actions CI/CD workflow, dependency updates, branch protection, and deployment requirements |
| [DOCKER.md](DOCKER.md) | Docker image, Docker Compose runtime, environment variables, and Kubernetes timing |
| [docs/diagrams/plantuml](docs/diagrams/plantuml/README.md) | PlantUML diagrams for architecture, payments, orders, pricing, returns, settlements, bargaining, cooperatives, database, and security |

## Core Backend Capabilities

- Multi-role authentication for customers, merchants, merchant staff, couriers, relay partners, support agents, admins, and super admins.
- Role-based and ownership-based authorization on every protected endpoint.
- Server-side order truth with immutable event history and explicit status transitions.
- Pricing engine for distance, minimum fees, subscriptions, loyalty multipliers, service fees, and delivery shortfalls.
- Dynamic merchant commission engine with per-merchant rates from 5% to 15%.
- Bargaining workflow with attempt limits, merchant toggles, accepted-price locks, and historical minimum tracking.
- Cooperative market orchestration for grouped independent sellers and single-package consolidation.
- Return and settlement engine with relay drop-offs, receipt-based refund triggers, payout scheduling, and ledger entries.
- Zero-cash wallet adapters for Yas Togo and Moov Africa.
- Audit logs for security, admin, financial, support, commission, refund, and payout actions.

## Current Stack

| Layer | Current choice |
| --- | --- |
| Language | Kotlin |
| Runtime | JVM 21 |
| Framework | Spring Boot 4.1 |
| Build | Gradle Kotlin DSL |
| Tests | JUnit Platform |
| Intended database | PostgreSQL with Flyway or Liquibase migrations |
| Intended API contract | REST under `/api/v1`, generated OpenAPI |

## Local Development

Run tests:

```bash
./gradlew test
```

Build:

```bash
./gradlew build
```

Run the same check used by CI:

```bash
./gradlew clean build --no-daemon --stacktrace
```

Run the API locally after web and persistence modules are added:

```bash
./gradlew bootRun
```

Run the API with PostgreSQL through Docker Compose:

```bash
docker compose up --build
```

## Environment Groups

Real secrets must live in local environment variables, a local uncommitted `.env`, or a deployment secret manager. Do not commit wallet provider keys, JWT signing keys, database passwords, webhook secrets, private OAuth secrets, or production URLs.

Expected configuration groups:

| Group | Examples |
| --- | --- |
| Runtime | `SPRING_PROFILES_ACTIVE`, public API URL, CORS origins |
| Database | JDBC URL, username, password, pool size |
| Auth | JWT issuer, audience, signing keys, access TTL, refresh TTL |
| Wallets | Yas Togo credentials, Moov Africa credentials, webhook secrets |
| Notifications | SMS, push, email, WhatsApp Business provider keys |
| Storage | Product images, KYC documents, proof photos |
| Observability | Log level, tracing, metrics, alert routing |

## Engineering Priorities

1. Add persistence and migration tooling before storing production data.
2. Implement auth and RBAC before exposing domain endpoints.
3. Build pricing, commission, bargaining, return, and settlement logic as tested domain services.
4. Make payment and wallet callbacks authenticated, idempotent, and auditable.
5. Use outbox events for financial, notification, and workflow side effects.
6. Generate and publish OpenAPI once controllers are implemented.

## Testing Expectations

Minimum backend test coverage before production:

| Area | Required tests |
| --- | --- |
| Auth | JWT validation, refresh rotation, logout, role checks, account lock |
| Pricing | Distance rounding, 400 CFA minimum, subscription discounts, loyalty multipliers, service fees |
| Commissions | Rate bounds, default 15%, merchant override, cooperative split, payout netting |
| Bargaining | 3-attempt limit, counter-offers, accepted price lock TTL, toggle enforcement |
| Orders | Valid transitions, invalid transition rejection, seller rejection, courier assignment |
| Returns | 72-hour eligibility, relay intake, physical receipt trigger, refund idempotency |
| Wallets | Yas/Moov callbacks, duplicate webhook handling, failed payment recovery |
| Security | Ownership checks, PII masking, audit event creation |
