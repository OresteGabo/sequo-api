create table customer_orders (
    id varchar(255) not null,
    checkout_id varchar(255) not null,
    customer_id varchar(255) not null,
    service_level varchar(64) not null,
    route varchar(64) not null,
    fulfillment_priority varchar(64) not null,
    requires_consolidation boolean not null,
    customer_facing_status varchar(255) not null,
    item_subtotal_cfa integer not null,
    delivery_fee_cfa integer not null,
    total_cfa integer not null,
    payment_provider varchar(64) not null,
    payment_reference varchar(255) not null,
    provider_reference varchar(255),
    payment_status varchar(64) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table customer_orders
    add constraint uk_customer_orders_checkout unique (checkout_id);

alter table customer_orders
    add constraint chk_customer_orders_money_nonnegative
    check (item_subtotal_cfa >= 0 and delivery_fee_cfa >= 0 and total_cfa >= 0);

create index idx_customer_orders_customer_created
    on customer_orders (customer_id, created_at);

create table customer_order_lines (
    id varchar(255) not null,
    order_id varchar(255) not null,
    product_id varchar(255) not null,
    seller_id varchar(255) not null,
    seller_name varchar(255) not null,
    product_name varchar(255) not null,
    category varchar(64) not null,
    quantity integer not null,
    unit_price_cfa integer not null,
    negotiated_unit_price_cfa integer,
    effective_unit_price_cfa integer not null,
    line_total_cfa integer not null,
    photo_evidence_type varchar(64) not null,
    line_index integer not null,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (id)
);

alter table customer_order_lines
    add constraint fk_customer_order_lines_order
    foreign key (order_id) references customer_orders (id);

alter table customer_order_lines
    add constraint chk_customer_order_lines_positive_amounts
    check (
        quantity > 0
        and unit_price_cfa > 0
        and effective_unit_price_cfa > 0
        and line_total_cfa > 0
    );

alter table customer_order_lines
    add constraint chk_customer_order_lines_line_index
    check (line_index >= 0);

create index idx_customer_order_lines_order
    on customer_order_lines (order_id, line_index);

create index idx_customer_order_lines_seller
    on customer_order_lines (seller_id, order_id);
