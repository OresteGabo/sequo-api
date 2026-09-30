create table merchant_sub_orders (
    id varchar(255) not null,
    sub_order_code varchar(64) not null,
    order_id varchar(255) not null,
    merchant_id varchar(255) not null,
    status varchar(64) not null default 'MERCHANT_PENDING',
    item_subtotal_cfa integer not null default 0,
    commission_rate_bps integer not null default 1500,
    commission_cfa integer not null default 0,
    merchant_net_cfa integer not null default 0,
    package_count integer not null default 0,
    accepted_at timestamp with time zone,
    preparing_at timestamp with time zone,
    packed_ready_at timestamp with time zone,
    handed_to_courier_at timestamp with time zone,
    rejection_reason varchar(500),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table merchant_sub_orders
    add constraint uk_merchant_sub_orders_code unique (sub_order_code);

alter table merchant_sub_orders
    add constraint chk_merchant_sub_orders_status
    check (status in (
        'MERCHANT_PENDING',
        'ACCEPTED',
        'PREPARING',
        'PACKED_READY',
        'HANDED_TO_COURIER',
        'REJECTED',
        'CANCELLED'
    ));

alter table merchant_sub_orders
    add constraint chk_merchant_sub_orders_money_nonnegative
    check (
        item_subtotal_cfa >= 0
        and commission_cfa >= 0
        and merchant_net_cfa >= 0
    );

alter table merchant_sub_orders
    add constraint chk_merchant_sub_orders_commission_rate
    check (commission_rate_bps between 500 and 1500);

alter table merchant_sub_orders
    add constraint chk_merchant_sub_orders_package_count
    check (package_count >= 0);

create index idx_merchant_sub_orders_order_status
    on merchant_sub_orders (order_id, status);

create index idx_merchant_sub_orders_merchant_status
    on merchant_sub_orders (merchant_id, status, created_at);

create table delivery_missions (
    id varchar(255) not null,
    delivery_code varchar(64) not null,
    order_id varchar(255) not null,
    merchant_sub_order_id varchar(255),
    courier_id varchar(255),
    delivery_mode varchar(64) not null,
    destination_type varchar(64) not null,
    status varchar(64) not null default 'CREATED',
    customer_delivery_fee_cfa integer not null default 0,
    courier_fee_cfa integer not null default 0,
    shortfall_cfa integer not null default 0,
    assigned_at timestamp with time zone,
    accepted_at timestamp with time zone,
    pickup_at timestamp with time zone,
    relay_deposited_at timestamp with time zone,
    delivered_at timestamp with time zone,
    pickup_proof_metadata text,
    dropoff_proof_metadata text,
    problem_metadata text,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table delivery_missions
    add constraint uk_delivery_missions_code unique (delivery_code);

alter table delivery_missions
    add constraint fk_delivery_missions_merchant_sub_order
    foreign key (merchant_sub_order_id) references merchant_sub_orders (id);

alter table delivery_missions
    add constraint chk_delivery_missions_mode
    check (delivery_mode in (
        'STANDARD',
        'EXPRESS',
        'PROGRAMMED',
        'CLICK_COLLECT',
        'RELAY'
    ));

alter table delivery_missions
    add constraint chk_delivery_missions_destination
    check (destination_type in (
        'CUSTOMER_ADDRESS',
        'RELAY_POINT',
        'SEQUO_CONSOLIDATION'
    ));

alter table delivery_missions
    add constraint chk_delivery_missions_status
    check (status in (
        'CREATED',
        'OFFERED_TO_COURIER',
        'ACCEPTED_BY_COURIER',
        'PICKED_UP_FROM_SELLER',
        'DEPOSITED_AT_RELAY',
        'DELIVERED_TO_CUSTOMER',
        'RELEASED_BY_RELAY',
        'PROBLEM_REPORTED',
        'CANCELLED'
    ));

alter table delivery_missions
    add constraint chk_delivery_missions_money_nonnegative
    check (
        customer_delivery_fee_cfa >= 0
        and courier_fee_cfa >= 0
        and shortfall_cfa >= 0
    );

create index idx_delivery_missions_order_status
    on delivery_missions (order_id, status);

create index idx_delivery_missions_courier_status
    on delivery_missions (courier_id, status, updated_at);

create index idx_delivery_missions_merchant_sub_order
    on delivery_missions (merchant_sub_order_id);

create table delivery_pins (
    id varchar(255) not null,
    delivery_mission_id varchar(255) not null,
    pin_hash varchar(255) not null,
    expires_at timestamp with time zone not null,
    used_at timestamp with time zone,
    attempt_count integer not null default 0,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (id)
);

alter table delivery_pins
    add constraint fk_delivery_pins_mission
    foreign key (delivery_mission_id) references delivery_missions (id);

alter table delivery_pins
    add constraint chk_delivery_pins_attempt_count
    check (attempt_count >= 0);

create index idx_delivery_pins_mission_used
    on delivery_pins (delivery_mission_id, used_at);

create index idx_delivery_pins_hash
    on delivery_pins (pin_hash);

create table relay_parcels (
    id varchar(255) not null,
    relay_point_id varchar(255) not null,
    locker_id varchar(255),
    order_id varchar(255),
    delivery_mission_id varchar(255),
    return_id varchar(255),
    deposit_code varchar(64),
    status varchar(64) not null default 'CREATED',
    deposited_at timestamp with time zone,
    picked_up_at timestamp with time zone,
    collected_at timestamp with time zone,
    late_fee_started_at timestamp with time zone,
    return_to_seller_due_at timestamp with time zone,
    problem_metadata text,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table relay_parcels
    add constraint uk_relay_parcels_deposit_code unique (deposit_code);

alter table relay_parcels
    add constraint fk_relay_parcels_delivery_mission
    foreign key (delivery_mission_id) references delivery_missions (id);

alter table relay_parcels
    add constraint chk_relay_parcels_status
    check (status in (
        'CREATED',
        'DEPOSITED',
        'PICKED_UP',
        'COLLECTED_BY_SEQUO',
        'DELAYED',
        'RETURN_TO_SELLER_REVIEW',
        'RETURNED_TO_SELLER',
        'PROBLEM'
    ));

create index idx_relay_parcels_relay_status
    on relay_parcels (relay_point_id, status, deposited_at);

create index idx_relay_parcels_order
    on relay_parcels (order_id);

create index idx_relay_parcels_return
    on relay_parcels (return_id);

create table relay_pickup_codes (
    id varchar(255) not null,
    relay_parcel_id varchar(255) not null,
    code_hash varchar(255) not null,
    qr_nonce_hash varchar(255),
    identity_check_required boolean not null default true,
    expires_at timestamp with time zone not null,
    used_at timestamp with time zone,
    attempt_count integer not null default 0,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (id)
);

alter table relay_pickup_codes
    add constraint fk_relay_pickup_codes_parcel
    foreign key (relay_parcel_id) references relay_parcels (id);

alter table relay_pickup_codes
    add constraint uk_relay_pickup_codes_qr_nonce_hash unique (qr_nonce_hash);

alter table relay_pickup_codes
    add constraint chk_relay_pickup_codes_attempt_count
    check (attempt_count >= 0);

create index idx_relay_pickup_codes_parcel_used
    on relay_pickup_codes (relay_parcel_id, used_at);

create index idx_relay_pickup_codes_hash
    on relay_pickup_codes (code_hash);

create table relay_custody_events (
    id varchar(255) not null,
    relay_parcel_id varchar(255) not null,
    actor_user_id varchar(255),
    event_type varchar(64) not null,
    metadata text,
    idempotency_key varchar(255),
    created_at timestamp with time zone not null default current_timestamp,
    primary key (id)
);

alter table relay_custody_events
    add constraint fk_relay_custody_events_parcel
    foreign key (relay_parcel_id) references relay_parcels (id);

alter table relay_custody_events
    add constraint fk_relay_custody_events_actor
    foreign key (actor_user_id) references users (id);

alter table relay_custody_events
    add constraint chk_relay_custody_events_type
    check (event_type in (
        'DEPOSIT',
        'PICKUP',
        'RELAY_RELEASE',
        'SEQUO_COLLECTION',
        'RETURN_DROPOFF',
        'PROBLEM'
    ));

create index idx_relay_custody_events_parcel_created
    on relay_custody_events (relay_parcel_id, created_at);

create index idx_relay_custody_events_actor_created
    on relay_custody_events (actor_user_id, created_at);

create index idx_relay_custody_events_idempotency
    on relay_custody_events (idempotency_key);
