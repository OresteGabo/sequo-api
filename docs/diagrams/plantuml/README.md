# Sequo PlantUML Diagrams

These diagrams document the intended Sequo API backend architecture and the currently implemented domain service flows.

Render with any PlantUML-compatible tool, for example:

```bash
plantuml docs/diagrams/plantuml/*.puml
```

Render embeddable SVGs into the generated folder:

```bash
plantuml -tsvg -o generated docs/diagrams/plantuml/*.puml
```

## Diagram Map

| File | Purpose |
| --- | --- |
| `system-context.puml` | External actors, Sequo API, storage, wallet, notification, and observability systems |
| `module-boundaries.puml` | Backend package/module ownership boundaries |
| `checkout-order-sequence.puml` | Customer checkout, pricing, wallet validation, and order handoff |
| `payment-processing-sequence.puml` | Payment attempt, provider callback, validation, cancellation, and refund responsibilities |
| `payment-policy-activity.puml` | Payment provider/feature policy checks, including no-cash constraints |
| `delivery-pricing-activity.puml` | Delivery pricing formula from distance to final customer delivery fee |
| `order-processing-activity.puml` | Implemented order processor template method flow |
| `order-state-machine.puml` | Target order lifecycle states and transitions |
| `return-refund-sequence.puml` | Return intake through relay, physical receipt, refund, and settlement adjustment |
| `return-state-machine.puml` | Target return lifecycle states |
| `settlement-ledger-flow.puml` | Ledger, payout, shortfall, hold, refund, and reconciliation flow |
| `bargaining-state-machine.puml` | Bargaining attempts, counter-offers, accepted locks, and expiry |
| `cooperative-market-flow.puml` | Multi-merchant cooperative order split and consolidation |
| `database-erd.puml` | High-level logical database relationships |
| `security-auth-rbac-sequence.puml` | Auth, JWT validation, RBAC, ownership, and audit flow |
| `notification-system-architecture.puml` | FCM, WebSocket, SMS fallback, outbox, routing, templates, and notification audit architecture |
| `notification-event-dispatch-sequence.puml` | Domain event to notification outbox, routing, delivery, retry, and audit flow |
| `websocket-stomp-architecture.puml` | Spring WebSocket/STOMP endpoint, JWT auth, destination authorization, topics, and broker |
| `rider-realtime-mission-flow.puml` | SequoRider availability, mission offer, WebSocket/FCM delivery, and mission acceptance flow |

## Generated SVGs

Generated SVGs live under `docs/diagrams/plantuml/generated/` and can be embedded in Markdown with normal image syntax:

```markdown
![Notification system architecture](docs/diagrams/plantuml/generated/notification-system-architecture.svg)
```
