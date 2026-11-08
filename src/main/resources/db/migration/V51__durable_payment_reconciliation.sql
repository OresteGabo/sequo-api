-- Existing receipts predate this reconciliation policy and must not be replayed automatically.
alter table payment_webhook_events
    add column reconciliation_status varchar(32) not null default 'LEGACY_REVIEW_REQUIRED',
    add column reconciliation_reason varchar(96),
    add column reconciled_at timestamp with time zone;

alter table payment_webhook_events add constraint chk_payment_webhook_reconciliation_status check (
    reconciliation_status in ('PENDING', 'FULFILLED', 'IGNORED', 'REVIEW_REQUIRED', 'ERROR', 'LEGACY_REVIEW_REQUIRED')
);
create index idx_payment_webhook_checkout_received on payment_webhook_events (checkout_id, received_at desc);

create table payment_transaction_claims (
    id varchar(64) not null primary key,
    provider varchar(64) not null,
    provider_reference varchar(255) not null,
    checkout_id varchar(255) not null,
    first_event_id varchar(255) not null,
    claimed_at timestamp with time zone not null,
    constraint uk_payment_transaction_claim_provider_reference unique (provider, provider_reference),
    constraint fk_payment_transaction_claim_checkout foreign key (checkout_id) references pending_payment_checkouts(checkout_id)
);
