alter table merchant_sub_orders
    add column seller_response_due_at timestamp with time zone;

alter table merchant_sub_orders
    add column packing_due_at timestamp with time zone;

create index idx_merchant_sub_orders_response_sla
    on merchant_sub_orders (status, seller_response_due_at);

create index idx_merchant_sub_orders_packing_sla
    on merchant_sub_orders (status, packing_due_at);
