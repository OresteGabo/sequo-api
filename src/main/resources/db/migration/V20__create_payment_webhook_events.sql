create table payment_webhook_events (
    id varchar(255) not null,
    provider varchar(64) not null,
    event_id varchar(255) not null,
    checkout_id varchar(255) not null,
    payment_reference varchar(255) not null,
    amount_cfa integer not null,
    payment_status varchar(32) not null,
    occurred_at timestamp with time zone not null,
    payload_hash varchar(128) not null,
    received_at timestamp with time zone not null,
    primary key (id),
    constraint uk_payment_webhook_events_provider_event unique (provider, event_id),
    constraint chk_payment_webhook_events_amount check (amount_cfa >= 0),
    constraint chk_payment_webhook_events_status check (payment_status in ('PENDING', 'VALIDATED', 'FAILED', 'CANCELLED'))
);

create index idx_payment_webhook_events_reference
    on payment_webhook_events (provider, payment_reference, occurred_at);
