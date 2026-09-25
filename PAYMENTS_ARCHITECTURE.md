# Payments Architecture

This document defines the production payment architecture for Sequo API, with a focus on Togo payment rails: Yas Togo, Moov Africa, customer checkout, refunds, logistics settlement, merchant payouts, and reconciliation.

It is aligned with the current Kotlin/Spring Boot repository. The code already contains payment abstractions in `dev.orestegabo.sequo_api.domain.payment`, pending checkout reconciliation in `domain.order`, Flyway migrations for `payment_webhook_events` and `pending_payment_checkouts`, and settlement ledger tables.

## Current Repository Baseline

| Area | Current implementation |
| --- | --- |
| Payment providers | `yas_togo`, `moov_africa` as `PaymentProviderId` values |
| Provider abstraction | `SequoPaymentMethod`, `YasTogoPaymentMethod`, `MoovAfricaPaymentMethod`, `PaymentProcessor` |
| Payment policy | `ConfigurablePaymentPolicy`; cash withdrawal and cash referral payout disabled globally |
| Webhooks | `/api/payments/webhooks/{provider}` with HMAC-SHA256 verification |
| Webhook idempotency | `payment_webhook_events` unique `(provider, event_id)` and payload hash conflict detection |
| Pending checkout | `pending_payment_checkouts`, reconciled after accepted webhook event |
| Settlement | `merchant_payout_accruals` and `settlement_ledger_entries` |
| Runtime config | `sequo.wallets.yas-togo.*` and `sequo.wallets.moov-africa.*` secrets |
| Database | PostgreSQL target, Flyway migrations, integer CFA amounts |

## Architecture Pattern

Payments use the Strategy Design Pattern. Sequo owns the internal domain model, and provider-specific adapters translate to and from external APIs.

```text
Order / Checkout Domain
        |
        v
PaymentService interface
        |
        +-- YaasPaymentServiceImpl
        |
        +-- MoovAfricaPaymentServiceImpl
        |
        v
Provider SDK / HTTPS API
```

The current code names this abstraction `SequoPaymentMethod` and coordinates it through `PaymentProcessor`. That is a good domain-first baseline. As providers become real integrations, evolve toward a Spring service interface:

```kotlin
interface PaymentService {
    val provider: PaymentProviderId

    fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult
    fun validatePayment(request: PaymentValidationRequest): PaymentValidationResult
    fun cancelPayment(request: PaymentCancellationRequest): PaymentActionResult
    fun refundPayment(request: PaymentRefundRequest): PaymentRefundResult
}

@Service
class YaasPaymentServiceImpl(
    private val client: YaasClient,
    private val mapper: YaasPaymentMapper,
) : PaymentService {
    override val provider = SequoPaymentProviders.YasTogo

    override fun createPaymentAttempt(request: PaymentAttemptRequest): PaymentAttemptResult {
        val payload = mapper.toCreatePaymentPayload(request)
        val response = client.createPayment(payload)
        return mapper.toAttemptResult(response)
    }
}
```

Provider payloads must stay outside core domain entities. Use DTOs and mappers:

```text
PaymentAttemptRequest       -> YaasCreatePaymentRequest
PaymentAttemptResult        <- YaasCreatePaymentResponse
PaymentWebhookCommand       <- YaasWebhookPayload
PaymentRefundRequest        -> MoovRefundRequest
```

This avoids leaking provider field names, callback quirks, and error codes into orders, settlement, and refunds.

## Domain Boundaries

| Boundary | Responsibility |
| --- | --- |
| Order processing | Calculates pricing, creates checkout, waits for wallet confirmation |
| Payment processor | Selects provider adapter, enforces payment policy, translates provider result |
| Payment transaction store | Persists internal transaction lifecycle and provider references |
| Webhook intake | Authenticates callbacks, stores deduplicated events, publishes internal event |
| Reconciliation listener | Applies terminal payment event to pending checkout/order |
| Settlement ledger | Posts immutable money movement after successful order/refund/payout |
| Refund workflow | Creates provider refund command after return policy allows it |

## Transaction Lifecycle And State Machine

The requested state vocabulary is the canonical external transaction state:

| State | Meaning |
| --- | --- |
| `PENDING` | Local transaction created, provider command not yet confirmed |
| `PROCESSING` | Provider has accepted request or USSD push is in progress |
| `SUCCESS` | Provider confirmed payment or refund success |
| `FAILED` | Provider or Sequo rejected the transaction |
| `REFUNDED` | Successful full refund posted |
| `EXPIRED` | User did not approve before TTL, or pending checkout exceeded policy window |

Recommended transition rules:

```text
PENDING    -> PROCESSING, FAILED, EXPIRED
PROCESSING -> SUCCESS, FAILED, EXPIRED
SUCCESS    -> REFUNDED
FAILED     -> terminal
REFUNDED   -> terminal
EXPIRED    -> terminal
```

Guard clauses:

- Do not move a terminal transaction back to non-terminal.
- Do not mark success unless provider, reference, amount, currency, and checkout/order match.
- Do not refund more than captured amount.
- Do not expire a transaction that already has a terminal provider confirmation.
- Do not apply a duplicate webhook if its payload hash conflicts with the original event ID.
- Do not create an order from payment unless pending checkout is still awaiting webhook.

Kotlin skeleton:

```kotlin
enum class PaymentTransactionStatus {
    PENDING, PROCESSING, SUCCESS, FAILED, REFUNDED, EXPIRED
}

fun PaymentTransactionStatus.canTransitionTo(next: PaymentTransactionStatus): Boolean =
    when (this) {
        PaymentTransactionStatus.PENDING ->
            next in setOf(
                PaymentTransactionStatus.PROCESSING,
                PaymentTransactionStatus.FAILED,
                PaymentTransactionStatus.EXPIRED,
            )
        PaymentTransactionStatus.PROCESSING ->
            next in setOf(
                PaymentTransactionStatus.SUCCESS,
                PaymentTransactionStatus.FAILED,
                PaymentTransactionStatus.EXPIRED,
            )
        PaymentTransactionStatus.SUCCESS ->
            next == PaymentTransactionStatus.REFUNDED
        PaymentTransactionStatus.FAILED,
        PaymentTransactionStatus.REFUNDED,
        PaymentTransactionStatus.EXPIRED -> false
    }
```

## Transaction Table

Add an internal `payment_transactions` table before integrating real provider commands. It should represent Sequo's payment truth, independent from pending checkout storage and webhook event storage.

```sql
create table payment_transactions (
    id varchar(255) primary key,
    checkout_id varchar(255) not null,
    order_id varchar(255),
    customer_id varchar(255) not null,
    provider varchar(64) not null,
    provider_reference varchar(255),
    provider_payment_reference varchar(255),
    amount_cfa integer not null,
    currency varchar(8) not null default 'XOF',
    status varchar(32) not null,
    feature varchar(64) not null,
    idempotency_key varchar(255) not null,
    request_hash varchar(128) not null,
    last_error_code varchar(128),
    last_error_message varchar(500),
    expires_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint chk_payment_transactions_amount check (amount_cfa >= 0),
    constraint chk_payment_transactions_status check (
        status in ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'REFUNDED', 'EXPIRED')
    ),
    constraint uk_payment_transactions_idempotency unique (provider, idempotency_key)
);

create index idx_payment_transactions_checkout
    on payment_transactions (checkout_id, provider);

create index idx_payment_transactions_provider_reference
    on payment_transactions (provider, provider_reference);

create index idx_payment_transactions_status_updated
    on payment_transactions (status, updated_at);
```

Use optimistic locking through `version` and transactional updates with status guards.

## Checkout Sequence

```text
Client
  -> POST /api/orders/checkout
API
  -> validate customer, cart, route, pricing
  -> create payment transaction with idempotency key
  -> call provider adapter for USSD/push prompt
  -> persist pending_payment_checkouts row
  -> return checkout status PROCESSING

Provider
  -> sends signed webhook
Webhook API
  -> verifies signature and timestamp
  -> stores payment_webhook_events row
  -> publishes PaymentWebhookAcceptedEvent
Listener
  -> locks pending checkout / transaction
  -> validates amount and reference
  -> creates order fulfillment
  -> posts settlement ledger entries
```

## Idempotency And Concurrency Control

Mobile money networks commonly produce ambiguous outcomes:

- The HTTP request to provider times out but the USSD prompt succeeds.
- The user receives multiple approval prompts after retries.
- Provider sends duplicate webhooks.
- Provider sends `PENDING`, then `SUCCESS`, then a duplicate `SUCCESS`.
- Provider sends callbacks late after the checkout appears expired locally.

Sequo must make every money command idempotent.

### `idempotency_keys`

```sql
create table idempotency_keys (
    id varchar(255) primary key,
    scope varchar(64) not null,
    idempotency_key varchar(255) not null,
    request_hash varchar(128) not null,
    response_status integer,
    response_body text,
    locked_until timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    completed_at timestamp with time zone,
    constraint uk_idempotency_keys_scope_key unique (scope, idempotency_key)
);

create index idx_idempotency_keys_locked_until
    on idempotency_keys (locked_until);
```

Usage rules:

- Scope keys by operation: `checkout:create`, `payment:intent`, `refund:create`, `webhook:yas_togo`, `webhook:moov_africa`.
- Hash normalized request JSON.
- Same key and same hash returns the stored response.
- Same key and different hash returns `409 idempotency_key_conflict`.
- Use `select ... for update` or an atomic insert to ensure one in-flight operation wins.
- Store provider references before making follow-up domain changes.

Service skeleton:

```kotlin
@Transactional
fun <T> executeIdempotently(
    scope: String,
    key: String,
    requestHash: String,
    operation: () -> T,
): T {
    val record = repository.findForUpdate(scope, key)
        ?: repository.insertInProgress(scope, key, requestHash)

    require(record.requestHash == requestHash) { "idempotency_key_conflict" }
    record.completedResponse?.let { return deserialize(it) }

    val response = operation()
    repository.markCompleted(record.id, serialize(response))
    return response
}
```

### Webhook Idempotency

The existing `PaymentWebhookService` already follows the correct direction:

- Verify HMAC-SHA256 over timestamp and raw payload.
- Enforce replay window.
- Persist `(provider, event_id)` once.
- Store `payload_hash`.
- Return success for true duplicates.
- Reject same event ID with different payload.

Continue this pattern and add provider-specific event mappings when real Yaas/Moov payloads are available.

## Webhook Processing And Security

Incoming webhooks must be fast, authenticated, and durable.

Headers:

```text
X-Sequo-Webhook-Timestamp: epoch seconds
X-Sequo-Webhook-Signature: sha256=<hex hmac>
```

Current signature base:

```text
<timestamp>.<raw_payload>
```

Verification:

```kotlin
private fun verifySignature(command: PaymentWebhookCommand, secret: String): Boolean {
    val expected = hmacSha256(secret, "${command.signatureTimestamp.epochSecond}.${command.rawPayload}")
    val supplied = command.signature.removePrefix("sha256=").lowercase()
    return supplied.length == expected.length &&
        MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.US_ASCII),
            expected.toByteArray(StandardCharsets.US_ASCII),
        )
}
```

Production rules:

- Use provider-specific secrets from deployment secret manager.
- Keep a 5-minute replay window unless provider docs require otherwise.
- Always verify against the exact raw request body.
- Never log full payloads if they contain phone numbers, wallet references, or customer names.
- Return `202 Accepted` after durable persistence, not after full order fulfillment.

### Asynchronous Processing

The existing code publishes `PaymentWebhookAcceptedEvent` and consumes it through `@TransactionalEventListener(phase = AFTER_COMMIT)`. This is acceptable for an MVP.

Production target:

```text
Webhook Controller
  -> verify signature
  -> insert payment_webhook_events
  -> insert outbox event
  -> return 202

Outbox Worker / Queue Consumer
  -> consume event
  -> lock payment transaction
  -> apply state transition
  -> update pending checkout/order
  -> post ledger/audit
```

Use a message queue or transactional outbox before running multiple API instances.

## Provider Adapter Requirements

### Yaas Togo

Adapter responsibilities:

- Create payment prompt or collection request.
- Pass Sequo idempotency key to provider if supported.
- Store provider transaction reference immediately.
- Map provider pending/success/failure/cancelled statuses to Sequo states.
- Verify webhook HMAC and timestamp.
- Support refund command only after original payment is successful.

### Moov Africa

Adapter responsibilities:

- Create collection request using Moov account/phone reference.
- Handle USSD push timeout as `PROCESSING`, not immediate failure.
- Query provider transaction status after timeout.
- Map duplicate callback deliveries idempotently.
- Support refund or reversal where provider allows it.

## Reconciliation

Daily reconciliation is mandatory because mobile money APIs can be eventually consistent and operationally noisy.

Inputs:

- Provider settlement files or transaction exports from Yaas Togo.
- Provider settlement files or transaction exports from Moov Africa.
- `payment_transactions`.
- `payment_webhook_events`.
- `pending_payment_checkouts`.
- `settlement_ledger_entries`.
- Order and refund tables.

Job pattern:

```kotlin
@Scheduled(cron = "0 30 2 * * *", zone = "Africa/Lome")
fun reconcilePreviousDay() {
    val providerRows = providerClient.fetchSettlementReport(date = yesterday)
    val localRows = paymentTransactionRepository.findByProviderAndDate(provider, yesterday)
    val mismatches = reconciliationService.compare(providerRows, localRows)
    mismatches.forEach { alertAndCreateCase(it) }
}
```

Mismatch categories:

| Mismatch | Action |
| --- | --- |
| Provider success, local pending | Query provider again, then mark success if amount/reference match |
| Provider failed, local processing | Mark failed if no later success exists |
| Local success, missing provider settlement | Create finance alert and hold payout |
| Amount mismatch | Block fulfillment or payout; require admin review |
| Duplicate provider reference | Lock both records and require manual investigation |
| Webhook success without pending checkout | Store as orphan event and alert operations |

## Error Handling

Use stable internal error codes. Provider messages may be stored for operators but should not be exposed directly to customers.

| Condition | Customer response | Internal action |
| --- | --- | --- |
| Provider timeout while creating prompt | `PROCESSING` | Poll provider or await webhook |
| User declines prompt | `FAILED` | Store provider status |
| Provider unavailable | Retryable failure | Circuit breaker and alert |
| Invalid webhook signature | `400` or `401` | Security audit and metric |
| Amount mismatch | Payment blocked | Fraud/ops alert |
| Duplicate webhook | `202` | No state mutation if already applied |
| Late success after expiry | Manual review | Do not auto-fulfill if business window closed |

## Refunds

Refunds must be tied to returns, cancellations, or admin-approved adjustments. For Sequo returns, automated refund starts only after Sequo physically receives the item, as defined in `SETTLEMENTS_AND_RETURNS.md`.

Refund workflow:

```text
Return physically received
  -> refund eligibility verified
  -> idempotent refund command created
  -> provider refund requested
  -> refund transaction PROCESSING
  -> webhook or polling confirms SUCCESS/FAILED
  -> settlement ledger posts refund and merchant/Sequo responsibility
```

Refund rules:

- Prefer refund to original provider.
- Enforce amount <= captured amount minus previous successful refunds.
- Store provider refund reference.
- Do not reduce merchant payout until refund responsibility is determined.
- Use ledger adjustment entries; never mutate historical ledger rows.

Refund table sketch:

```sql
create table payment_refunds (
    id varchar(255) primary key,
    payment_transaction_id varchar(255) not null,
    return_id varchar(255),
    provider varchar(64) not null,
    provider_refund_reference varchar(255),
    amount_cfa integer not null,
    status varchar(32) not null,
    reason varchar(255) not null,
    idempotency_key varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint fk_payment_refunds_transaction foreign key (payment_transaction_id) references payment_transactions (id),
    constraint chk_payment_refunds_amount check (amount_cfa > 0),
    constraint chk_payment_refunds_status check (status in ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'EXPIRED')),
    constraint uk_payment_refunds_provider_key unique (provider, idempotency_key)
);
```

## Partial Payments And Timeout Handling

Partial payments should not fulfill an order by default. If a provider reports a lower amount than expected:

- Mark payment transaction `FAILED` or `PROCESSING_REVIEW` if that state is added later.
- Do not create order fulfillment.
- Create an operations case.
- If money was captured, refund automatically when provider supports it or mark for manual refund.

USSD timeout handling:

- Treat client timeout as unknown, not failed.
- Keep transaction `PROCESSING` until provider webhook, provider status query, or local expiry.
- Poll provider with exponential backoff for a short period.
- After expiry, mark `EXPIRED` only if provider has no success.
- If a late success arrives after expiry, hold for manual review or automatically refund based on policy.

## Settlement And Ledger

Successful customer payments should produce immutable ledger events:

```text
Customer wallet collected        +amount
Merchant payable accrued         -merchant_net
Platform commission recognized   -commission
Delivery payable / shortfall      delivery entries
```

Existing `settlement_ledger_entries` already captures account, direction, amount, source, actor references, and created time. Extend it with idempotency keys and currency when needed.

Recommended extension:

```sql
alter table settlement_ledger_entries
    add column if not exists currency varchar(8) not null default 'XOF',
    add column if not exists idempotency_key varchar(255),
    add column if not exists provider varchar(64),
    add column if not exists provider_reference varchar(255);

create unique index if not exists uk_settlement_ledger_idempotency
    on settlement_ledger_entries (source_type, source_id, idempotency_key)
    where idempotency_key is not null;
```

## Flyway Migration Sketch

```sql
-- Vxx__create_payment_transactions_and_idempotency.sql
create table idempotency_keys (
    id varchar(255) primary key,
    scope varchar(64) not null,
    idempotency_key varchar(255) not null,
    request_hash varchar(128) not null,
    response_status integer,
    response_body text,
    locked_until timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    completed_at timestamp with time zone,
    constraint uk_idempotency_keys_scope_key unique (scope, idempotency_key)
);

create table payment_transactions (
    id varchar(255) primary key,
    checkout_id varchar(255) not null,
    order_id varchar(255),
    customer_id varchar(255) not null,
    provider varchar(64) not null,
    provider_reference varchar(255),
    provider_payment_reference varchar(255),
    amount_cfa integer not null,
    currency varchar(8) not null default 'XOF',
    status varchar(32) not null,
    feature varchar(64) not null,
    idempotency_key varchar(255) not null,
    request_hash varchar(128) not null,
    last_error_code varchar(128),
    last_error_message varchar(500),
    expires_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint chk_payment_transactions_amount check (amount_cfa >= 0),
    constraint chk_payment_transactions_status check (
        status in ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'REFUNDED', 'EXPIRED')
    ),
    constraint uk_payment_transactions_idempotency unique (provider, idempotency_key)
);

create index idx_payment_transactions_checkout on payment_transactions (checkout_id, provider);
create index idx_payment_transactions_provider_reference on payment_transactions (provider, provider_reference);
create index idx_payment_transactions_status_updated on payment_transactions (status, updated_at);
```

## Observability And Alerts

Metrics:

- Payment attempts by provider/status.
- Webhook acceptance/rejection counts.
- Signature failures by provider.
- Duplicate webhook rate.
- Pending transactions older than 5, 15, and 60 minutes.
- Reconciliation mismatches.
- Refund failures.
- Provider latency and timeout rate.

Alerts:

- Invalid signatures spike.
- Provider timeout spike.
- Amount mismatch.
- Local success missing from provider settlement.
- Provider success not fulfilled locally.
- Refund stuck in processing.
- Duplicate provider references.

## Required Tests

- Provider strategy registry rejects duplicate providers.
- Policy blocks disabled features such as cash withdrawal.
- Payment state machine rejects illegal terminal mutations.
- Same idempotency key and same request returns stored response.
- Same idempotency key and different request returns conflict.
- Valid Yaas webhook is accepted.
- Invalid Yaas signature is rejected.
- Duplicate webhook with same payload is idempotent.
- Duplicate webhook with different payload is rejected.
- Pending checkout is fulfilled once after success webhook.
- Failed/cancelled webhook does not create order.
- Amount mismatch does not create order.
- Refund cannot exceed captured amount.
- Reconciliation detects provider success/local pending mismatch.

## Implementation Roadmap

1. Add `payment_transactions` and `idempotency_keys` migrations.
2. Wrap checkout creation and provider commands in idempotency service.
3. Replace placeholder handlers in `YasTogoPaymentMethod` and `MoovAfricaPaymentMethod` with HTTP clients and DTO mappers.
4. Add provider status polling for ambiguous timeouts.
5. Move webhook post-processing to transactional outbox or queue.
6. Add refund persistence and refund webhook handling.
7. Add daily reconciliation jobs and admin mismatch cases.
8. Extend settlement ledger with idempotency and provider reference fields.
9. Add dashboards and alerts before production payment launch.
