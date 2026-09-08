create table return_requests (
    id varchar(255) not null,
    order_id varchar(255) not null,
    customer_id varchar(255) not null,
    status varchar(64) not null,
    reason varchar(1000) not null,
    requested_refund_cfa integer not null,
    return_pin_hash varchar(255) not null,
    relay_point_id varchar(255),
    dropped_at timestamp with time zone,
    received_by_sequo_at timestamp with time zone,
    receiving_operator_id varchar(255),
    condition_assessment varchar(1000),
    responsibility varchar(64),
    receipt_idempotency_key varchar(255),
    refund_idempotency_key varchar(255),
    refund_amount_cfa integer,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    primary key (id)
);

alter table return_requests
    add constraint fk_return_requests_order
    foreign key (order_id) references customer_orders (id);

alter table return_requests
    add constraint chk_return_requests_status
    check (status in (
        'Requested',
        'AwaitingRelayDropoff',
        'DroppedAtRelay',
        'ReceivedBySequo',
        'RefundPending',
        'Rejected'
    ));

alter table return_requests
    add constraint chk_return_requests_refund_positive
    check (requested_refund_cfa > 0 and (refund_amount_cfa is null or refund_amount_cfa > 0));

create index idx_return_requests_order
    on return_requests (order_id);

create index idx_return_requests_customer_created
    on return_requests (customer_id, created_at);

create index idx_return_requests_status_updated
    on return_requests (status, updated_at);

create index idx_return_requests_receipt_idempotency
    on return_requests (receipt_idempotency_key);

create index idx_return_requests_refund_idempotency
    on return_requests (refund_idempotency_key);
