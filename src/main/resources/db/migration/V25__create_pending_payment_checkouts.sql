alter table payment_webhook_events
    add column provider_reference varchar(255);

create table pending_payment_checkouts (
    checkout_id varchar(255) not null,
    customer_id varchar(255) not null,
    payment_provider varchar(64) not null,
    payment_reference varchar(255) not null,
    amount_cfa integer not null,
    request_payload text not null,
    pricing_payload text not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    provider_reference varchar(255),
    primary key (checkout_id),
    constraint chk_pending_payment_checkouts_amount check (amount_cfa >= 0),
    constraint chk_pending_payment_checkouts_status check (
        status in ('AWAITING_WEBHOOK', 'COMPLETED', 'FAILED', 'CANCELLED')
    )
);

create index idx_pending_payment_checkouts_reference
    on pending_payment_checkouts (payment_provider, payment_reference, status);

create index idx_pending_payment_checkouts_customer_created
    on pending_payment_checkouts (customer_id, created_at);
