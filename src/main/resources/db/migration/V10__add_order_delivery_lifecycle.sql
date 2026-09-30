alter table customer_orders
    add column order_status varchar(64) not null default 'ACCEPTED_FOR_FULFILLMENT';

alter table customer_orders
    add column delivered_at timestamp with time zone;

alter table customer_orders
    add column return_window_ends_at timestamp with time zone;

alter table customer_orders
    add constraint chk_customer_orders_status
    check (order_status in (
        'ACCEPTED_FOR_FULFILLMENT',
        'DELIVERED',
        'CANCELLED',
        'RETURN_REQUESTED',
        'REFUNDED'
    ));

create table order_events (
    id varchar(255) not null,
    order_id varchar(255) not null,
    event_type varchar(64) not null,
    source_type varchar(64) not null,
    source_id varchar(255) not null,
    actor_user_id varchar(255),
    metadata varchar(1000),
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table order_events
    add constraint fk_order_events_order
    foreign key (order_id) references customer_orders (id);

alter table order_events
    add constraint chk_order_events_type
    check (event_type in (
        'ACCEPTED_FOR_FULFILLMENT',
        'DELIVERED',
        'RETURN_WINDOW_OPENED'
    ));

create index idx_order_events_order_created
    on order_events (order_id, created_at);

create index idx_order_events_source
    on order_events (source_type, source_id);
