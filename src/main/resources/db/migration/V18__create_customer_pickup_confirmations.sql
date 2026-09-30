create table customer_pickup_confirmations (
    id varchar(255) not null,
    order_id varchar(255) not null,
    customer_id varchar(255) not null,
    actor_user_id varchar(255) not null,
    idempotency_key varchar(255) not null,
    proof_metadata varchar(1000),
    confirmed_at timestamp with time zone not null,
    primary key (id)
);

alter table customer_pickup_confirmations
    add constraint fk_customer_pickup_confirmations_order
    foreign key (order_id) references customer_orders (id);

alter table customer_pickup_confirmations
    add constraint uq_customer_pickup_confirmations_idempotency
    unique (order_id, idempotency_key);

create index idx_customer_pickup_confirmations_order
    on customer_pickup_confirmations (order_id, confirmed_at);
