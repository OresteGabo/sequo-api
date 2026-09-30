create table order_pricing_snapshots (
    order_id varchar(255) not null,
    checkout_id varchar(255) not null,
    customer_id varchar(255) not null,
    pricing_version varchar(64) not null,
    quote_id varchar(255),
    currency varchar(8) not null,
    service_level varchar(64) not null,
    route varchar(64) not null,
    merchant_ids varchar(2000) not null,
    item_subtotal_cfa integer not null,
    platform_margin_total_cfa integer not null,
    service_fee_total_cfa integer not null,
    delivery_distance_km float(53) not null,
    raw_distance_meters integer,
    distance_source varchar(64),
    delivery_billable_km integer not null,
    delivery_base_fee_cfa integer not null,
    subscription_discount_cfa integer not null,
    referral_credit_applied_cfa integer not null,
    customer_delivery_fee_cfa integer not null,
    courier_fee_estimate_cfa integer,
    delivery_shortfall_estimate_cfa integer,
    bargaining_lock_ids varchar(2000),
    total_cfa integer not null,
    created_at timestamp with time zone not null,
    primary key (order_id)
);

alter table order_pricing_snapshots
    add constraint fk_order_pricing_snapshots_order
    foreign key (order_id) references customer_orders (id);

alter table order_pricing_snapshots
    add constraint chk_order_pricing_snapshots_money_nonnegative
    check (
        item_subtotal_cfa >= 0
        and platform_margin_total_cfa >= 0
        and service_fee_total_cfa >= 0
        and delivery_base_fee_cfa >= 0
        and subscription_discount_cfa >= 0
        and referral_credit_applied_cfa >= 0
        and customer_delivery_fee_cfa >= 0
        and (courier_fee_estimate_cfa is null or courier_fee_estimate_cfa >= 0)
        and (delivery_shortfall_estimate_cfa is null or delivery_shortfall_estimate_cfa >= 0)
        and total_cfa >= 0
    );

alter table order_pricing_snapshots
    add constraint chk_order_pricing_snapshots_distance_nonnegative
    check (
        delivery_distance_km >= 0
        and delivery_billable_km >= 0
        and (raw_distance_meters is null or raw_distance_meters >= 0)
    );

create index idx_order_pricing_snapshots_customer_created
    on order_pricing_snapshots (customer_id, created_at);

create index idx_order_pricing_snapshots_checkout
    on order_pricing_snapshots (checkout_id);
