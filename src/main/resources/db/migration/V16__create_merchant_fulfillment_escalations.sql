create table merchant_fulfillment_escalations (
    id varchar(255) not null,
    sub_order_id varchar(255) not null,
    actor_user_id varchar(255) not null,
    reason_code varchar(64) not null,
    note varchar(1000) not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table merchant_fulfillment_escalations
    add constraint fk_merchant_fulfillment_escalations_sub_order
    foreign key (sub_order_id) references merchant_sub_orders (id);

alter table merchant_fulfillment_escalations
    add constraint chk_merchant_fulfillment_escalations_reason
    check (reason_code in ('SELLER_RESPONSE_SLA_EXCEEDED', 'PACKING_SLA_EXCEEDED', 'MANUAL_SUPPORT_REVIEW'));

alter table merchant_fulfillment_escalations
    add constraint uq_merchant_fulfillment_escalations_reason
    unique (sub_order_id, reason_code);

create index idx_merchant_fulfillment_escalations_sub_order
    on merchant_fulfillment_escalations (sub_order_id, created_at);
