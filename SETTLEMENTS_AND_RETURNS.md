# Settlements And Returns

This document defines how Sequo API handles returns, refunds, physical custody, merchant payouts, courier costs, and financial settlement.

## Authoritative Rules

| Topic | Rule |
| --- | --- |
| Return window | 72 hours after delivery/customer receipt |
| Return intake | Customer must drop item at a Point de Relai |
| Relay eligibility | Food and perishable products cannot be routed to Point de Relai by default |
| Delayed relay parcels | Fees may start after 2 weeks; return-to-seller timing needs owner approval |
| Refund trigger | Automated refund can start only after Sequo physically receives the item |
| Merchant payout | Scheduled within 1 week of package receipt, net of holds and adjustments |
| Shortfall | Sequo covers delivery fee shortfall when customer delivery payment is lower than courier fee |
| Wallet rails | Yas Togo and Moov Africa only for current backend scope |
| Cash | No cash withdrawal operations |

## Return Eligibility

Eligible when all are true:

- Order item belongs to a returnable product category.
- Delivery or pickup receipt occurred less than or equal to 72 hours ago.
- Item is not already returned, refunded, or under unresolved fraud lock.
- Customer is the order owner.
- Merchant and product policy allow return or admin override applies.

Ineligible by default:

- Return requested after 72 hours.
- Consumed/perishable goods where policy blocks returns.
- Restaurant/hot-food orders unless admin exception.
- Customer cannot provide required reason/evidence.
- Item was previously refunded.

## Return State Machine

| State | Meaning | Next states |
| --- | --- | --- |
| `REQUESTED` | Customer opened return request | `AWAITING_RELAY_DROPOFF`, `REJECTED` |
| `AWAITING_RELAY_DROPOFF` | Return reference and PIN issued | `DROPPED_AT_RELAY`, `EXPIRED` |
| `DROPPED_AT_RELAY` | Point de Relai accepted parcel | `IN_SEQUO_COLLECTION`, `RELAY_EXCEPTION` |
| `IN_SEQUO_COLLECTION` | Waiting for Sequo collection from relay | `RECEIVED_BY_SEQUO`, `LOST_IN_TRANSIT` |
| `RECEIVED_BY_SEQUO` | Physical item received and inspected | `REFUND_APPROVED`, `REFUND_REJECTED`, `DISPUTED` |
| `REFUND_APPROVED` | Refund decision made | `REFUND_PENDING` |
| `REFUND_PENDING` | Wallet refund in progress | `REFUNDED`, `REFUND_FAILED` |
| `REFUNDED` | Refund completed | terminal |
| `REJECTED` | Return denied | terminal |
| `EXPIRED` | Customer missed drop-off deadline | terminal |
| `DISPUTED` | Support/admin investigation | `REFUND_APPROVED`, `REFUND_REJECTED` |

## Return Flow

1. Customer requests return from order history.
2. API validates 72-hour eligibility.
3. Customer selects reason and preferred refund provider.
4. API creates return reference and short-lived return PIN.
5. Customer drops item at Point de Relai.
6. Relay partner validates return reference and PIN.
7. Relay creates custody event and assigns locker/case.
8. Sequo collects item from relay.
9. Admin or automated intake confirms physical receipt through `ReturnProcessingService`.
10. Refund responsibility is assigned.
11. Wallet refund is triggered through Yas Togo or Moov Africa.
12. Settlement ledger posts refund and payout adjustment.

## Physical Receipt Rule

Automated refunds must not be triggered merely because the customer opened a return or relay accepted a parcel. The refund trigger is Sequo physical receipt.

The domain service validates and records the following physical receipt data:

- Return ID.
- Receiving operator or automated intake ID.
- Timestamp.
- Relay/deposit reference.
- Condition assessment.
- Photos or evidence when applicable.
- Responsibility decision or pending dispute marker.

## Refund Responsibility

| Scenario | Default responsible party |
| --- | --- |
| Wrong item | Merchant |
| Wrong size/model from catalog error | Merchant |
| Damaged before merchant handoff | Merchant |
| Damaged in Sequo custody | Sequo |
| Lost after courier pickup | Courier or Sequo policy |
| Lost during programmed consolidation | Sequo |
| Customer preference change | Configurable policy |
| Suspected fraud | Support/admin decision |

## Refund Execution

Refunds:

- Use original payment provider where possible.
- Otherwise use customer wallet rail allowed by policy.
- Are idempotent by return ID plus refund attempt number.
- Store provider reference, amount, status, request payload hash, and callback result.
- Emit audit and settlement ledger events.

Refund states:

| State | Meaning |
| --- | --- |
| `PENDING` | Refund command created |
| `SENT_TO_PROVIDER` | Provider accepted request |
| `SUCCEEDED` | Provider confirmed refund |
| `FAILED_RETRYABLE` | Retry is allowed |
| `FAILED_FINAL` | Manual intervention required |

## Merchant Payout Lifecycle

| State | Meaning |
| --- | --- |
| `ACCRUED` | Order delivered/received and merchant payable calculated |
| `HELD_RETURN_WINDOW` | Optional hold during 72-hour return window |
| `HELD_DISPUTE` | Support/admin hold due to issue |
| `ELIGIBLE` | Can be included in payout batch |
| `BATCHED` | Included in payout batch |
| `APPROVED` | Finance/admin approved batch |
| `SENT_TO_PROVIDER` | Wallet payout sent |
| `PAID` | Provider confirmed payout |
| `FAILED` | Retry/manual correction needed |
| `ADJUSTED` | Corrected by ledger adjustment |

Payout rule:

- Eligible merchant amounts should be scheduled within 1 week of package receipt.
- Package receipt is delivery confirmed, customer pickup confirmed, or Sequo custody confirmation depending on workflow.
- Active returns and disputes create holds.

## Courier Settlement

Courier payable is based on delivery mission policy and actual completed work.

Record:

- Mission ID.
- Courier ID.
- Estimated courier fee.
- Final courier fee.
- Customer delivery fee collected.
- Delivery shortfall.
- Proof of pickup/drop-off.
- Incident responsibility if any.

Shortfall:

```text
delivery_shortfall = max(0, final_courier_fee - customer_delivery_fee)
```

The shortfall is posted as Sequo expense.

## Point de Relai Settlement

Relay partners can earn:

- Relay handling fee.
- Share of relay pickup/delay penalties if configured.
- Return intake fee if configured.

Relay custody events must be tracked for:

- Merchant deposit.
- Courier deposit.
- Customer pickup.
- Customer return drop-off.
- Sequo collection.
- Lost/damaged parcel report.

Relay pickup restrictions:

- Food and perishable products are not eligible for Point de Relai pickup by default.
- Pickup release requires a valid hashed numeric code or QR payload and an identity validation event when policy requires it.
- Delayed parcels can generate storage-fee ledger entries only after a configured threshold.
- Return-to-seller automation must wait for a product-approved threshold because the owner note still leaves the exact 1-month timing discussable.

## Ledger Requirements

The settlement ledger is append-only.

Entry fields:

- Entry ID.
- Ledger account.
- Debit amount.
- Credit amount.
- Currency.
- Source domain.
- Source ID.
- Actor ID.
- Idempotency key.
- Reason code.
- Created timestamp.

Never update or delete ledger entries to correct money. Add a reversing or adjustment entry.

## Reconciliation

Daily reconciliation should compare:

- Wallet provider payment confirmations.
- Wallet provider refund confirmations.
- Payout provider references.
- Internal settlement ledger.
- Order payment status.
- Return/refund status.

Mismatches create admin alerts.

## Required Tests

- Return request allowed at exactly 72 hours.
- Return request rejected after 72 hours.
- Relay drop-off requires valid return PIN.
- Refund not triggered before physical receipt.
- Duplicate physical receipt event is idempotent.
- Merchant-liable refund reduces merchant payout.
- Sequo-liable refund reduces Sequo revenue.
- Payout generated within configured 1-week window.
- Delivery shortfall posts Sequo expense.
- Ledger correction uses adjustment entry, not mutation.
