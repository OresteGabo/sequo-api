
-- ============================================================================
-- Squashed from V1__create_auth_users.sql
-- ============================================================================

create table users (
    id varchar(255) not null,
    email varchar(255) not null,
    password_hash varchar(255),
    name varchar(255),
    provider varchar(255) not null,
    status varchar(255) not null default 'ACTIVE',
    provider_id varchar(255),
    reset_token_hash varchar(255),
    reset_token_expiry timestamp with time zone,
    primary key (id)
);

alter table users
    add constraint uk_users_email unique (email);

alter table users
    add constraint uk_users_provider_id unique (provider_id);

create index idx_users_email on users (email);

create index idx_users_provider_provider_id on users (provider, provider_id);

create index idx_users_reset_token_hash on users (reset_token_hash);


-- ============================================================================
-- Squashed from V2__create_delivery_fulfillment_tables.sql
-- ============================================================================

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


-- ============================================================================
-- Squashed from V3__create_notification_tables.sql
-- ============================================================================

create table device_fcm_tokens (
    id varchar(255) not null,
    user_id varchar(255) not null,
    device_id varchar(255) not null,
    app_family varchar(64) not null,
    platform varchar(32) not null,
    fcm_token_hash varchar(128) not null,
    fcm_token_ciphertext varchar(2000) not null,
    app_version varchar(64),
    locale varchar(32),
    timezone varchar(128),
    status varchar(32) not null default 'ACTIVE',
    last_seen_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table device_fcm_tokens
    add constraint fk_device_fcm_tokens_user
    foreign key (user_id) references users (id);

alter table device_fcm_tokens
    add constraint uk_device_fcm_tokens_hash unique (fcm_token_hash);

alter table device_fcm_tokens
    add constraint chk_device_fcm_tokens_app_family
    check (app_family in (
        'SEQUO_CUSTOMER',
        'SEQUO_MERCHANT',
        'SEQUO_HUB',
        'SEQUO_RIDER',
        'SEQUO_ADMIN'
    ));

alter table device_fcm_tokens
    add constraint chk_device_fcm_tokens_platform
    check (platform in ('ANDROID', 'IOS', 'WEB'));

alter table device_fcm_tokens
    add constraint chk_device_fcm_tokens_status
    check (status in ('ACTIVE', 'REVOKED', 'STALE', 'FAILED'));

create index idx_device_fcm_tokens_user_app_status
    on device_fcm_tokens (user_id, app_family, status);

create index idx_device_fcm_tokens_user_device_app_status
    on device_fcm_tokens (user_id, device_id, app_family, status);

create table notification_preferences (
    id varchar(255) not null,
    user_id varchar(255) not null,
    app_family varchar(64) not null,
    event_type varchar(128) not null default 'ALL',
    push_enabled boolean not null default true,
    in_app_enabled boolean not null default true,
    sms_enabled boolean not null default true,
    quiet_hours_start time,
    quiet_hours_end time,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table notification_preferences
    add constraint fk_notification_preferences_user
    foreign key (user_id) references users (id);

alter table notification_preferences
    add constraint uk_notification_preferences_user_app_event unique (user_id, app_family, event_type);

alter table notification_preferences
    add constraint chk_notification_preferences_app_family
    check (app_family in (
        'SEQUO_CUSTOMER',
        'SEQUO_MERCHANT',
        'SEQUO_HUB',
        'SEQUO_RIDER',
        'SEQUO_ADMIN'
    ));

create table notification_messages (
    id varchar(255) not null,
    event_id varchar(255) not null,
    recipient_user_id varchar(255) not null,
    app_family varchar(64) not null,
    event_type varchar(128) not null,
    severity varchar(64) not null,
    title varchar(255) not null,
    body varchar(1000) not null,
    action_url varchar(500),
    payload varchar(4000),
    read_at timestamp with time zone,
    archived_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (id)
);

alter table notification_messages
    add constraint fk_notification_messages_recipient
    foreign key (recipient_user_id) references users (id);

alter table notification_messages
    add constraint uk_notification_messages_event_recipient unique (event_id, recipient_user_id, app_family, event_type);

alter table notification_messages
    add constraint chk_notification_messages_app_family
    check (app_family in (
        'SEQUO_CUSTOMER',
        'SEQUO_MERCHANT',
        'SEQUO_HUB',
        'SEQUO_RIDER',
        'SEQUO_ADMIN'
    ));

alter table notification_messages
    add constraint chk_notification_messages_severity
    check (severity in ('INFO', 'ACTION_REQUIRED', 'URGENT', 'SECURITY', 'FINANCIAL'));

create index idx_notification_messages_recipient_created
    on notification_messages (recipient_user_id, created_at);

create table notification_deliveries (
    id varchar(255) not null,
    message_id varchar(255) not null,
    channel varchar(64) not null,
    target_ref varchar(255) not null,
    status varchar(64) not null default 'PENDING',
    provider_reference varchar(255),
    failure_code varchar(128),
    failure_message varchar(500),
    attempt_count integer not null default 0,
    next_attempt_at timestamp with time zone,
    sent_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table notification_deliveries
    add constraint fk_notification_deliveries_message
    foreign key (message_id) references notification_messages (id);

alter table notification_deliveries
    add constraint chk_notification_deliveries_channel
    check (channel in ('IN_APP', 'FCM', 'WEBSOCKET', 'SMS', 'EMAIL', 'WHATSAPP'));

alter table notification_deliveries
    add constraint chk_notification_deliveries_status
    check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED_RETRYABLE', 'FAILED_FINAL', 'SUPPRESSED'));

alter table notification_deliveries
    add constraint chk_notification_deliveries_attempt_count
    check (attempt_count >= 0);

create index idx_notification_deliveries_message_channel_status
    on notification_deliveries (message_id, channel, status);

create table notification_outbox (
    id varchar(255) not null,
    event_id varchar(255) not null,
    event_type varchar(128) not null,
    aggregate_type varchar(128) not null,
    aggregate_id varchar(255) not null,
    payload varchar(4000),
    status varchar(64) not null default 'PENDING',
    attempt_count integer not null default 0,
    next_attempt_at timestamp with time zone,
    locked_by varchar(128),
    locked_until timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    processed_at timestamp with time zone,
    version bigint not null default 0,
    primary key (id)
);

alter table notification_outbox
    add constraint uk_notification_outbox_event unique (event_id);

alter table notification_outbox
    add constraint chk_notification_outbox_status
    check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED_RETRYABLE', 'FAILED_FINAL'));

alter table notification_outbox
    add constraint chk_notification_outbox_attempt_count
    check (attempt_count >= 0);

create index idx_notification_outbox_status_next_attempt
    on notification_outbox (status, next_attempt_at);


-- ============================================================================
-- Squashed from V4__create_social_identity_table.sql
-- ============================================================================

create table social_identities (
    id varchar(255) not null,
    user_id varchar(255) not null,
    provider varchar(32) not null,
    provider_subject varchar(255) not null,
    verified_email varchar(255),
    created_at timestamp with time zone not null,
    last_login_at timestamp with time zone,
    primary key (id),
    constraint fk_social_identities_user foreign key (user_id) references users (id),
    constraint uk_social_identity_provider_subject unique (provider, provider_subject),
    constraint chk_social_identity_provider check (provider <> 'EMAIL')
);

create index idx_social_identities_user on social_identities (user_id);


-- ============================================================================
-- Squashed from V5__add_merchant_fulfillment_sla.sql
-- ============================================================================

alter table merchant_sub_orders
    add column seller_response_due_at timestamp with time zone;

alter table merchant_sub_orders
    add column packing_due_at timestamp with time zone;

create index idx_merchant_sub_orders_response_sla
    on merchant_sub_orders (status, seller_response_due_at);

create index idx_merchant_sub_orders_packing_sla
    on merchant_sub_orders (status, packing_due_at);


-- ============================================================================
-- Squashed from V6__add_delivery_proof_actors.sql
-- ============================================================================

alter table delivery_missions
    add column pickup_proof_actor_id varchar(255);

alter table delivery_missions
    add column dropoff_proof_actor_id varchar(255);

alter table delivery_missions
    add column relay_deposit_proof_metadata text;

alter table delivery_missions
    add column relay_deposit_proof_actor_id varchar(255);


-- ============================================================================
-- Squashed from V7__add_relay_parcel_category.sql
-- ============================================================================

alter table relay_parcels
    add column category varchar(64) not null default 'GeneralGoods';


-- ============================================================================
-- Squashed from V8__create_settlement_tables.sql
-- ============================================================================

create table merchant_payout_accruals (
    id varchar(255) not null,
    merchant_id varchar(255) not null,
    order_id varchar(255) not null,
    source_order_item_id varchar(255) not null,
    merchant_net_cfa integer not null,
    commission_cfa integer not null,
    platform_margin_cfa integer not null,
    package_received_at timestamp with time zone not null,
    payout_eligible_at timestamp with time zone not null,
    payout_due_by timestamp with time zone not null,
    status varchar(64) not null,
    workflow_type varchar(64) not null,
    active_return_hold boolean not null default false,
    active_dispute_hold boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table merchant_payout_accruals
    add constraint chk_payout_amounts_nonnegative
    check (merchant_net_cfa >= 0 and commission_cfa >= 0 and platform_margin_cfa >= 0);

create index idx_payouts_merchant_status
    on merchant_payout_accruals (merchant_id, status, payout_eligible_at);

create index idx_payouts_due_status
    on merchant_payout_accruals (status, payout_eligible_at, payout_due_by);

create table settlement_ledger_entries (
    id varchar(255) not null,
    account varchar(64) not null,
    direction varchar(32) not null,
    amount_cfa integer not null,
    merchant_id varchar(255),
    courier_id varchar(255),
    relay_point_id varchar(255),
    source_type varchar(64) not null,
    source_id varchar(255) not null,
    description varchar(500) not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table settlement_ledger_entries
    add constraint chk_settlement_amount_positive check (amount_cfa > 0);

create index idx_settlement_source
    on settlement_ledger_entries (source_type, source_id, created_at);

create index idx_settlement_merchant
    on settlement_ledger_entries (merchant_id, created_at);


-- ============================================================================
-- Squashed from V9__create_order_fulfillment_tables.sql
-- ============================================================================

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


-- ============================================================================
-- Squashed from V10__add_order_delivery_lifecycle.sql
-- ============================================================================

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


-- ============================================================================
-- Squashed from V11__create_return_request_tables.sql
-- ============================================================================

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


-- ============================================================================
-- Squashed from V12__create_relay_storage_fee_assessments.sql
-- ============================================================================

create table relay_storage_fee_assessments (
    id varchar(255) not null,
    relay_parcel_id varchar(255) not null,
    relay_point_id varchar(255) not null,
    daily_fee_cfa integer not null,
    chargeable_days bigint not null,
    total_fee_cfa integer not null,
    last_increment_cfa integer not null,
    fee_starts_at timestamp with time zone not null,
    measured_until timestamp with time zone not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    primary key (id)
);

alter table relay_storage_fee_assessments
    add constraint fk_relay_storage_fee_assessments_parcel
    foreign key (relay_parcel_id) references relay_parcels (id);

alter table relay_storage_fee_assessments
    add constraint uk_relay_storage_fee_assessments_parcel unique (relay_parcel_id);

alter table relay_storage_fee_assessments
    add constraint chk_relay_storage_fee_assessments_nonnegative
    check (
        daily_fee_cfa >= 0
        and chargeable_days >= 0
        and total_fee_cfa >= 0
        and last_increment_cfa >= 0
    );

create index idx_relay_storage_fee_assessments_relay
    on relay_storage_fee_assessments (relay_point_id, updated_at);


-- ============================================================================
-- Squashed from V13__create_delivery_mission_idempotency_keys.sql
-- ============================================================================

create table delivery_mission_idempotency_keys (
    id varchar(255) not null,
    idempotency_key varchar(128) not null,
    mission_id varchar(255) not null,
    actor_user_id varchar(255) not null,
    event_type varchar(64) not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table delivery_mission_idempotency_keys
    add constraint fk_delivery_mission_idempotency_keys_mission
    foreign key (mission_id) references delivery_missions (id);

alter table delivery_mission_idempotency_keys
    add constraint uk_delivery_mission_idempotency_keys_key unique (idempotency_key);

alter table delivery_mission_idempotency_keys
    add constraint chk_delivery_mission_idempotency_keys_event
    check (event_type in (
        'OfferToCourier',
        'CourierAccepts',
        'CourierPicksUpFromSeller',
        'CourierDeliversToCustomer',
        'CourierDepositsAtRelay',
        'RelayReleasesToCustomer',
        'ReportProblem',
        'Cancel'
    ));

create index idx_delivery_mission_idempotency_keys_mission
    on delivery_mission_idempotency_keys (mission_id, created_at);


-- ============================================================================
-- Squashed from V14__create_delivery_problem_resolutions.sql
-- ============================================================================

create table delivery_problem_resolutions (
    id varchar(255) not null,
    mission_id varchar(255) not null,
    actor_user_id varchar(255) not null,
    action varchar(64) not null,
    replacement_courier_id varchar(255),
    reason varchar(1000) not null,
    resolved_at timestamp with time zone not null,
    primary key (id)
);

alter table delivery_problem_resolutions
    add constraint fk_delivery_problem_resolutions_mission
    foreign key (mission_id) references delivery_missions (id);

alter table delivery_problem_resolutions
    add constraint chk_delivery_problem_resolutions_action
    check (action in ('REQUEUE_FOR_DISPATCH', 'CANCEL_MISSION'));

create index idx_delivery_problem_resolutions_mission
    on delivery_problem_resolutions (mission_id, resolved_at);


-- ============================================================================
-- Squashed from V15__create_courier_availability_states.sql
-- ============================================================================

create table courier_availability_states (
    courier_id varchar(255) not null,
    status varchar(32) not null,
    paused_reason varchar(1000),
    paused_by varchar(255),
    paused_at timestamp with time zone,
    paused_until timestamp with time zone,
    updated_at timestamp with time zone not null,
    primary key (courier_id)
);

alter table courier_availability_states
    add constraint chk_courier_availability_status
    check (status in ('ACTIVE', 'PAUSED'));

create index idx_courier_availability_status
    on courier_availability_states (status, paused_until);


-- ============================================================================
-- Squashed from V16__create_merchant_fulfillment_escalations.sql
-- ============================================================================

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


-- ============================================================================
-- Squashed from V17__create_merchant_commission_overrides.sql
-- ============================================================================

create table merchant_commission_overrides (
    merchant_id varchar(255) not null,
    commission_rate_bps integer not null,
    reason varchar(500),
    updated_by_user_id varchar(255) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (merchant_id)
);

alter table merchant_commission_overrides
    add constraint chk_merchant_commission_overrides_rate
    check (commission_rate_bps between 500 and 1500);


-- ============================================================================
-- Squashed from V18__create_customer_pickup_confirmations.sql
-- ============================================================================

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


-- ============================================================================
-- Squashed from V19__create_delivery_dispatch_runs.sql
-- ============================================================================

create table delivery_dispatch_runs (
    id varchar(255) not null,
    scanned_ready_sub_orders integer not null,
    created_mission_ids varchar(4000) not null,
    existing_mission_ids varchar(4000) not null,
    skipped_sub_order_ids varchar(4000) not null,
    skipped_reasons varchar(4000) not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table delivery_dispatch_runs
    add constraint chk_delivery_dispatch_runs_counts
    check (scanned_ready_sub_orders >= 0);

create index idx_delivery_dispatch_runs_created
    on delivery_dispatch_runs (created_at);


-- ============================================================================
-- Squashed from V20__create_payment_webhook_events.sql
-- ============================================================================

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


-- ============================================================================
-- Squashed from V21__create_consolidation_manifests.sql
-- ============================================================================

create table consolidation_manifests (
    id varchar(255) not null,
    order_id varchar(255) not null,
    customer_id varchar(255) not null,
    seller_packages_json varchar(12000) not null,
    status varchar(64) not null,
    final_package_id varchar(255),
    sequo_custody_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    primary key (id),
    constraint uk_consolidation_manifests_order unique (order_id),
    constraint chk_consolidation_manifests_status check (status in ('AwaitingSellerPackages', 'ReadyForSequoPickup', 'InSequoCustody', 'Consolidated', 'Dispatched', 'Cancelled'))
);

create index idx_consolidation_manifests_status_updated
    on consolidation_manifests (status, updated_at);


-- ============================================================================
-- Squashed from V22__create_refresh_sessions.sql
-- ============================================================================

create table refresh_sessions (
    id varchar(255) not null,
    user_id varchar(255) not null,
    token_hash varchar(64) not null,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    last_used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    primary key (id),
    constraint uk_refresh_sessions_token_hash unique (token_hash)
);

create index idx_refresh_sessions_user_active on refresh_sessions (user_id, revoked_at);
create index idx_refresh_sessions_expires_at on refresh_sessions (expires_at);


-- ============================================================================
-- Squashed from V23__create_user_roles_and_merchant_memberships.sql
-- ============================================================================

create table user_roles (
    user_id varchar(255) not null,
    role_code varchar(255) not null,
    primary key (user_id, role_code)
);

alter table user_roles
    add constraint fk_user_roles_user
    foreign key (user_id) references users (id);

alter table user_roles
    add constraint chk_user_roles_role_code
    check (role_code in (
        'CUSTOMER',
        'MERCHANT_OWNER',
        'MERCHANT_STAFF',
        'COURIER',
        'RELAY_PARTNER',
        'SUPPORT_AGENT',
        'ADMIN',
        'SUPER_ADMIN'
    ));

insert into user_roles (user_id, role_code)
select id, 'CUSTOMER'
from users;

create table merchant_memberships (
    id varchar(255) not null,
    user_id varchar(255) not null,
    merchant_id varchar(255) not null,
    role_code varchar(255) not null,
    active boolean not null default true,
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table merchant_memberships
    add constraint fk_merchant_memberships_user
    foreign key (user_id) references users (id);

alter table merchant_memberships
    add constraint uk_merchant_memberships_user_merchant_role
    unique (user_id, merchant_id, role_code);

alter table merchant_memberships
    add constraint chk_merchant_memberships_role_code
    check (role_code in ('MERCHANT_OWNER', 'MERCHANT_STAFF'));

create index idx_merchant_memberships_user_active
    on merchant_memberships (user_id, active);

create index idx_merchant_memberships_merchant_active
    on merchant_memberships (merchant_id, active);


-- ============================================================================
-- Squashed from V24__create_order_pricing_snapshots.sql
-- ============================================================================

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


-- ============================================================================
-- Squashed from V25__create_pending_payment_checkouts.sql
-- ============================================================================

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


-- ============================================================================
-- Squashed from V26__create_hub_mobile_tables.sql
-- ============================================================================

create table relay_lockers (
    id varchar(255) not null,
    relay_point_id varchar(255) not null,
    locker_code varchar(64) not null,
    status varchar(64) not null default 'AVAILABLE',
    availability_reason varchar(64),
    expected_available_at timestamp with time zone,
    updated_by_user_id varchar(255),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table relay_lockers
    add constraint uk_relay_lockers_relay_code unique (relay_point_id, locker_code);

alter table relay_lockers
    add constraint chk_relay_lockers_status
    check (status in ('AVAILABLE', 'OCCUPIED', 'MAINTENANCE'));

alter table relay_lockers
    add constraint chk_relay_lockers_reason
    check (
        availability_reason is null or availability_reason in (
            'BROKEN_DOOR',
            'JAMMED_LOCK',
            'DIRTY',
            'WRONG_CONTENTS',
            'OTHER',
            'OPERATOR_CONFIRMED_AVAILABLE',
            'SYSTEM_OCCUPIED'
        )
    );

create index idx_relay_lockers_relay_status
    on relay_lockers (relay_point_id, status);

create table hub_opening_hours (
    id varchar(255) not null,
    relay_point_id varchar(255) not null,
    day_of_week varchar(16) not null,
    timezone varchar(128) not null,
    is_open boolean not null,
    opens_at time,
    closes_at time,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table hub_opening_hours
    add constraint uk_hub_opening_hours_relay_day unique (relay_point_id, day_of_week);

alter table hub_opening_hours
    add constraint chk_hub_opening_hours_day
    check (day_of_week in ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'));

alter table hub_opening_hours
    add constraint chk_hub_opening_hours_times
    check (
        (is_open = false and opens_at is null and closes_at is null)
        or (is_open = true and opens_at is not null and closes_at is not null and opens_at < closes_at)
    );

create index idx_hub_opening_hours_relay
    on hub_opening_hours (relay_point_id);

create table hub_opening_hour_exceptions (
    id varchar(255) not null,
    relay_point_id varchar(255) not null,
    exception_date date not null,
    is_closed boolean not null,
    opens_at time,
    closes_at time,
    reason varchar(255),
    effective_until date,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table hub_opening_hour_exceptions
    add constraint uk_hub_opening_hour_exceptions_relay_date unique (relay_point_id, exception_date);

alter table hub_opening_hour_exceptions
    add constraint chk_hub_opening_hour_exceptions_times
    check (
        (is_closed = true and opens_at is null and closes_at is null)
        or (is_closed = false and opens_at is not null and closes_at is not null and opens_at < closes_at)
    );

create index idx_hub_opening_hour_exceptions_relay_date
    on hub_opening_hour_exceptions (relay_point_id, exception_date);

create table account_deletion_requests (
    id varchar(255) not null,
    user_id varchar(255) not null,
    reason varchar(500),
    confirmation varchar(128) not null,
    status varchar(64) not null default 'REQUESTED',
    requested_at timestamp with time zone not null default current_timestamp,
    reviewed_at timestamp with time zone,
    version bigint not null default 0,
    primary key (id)
);

alter table account_deletion_requests
    add constraint fk_account_deletion_requests_user
    foreign key (user_id) references users (id);

alter table account_deletion_requests
    add constraint chk_account_deletion_requests_status
    check (status in ('REQUESTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED', 'COMPLETED'));

create index idx_account_deletion_requests_user_status
    on account_deletion_requests (user_id, status, requested_at);

create table hub_control_decisions (
    id varchar(255) not null,
    relay_point_id varchar(255) not null,
    target varchar(64) not null,
    status varchar(64) not null,
    reason_code varchar(64) not null,
    staff_message varchar(500) not null,
    customer_message varchar(500),
    effective_until timestamp with time zone,
    actor_type varchar(64) not null,
    actor_id varchar(255) not null,
    source varchar(128) not null,
    incident_reference_id varchar(255),
    idempotency_key varchar(128) not null,
    created_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table hub_control_decisions
    add constraint uk_hub_control_decisions_relay_idempotency unique (relay_point_id, idempotency_key);

alter table hub_control_decisions
    add constraint chk_hub_control_decisions_target
    check (target in (
        'HUB',
        'LOCKER_INTAKE',
        'CUSTOMER_PICKUP',
        'CUSTOMER_RETURNS',
        'SEQUO_COLLECTION',
        'PLAN_B_DROP_OFF'
    ));

alter table hub_control_decisions
    add constraint chk_hub_control_decisions_status
    check (status in ('ACTIVE', 'PAUSED', 'DISABLED'));

alter table hub_control_decisions
    add constraint chk_hub_control_decisions_actor_type
    check (actor_type in ('SEQUO_OPERATOR', 'SEQUO_AI', 'SYSTEM_POLICY'));

alter table hub_control_decisions
    add constraint chk_hub_control_decisions_reason_code
    check (reason_code in (
        'RISK_REVIEW',
        'PARTNER_SUSPENSION',
        'CAPACITY_LOCK',
        'FRAUD_SIGNAL',
        'MAINTENANCE',
        'COMPLIANCE_REVIEW',
        'EMERGENCY',
        'OTHER'
    ));

create index idx_hub_control_decisions_relay_target_created
    on hub_control_decisions (relay_point_id, target, created_at);

create index idx_hub_control_decisions_relay_created
    on hub_control_decisions (relay_point_id, created_at);

create table user_app_preferences (
    id varchar(255) not null,
    user_id varchar(255) not null,
    app_family varchar(64) not null,
    theme varchar(64) not null default 'SYSTEM',
    language varchar(16) not null default 'fr',
    quick_scan_on_open boolean not null default false,
    sound_feedback boolean not null default true,
    large_locker_labels boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table user_app_preferences
    add constraint fk_user_app_preferences_user
    foreign key (user_id) references users (id);

alter table user_app_preferences
    add constraint uk_user_app_preferences_user_app unique (user_id, app_family);

alter table user_app_preferences
    add constraint chk_user_app_preferences_app_family
    check (app_family in (
        'SEQUO_CUSTOMER',
        'SEQUO_MERCHANT',
        'SEQUO_HUB',
        'SEQUO_RIDER',
        'SEQUO_ADMIN'
    ));

alter table user_app_preferences
    add constraint chk_user_app_preferences_theme
    check (theme in ('SYSTEM', 'LIGHT', 'DARK'));

create index idx_user_app_preferences_user
    on user_app_preferences (user_id);


-- ============================================================================
-- Squashed from V27__add_relay_locker_grid_id.sql
-- ============================================================================

alter table relay_lockers
    add column relay_locker_grid_id varchar(255);

update relay_lockers
set relay_locker_grid_id = relay_point_id
where relay_locker_grid_id is null;

alter table relay_lockers
    alter column relay_locker_grid_id set not null;

create index idx_relay_lockers_grid
    on relay_lockers (relay_locker_grid_id);


-- ============================================================================
-- Squashed from V28__add_product_related_lookup_index.sql
-- ============================================================================

create table if not exists products (
    id varchar(255) not null,
    merchant_id varchar(255),
    name varchar(255) not null,
    kind varchar(64) not null,
    status varchar(64) not null,
    category varchar(128),
    base_price_cfa integer,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table products
    add constraint chk_products_kind
    check (kind in (
        'GenericSealedItem',
        'SellerSpecific',
        'PreparedFood'
    ));

alter table products
    add constraint chk_products_status
    check (status in (
        'ACTIVE',
        'INACTIVE',
        'ARCHIVED'
    ));

alter table products
    add constraint chk_products_price_nonnegative
    check (base_price_cfa is null or base_price_cfa >= 0);

create index if not exists idx_products_active_category_created_at
    on products (category, status, created_at desc);

create index if not exists idx_products_merchant_status
    on products (merchant_id, status, created_at desc);

create table if not exists catalog_images (
    id varchar(255) not null,
    product_id varchar(255) not null,
    storage_key varchar(255) not null,
    public_url varchar(255) not null,
    source varchar(64) not null,
    moderation_status varchar(64) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table catalog_images
    add constraint fk_catalog_images_product
    foreign key (product_id) references products (id);

alter table catalog_images
    add constraint chk_catalog_images_source
    check (source in (
        'SellerLiveCapture',
        'SupplierReference',
        'SequoManaged'
    ));

alter table catalog_images
    add constraint chk_catalog_images_moderation_status
    check (moderation_status in (
        'NeedsReview',
        'Approved',
        'Rejected'
    ));

create index if not exists idx_catalog_images_product_status
    on catalog_images (product_id, moderation_status, created_at desc);


-- ============================================================================
-- Squashed from V29__create_party_master_tables.sql
-- ============================================================================

create table if not exists merchants (
    id varchar(255) not null,
    owner_user_id varchar(255),
    name varchar(255) not null,
    status varchar(64) not null,
    commission_rate_bps integer not null default 1500,
    wallet_provider varchar(64),
    wallet_account_ref varchar(255),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table merchants
    add constraint fk_merchants_owner_user
    foreign key (owner_user_id) references users (id);

alter table merchants
    add constraint chk_merchants_commission_rate
    check (commission_rate_bps between 0 and 10000);

create index if not exists idx_merchants_owner_user
    on merchants (owner_user_id);

create index if not exists idx_merchants_status
    on merchants (status, created_at);

create table if not exists couriers (
    id varchar(255) not null,
    user_id varchar(255),
    status varchar(64) not null,
    workforce_type varchar(64),
    vehicle_type varchar(64),
    wallet_provider varchar(64),
    wallet_account_ref varchar(255),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table couriers
    add constraint fk_couriers_user
    foreign key (user_id) references users (id);

alter table couriers
    add constraint uk_couriers_user_id
    unique (user_id);

create index if not exists idx_couriers_status
    on couriers (status, created_at);

create table if not exists relay_points (
    id varchar(255) not null,
    operator_user_id varchar(255),
    name varchar(255) not null,
    status varchar(64) not null,
    city varchar(255),
    neighborhood varchar(255),
    landmark varchar(500),
    wallet_provider varchar(64),
    wallet_account_ref varchar(255),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table relay_points
    add constraint fk_relay_points_operator_user
    foreign key (operator_user_id) references users (id);

create index if not exists idx_relay_points_operator_user
    on relay_points (operator_user_id);

create index if not exists idx_relay_points_location_status
    on relay_points (city, neighborhood, status);

create table if not exists relay_locker_grids (
    id varchar(255) not null,
    relay_point_id varchar(255) not null,
    grid_code varchar(64) not null,
    label varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table relay_locker_grids
    add constraint fk_relay_locker_grids_relay_point
    foreign key (relay_point_id) references relay_points (id);

alter table relay_locker_grids
    add constraint uk_relay_locker_grids_relay_code
    unique (relay_point_id, grid_code);

create index if not exists idx_relay_locker_grids_relay_point
    on relay_locker_grids (relay_point_id);


-- ============================================================================
-- Squashed from V30__create_delivery_pricing_settings.sql
-- ============================================================================

create table if not exists delivery_pricing_settings (
    id varchar(255) not null,
    profile varchar(64) not null,
    minimum_delivery_fee_cfa integer not null,
    extra_km_fee_cfa integer not null,
    included_km integer not null,
    active boolean not null default false,
    effective_from timestamp with time zone not null,
    created_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table delivery_pricing_settings
    add constraint chk_delivery_pricing_settings_money_nonnegative
    check (
        minimum_delivery_fee_cfa >= 0
        and extra_km_fee_cfa >= 0
        and included_km >= 0
    );

create index if not exists idx_delivery_pricing_settings_active_effective
    on delivery_pricing_settings (active, effective_from desc, created_at desc);

insert into delivery_pricing_settings (
    id,
    profile,
    minimum_delivery_fee_cfa,
    extra_km_fee_cfa,
    included_km,
    active,
    effective_from
) values (
    'default-2026-09',
    'DEFAULT',
    400,
    100,
    5,
    true,
    current_timestamp
);


-- ============================================================================
-- Squashed from V31__create_commerce_catalog_tables.sql
-- ============================================================================

alter table products
    add column if not exists detail varchar(500);

alter table products
    add column if not exists option_hint varchar(240);

alter table products
    add column if not exists subcategory varchar(128);

alter table products
    add column if not exists original_price_cfa integer;

alter table products
    add column if not exists bargaining_enabled boolean not null default false;

alter table products
    add column if not exists bargain_floor_cfa integer;

alter table products
    add column if not exists camera_verified boolean not null default false;

alter table products
    add column if not exists captured_at_label varchar(120);

alter table products
    add column if not exists sort_order integer not null default 0;

alter table products
    add constraint chk_products_original_price
    check (original_price_cfa is null or base_price_cfa is null or original_price_cfa >= base_price_cfa);

alter table products
    add constraint chk_products_bargain_floor
    check (bargain_floor_cfa is null or bargain_floor_cfa >= 0);

create table catalog_categories (
    category_key varchar(64) primary key,
    title varchar(120) not null,
    support_label varchar(160) not null,
    accent_hex varchar(16) not null,
    sort_order integer not null,
    active boolean not null default true,
    created_at timestamp with time zone not null default current_timestamp
);

create table merchant_storefronts (
    merchant_id varchar(255) primary key,
    area varchar(160),
    kind varchar(160),
    distance_km double precision,
    eta varchar(80),
    photo_status varchar(120),
    open_status varchar(120),
    rating varchar(24),
    consolidation varchar(160),
    active boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_merchant_storefronts_merchant foreign key (merchant_id) references merchants(id),
    constraint chk_merchant_storefronts_distance check (distance_km is null or distance_km >= 0)
);

create table catalog_promotions (
    id varchar(128) primary key,
    headline varchar(160) not null,
    title varchar(180) not null,
    subtitle varchar(260) not null,
    product_id varchar(255) not null,
    starts_at timestamp with time zone,
    ends_at timestamp with time zone,
    sort_order integer not null,
    active boolean not null default true,
    created_at timestamp with time zone not null default current_timestamp,
    constraint fk_catalog_promotions_product foreign key (product_id) references products(id),
    constraint chk_catalog_promotions_window check (ends_at is null or starts_at is null or ends_at > starts_at)
);

create table customer_cart_items (
    id varchar(128) primary key,
    user_id varchar(128) not null,
    product_id varchar(255) not null,
    quantity integer not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_customer_cart_items_user foreign key (user_id) references users(id),
    constraint fk_customer_cart_items_product foreign key (product_id) references products(id),
    constraint chk_customer_cart_items_quantity check (quantity > 0 and quantity <= 99)
);

create table bargaining_threads (
    id varchar(128) primary key,
    customer_id varchar(128) not null,
    merchant_id varchar(255) not null,
    product_id varchar(255) not null,
    status varchar(64) not null,
    opened_at timestamp with time zone not null default current_timestamp,
    closed_at timestamp with time zone,
    expires_at timestamp with time zone,
    last_offer_cfa integer,
    accepted_price_cfa integer,
    version bigint not null default 0,
    constraint fk_bargaining_threads_customer foreign key (customer_id) references users(id),
    constraint fk_bargaining_threads_merchant foreign key (merchant_id) references merchants(id),
    constraint fk_bargaining_threads_product foreign key (product_id) references products(id),
    constraint chk_bargaining_threads_status check (status in ('OPEN', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    constraint chk_bargaining_threads_prices check (
        (last_offer_cfa is null or last_offer_cfa >= 0)
        and (accepted_price_cfa is null or accepted_price_cfa >= 0)
    )
);

create table bargaining_offers (
    id varchar(128) primary key,
    thread_id varchar(128) not null,
    actor_user_id varchar(128) not null,
    actor_type varchar(64) not null,
    offer_type varchar(64) not null,
    amount_cfa integer,
    message varchar(500),
    created_at timestamp with time zone not null default current_timestamp,
    constraint fk_bargaining_offers_thread foreign key (thread_id) references bargaining_threads(id),
    constraint fk_bargaining_offers_actor foreign key (actor_user_id) references users(id),
    constraint chk_bargaining_offers_actor_type check (actor_type in ('CUSTOMER', 'MERCHANT', 'SUPPORT')),
    constraint chk_bargaining_offers_type check (offer_type in ('OFFER', 'COUNTER_OFFER', 'ACCEPT', 'REJECT', 'MESSAGE')),
    constraint chk_bargaining_offers_amount check (amount_cfa is null or amount_cfa >= 0)
);

create unique index ux_customer_cart_items_user_product on customer_cart_items(user_id, product_id);
create index idx_products_category_status_sort on products(category, status, sort_order, created_at desc);
create index idx_products_subcategory_status on products(subcategory, status, sort_order);
create index idx_merchant_storefronts_active_sort on merchant_storefronts(active, sort_order);
create index idx_catalog_promotions_active_sort on catalog_promotions(active, sort_order);
create index idx_bargaining_threads_customer_status on bargaining_threads(customer_id, status, opened_at desc);
create index idx_bargaining_threads_merchant_status on bargaining_threads(merchant_id, status, opened_at desc);

insert into catalog_categories (category_key, title, support_label, accent_hex, sort_order) values
('food', 'Food', 'Hot meals', '#E2693D', 10),
('grocery', 'Grocery', 'Fresh & pantry', '#1B8A5A', 20),
('fashion', 'Fashion', 'Clothes & shoes', '#7B5EA7', 30),
('electronics', 'Electronics', 'Phones & tech', '#4D6F9E', 40),
('pharmacy', 'Pharmacy', 'Care items', '#6C7E51', 50),
('home', 'Home & baby', 'Daily basics', '#A77A41', 60);

insert into merchants (id, name, status, commission_rate_bps) values
('merchant-chez-ramatou', 'Chez Ramatou Attieke', 'ACTIVE', 1500),
('merchant-grand-marche-assigame', 'Grand Marche Assigame', 'ACTIVE', 1500),
('merchant-hedzranawoe-electronics', 'Hedzranawoe Electronics', 'ACTIVE', 1500),
('merchant-pharmacie-du-golfe', 'Pharmacie du Golfe', 'ACTIVE', 1500),
('merchant-tokoin-urban-wear', 'Tokoin Urban Wear', 'ACTIVE', 1500),
('merchant-ablogame-grocery', 'Ablogame Grocery', 'ACTIVE', 1500);

insert into merchant_storefronts (
    merchant_id, area, kind, distance_km, eta, photo_status, open_status, rating, consolidation, sort_order
) values
('merchant-chez-ramatou', 'Tokoin Gbadago', 'Food now', 1.8, '22 min', 'Hot meals', 'Open until 22:30', '4.8', 'Packed warm', 10),
('merchant-grand-marche-assigame', 'Assigame', 'Market cooperative', 3.1, '45 min', 'Fresh picks', 'Open now', '4.6', 'Grouped order', 20),
('merchant-hedzranawoe-electronics', 'Hedzranawoe', 'Electronics & phones', 5.6, 'Tomorrow', 'Phones & laptops', 'Ships today', '4.7', 'Sealed box', 30),
('merchant-pharmacie-du-golfe', 'Be-Kpota', 'Pharmacy & care', 4.7, '35 min', 'Care items', 'Open until 23:00', '4.7', 'Care items sealed', 40),
('merchant-tokoin-urban-wear', 'Tokoin', 'Fashion clothes', 2.3, '35 min', 'Clothes', 'Open now', '4.5', 'Folded pack', 50),
('merchant-ablogame-grocery', 'Ablogame', 'Grocery pantry', 5.9, 'Today 19:00', 'Pantry', 'Open until 21:00', '4.6', 'Heavy bag', 60);

insert into products (
    id, merchant_id, name, kind, status, category, subcategory, detail, base_price_cfa, option_hint,
    original_price_cfa, bargaining_enabled, bargain_floor_cfa, camera_verified, captured_at_label, sort_order
) values
('attieke-poisson-braise', 'merchant-chez-ramatou', 'Attieke poisson braise', 'SellerSpecific', 'ACTIVE', 'food', null, 'Choose fish size, piment, onion, alloco', 4200, 'Medium fish / piment doux / extra onion', null, false, null, false, null, 10),
('riz-gras-poulet', 'merchant-chez-ramatou', 'Riz gras poulet', 'SellerSpecific', 'ACTIVE', 'food', null, 'Sauce tomate, fried plantain, cold bissap', 3300, 'No pepper / add bissap', null, false, null, false, null, 20),
('pagne-wax-6-yards', 'merchant-grand-marche-assigame', 'Pagne wax 6 yards', 'SellerSpecific', 'ACTIVE', 'grocery', null, 'Pattern preview, bargain available', 17500, 'Historical minimum: 15 000 CFA', null, true, 15000, false, null, 10),
('tomato-onion-basket', 'merchant-grand-marche-assigame', 'Tomato and onion basket', 'SellerSpecific', 'ACTIVE', 'grocery', 'Produce', 'Fresh produce inspected at pickup', 5200, 'Family basket / today harvest', null, false, null, false, null, 20),
('iphone-15-rose', 'merchant-hedzranawoe-electronics', 'iPhone 15 rose', 'SellerSpecific', 'ACTIVE', 'electronics', 'Phones', '128 GB, clean finish', 420000, 'Pink / sealed box', null, false, null, false, null, 10),
('hp-laptop-15', 'merchant-hedzranawoe-electronics', 'HP laptop 15', 'SellerSpecific', 'ACTIVE', 'electronics', 'Laptops', 'Everyday Windows laptop', 245000, '8 GB RAM / 256 GB SSD', null, true, 230000, false, null, 20),
('hydrafizz-fraise', 'merchant-pharmacie-du-golfe', 'HydraFizz fraise', 'GenericSealedItem', 'ACTIVE', 'pharmacy', 'Care', 'Effervescent hydration tube', 4800, 'Strawberry / 16 tablets', null, false, null, false, null, 10),
('paracetamol-500-mg', 'merchant-pharmacie-du-golfe', 'Paracetamol 500 mg', 'GenericSealedItem', 'ACTIVE', 'pharmacy', 'Care', 'Pain and fever box', 1200, '16 tablets / sealed box', null, false, null, false, null, 20),
('graphic-black-tee', 'merchant-tokoin-urban-wear', 'Graphic black tee', 'SellerSpecific', 'ACTIVE', 'fashion', 'Clothes', 'Printed cotton shirt', 8500, 'Size M / black', null, false, null, false, null, 10),
('blue-office-shirt', 'merchant-tokoin-urban-wear', 'Blue office shirt', 'SellerSpecific', 'ACTIVE', 'fashion', 'Clothes', 'Long-sleeve shirt', 14500, 'Size L / blue', null, false, null, false, null, 20),
('lait-frais-1l', 'merchant-ablogame-grocery', 'Lait frais 1L', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Pantry', 'Fresh milk bottle', 1900, '1L / chilled', 2300, false, null, true, 'Taken today', 10),
('noix-de-coco', 'merchant-ablogame-grocery', 'Noix de coco', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Produce', 'Fresh coconut', 1200, '1 piece / fresh', null, false, null, true, 'Taken today', 20),
('brochettes-poulet', 'merchant-chez-ramatou', 'Brochettes poulet', 'SellerSpecific', 'ACTIVE', 'food', 'Grill', 'Grilled skewers with onion and piment', 2800, '4 pieces / spicy sauce', null, false, null, false, null, 30),
('yassa-poulet', 'merchant-chez-ramatou', 'Yassa poulet', 'SellerSpecific', 'ACTIVE', 'food', 'Rice plates', 'Onion sauce with couscous', 3800, 'Mild / extra onion', null, false, null, false, null, 40),
('pizza-maison', 'merchant-chez-ramatou', 'Pizza maison', 'SellerSpecific', 'ACTIVE', 'food', 'Fast food', 'Vegetable pizza with cold drink', 5200, 'Medium / sliced', null, false, null, false, null, 50),
('banana-bunch', 'merchant-grand-marche-assigame', 'Banana bunch', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Produce', 'Sweet ripe bananas', 1800, 'Small bunch / ready to eat', null, false, null, true, 'Taken today', 30),
('fruit-mix-basket', 'merchant-grand-marche-assigame', 'Fruit mix basket', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Produce', 'Seasonal fruit selection', 4600, 'Banana / citrus / mango', null, false, null, true, 'Taken today', 40),
('cherry-snack-pack', 'merchant-grand-marche-assigame', 'Cherry snack pack', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Produce', 'Small fruit snack pack', 2600, 'Washed / sealed cup', null, false, null, true, 'Taken today', 50),
('poco-smartphone', 'merchant-hedzranawoe-electronics', 'Poco smartphone', 'SellerSpecific', 'ACTIVE', 'electronics', 'Phones', 'Large screen Android phone', 155000, '8 GB RAM / 256 GB', null, true, 145000, false, null, 30),
('dell-latitude', 'merchant-hedzranawoe-electronics', 'Dell Latitude', 'SellerSpecific', 'ACTIVE', 'electronics', 'Laptops', 'Work laptop, slim body', 310000, 'Core i5 / 512 GB SSD', null, true, 295000, false, null, 40),
('iphone-15-noir', 'merchant-hedzranawoe-electronics', 'iPhone 15 noir', 'SellerSpecific', 'ACTIVE', 'electronics', 'Phones', '128 GB, sealed box', 445000, 'Black / 128 GB', null, false, null, false, null, 50),
('dell-business-laptop', 'merchant-hedzranawoe-electronics', 'Dell business laptop', 'SellerSpecific', 'ACTIVE', 'electronics', 'Laptops', 'Office laptop, metal body', 285000, 'Core i7 / 16 GB RAM', null, true, 265000, false, null, 60),
('upsa-vitamine-c', 'merchant-pharmacie-du-golfe', 'UPSA vitamine C', 'GenericSealedItem', 'ACTIVE', 'pharmacy', 'Care', 'Orange vitamin C sachets', 3500, 'Orange / 10 sachets', null, false, null, false, null, 30),
('upsa-booster-mate', 'merchant-pharmacie-du-golfe', 'UPSA booster mate', 'GenericSealedItem', 'ACTIVE', 'pharmacy', 'Care', 'Energy tablets', 5200, '20 tablets / lemon mate', null, false, null, false, null, 40),
('doliprane-1000-mg', 'merchant-pharmacie-du-golfe', 'Doliprane 1000 mg', 'GenericSealedItem', 'ACTIVE', 'pharmacy', 'Care', 'Adult paracetamol box', 1800, '8 tablets / sealed box', null, false, null, false, null, 50),
('white-running-shoes', 'merchant-tokoin-urban-wear', 'White running shoes', 'SellerSpecific', 'ACTIVE', 'fashion', 'Shoes', 'Lightweight sport pair', 28500, 'Size 41 / white', null, true, 26000, false, null, 30),
('plain-black-tee', 'merchant-tokoin-urban-wear', 'Plain black tee', 'SellerSpecific', 'ACTIVE', 'fashion', 'Clothes', 'Everyday cotton shirt', 5500, 'Size M / black', null, false, null, false, null, 40),
('plain-white-tee', 'merchant-tokoin-urban-wear', 'Plain white tee', 'SellerSpecific', 'ACTIVE', 'fashion', 'Clothes', 'Clean cotton basic', 5500, 'Size L / white', null, false, null, false, null, 50),
('green-tee', 'merchant-tokoin-urban-wear', 'Green tee', 'SellerSpecific', 'ACTIVE', 'fashion', 'Clothes', 'Soft cotton basic', 6500, 'Size M / green', null, false, null, false, null, 60),
('striped-shirt', 'merchant-tokoin-urban-wear', 'Striped shirt', 'SellerSpecific', 'ACTIVE', 'fashion', 'Clothes', 'Long-sleeve striped shirt', 16000, 'Size M / blue', null, true, 14500, false, null, 70),
('huile-colza-1l', 'merchant-ablogame-grocery', 'Huile de colza 1L', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Pantry', 'Cooking oil bottle', 3200, '1L / colza', null, false, null, false, null, 30),
('huile-bio-colza', 'merchant-ablogame-grocery', 'Huile bio colza', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Pantry', 'Organic cooking oil', 4800, '75cl / bio', null, false, null, false, null, 40),
('farine-ble-1kg', 'merchant-ablogame-grocery', 'Farine de ble 1kg', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Pantry', 'Wheat flour bag', 1600, '1kg / T45', null, false, null, false, null, 50),
('farine-manioc', 'merchant-ablogame-grocery', 'Farine de manioc', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Pantry', 'Cassava flour pouch', 1800, '250g / bio', null, false, null, false, null, 60),
('noix-cajou', 'merchant-ablogame-grocery', 'Noix de cajou', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Pantry', 'Cashew bowl', 3500, '250g / roasted', null, false, null, true, 'Taken today', 70),
('haricots-melanges', 'merchant-ablogame-grocery', 'Haricots melanges', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Pantry', 'Mixed dry beans', 2300, '1kg / dry', null, false, null, false, null, 80),
('pack-eau-1-5l-x6', 'merchant-ablogame-grocery', 'Pack eau 1.5L x 6', 'GenericSealedItem', 'ACTIVE', 'home', 'Daily basics', 'Still water family pack', 2400, '6 bottles / room temperature', null, false, null, false, null, 90),
('liquide-vaisselle-1l', 'merchant-ablogame-grocery', 'Liquide vaisselle 1L', 'GenericSealedItem', 'ACTIVE', 'home', 'Cleaners', 'Lemon scent dish soap', 1500, 'Lemon scent / sealed bottle', null, false, null, false, null, 100),
('savon-lessive-pack', 'merchant-ablogame-grocery', 'Savon lessive pack', 'GenericSealedItem', 'ACTIVE', 'home', 'Cleaners', 'Family laundry pack', 3200, '6 bars / sealed pack', null, false, null, false, null, 110),
('papier-cuisine', 'merchant-ablogame-grocery', 'Papier cuisine', 'GenericSealedItem', 'ACTIVE', 'home', 'Daily basics', 'Two-roll kitchen paper', 2100, '2 rolls / white', null, false, null, false, null, 120);

insert into catalog_promotions (id, headline, title, subtitle, product_id, sort_order) values
('promo-hot-food', 'Ready now', 'Attieke poisson braise', 'Warm meal from Chez Ramatou Attieke', 'attieke-poisson-braise', 10),
('promo-bargain-market', 'Bargain available', 'Pagne wax 6 yards', 'Try an offer before checkout', 'pagne-wax-6-yards', 20),
('promo-fresh-grocery', 'Camera verified', 'Lait frais 1L', 'Fresh item photographed today', 'lait-frais-1l', 30);

-- ============================================================================
-- MVP readiness tables for contract coverage.
-- These tables expose real API surfaces now while keeping external integrations
-- such as mobile money providers configurable until production credentials exist.
-- ============================================================================

create table customer_profiles (
    user_id varchar(128) primary key,
    display_name varchar(180) not null,
    phone_number varchar(64),
    receipt_email varchar(254),
    default_address_id varchar(128),
    marketing_opt_in boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_customer_profiles_user foreign key (user_id) references users(id)
);

create table customer_addresses (
    id varchar(128) primary key,
    user_id varchar(128) not null,
    kind varchar(64) not null,
    label varchar(180) not null,
    recipient_name varchar(180) not null,
    phone_number varchar(64) not null,
    city varchar(120) not null,
    area varchar(160),
    street_hint varchar(240),
    latitude double precision,
    longitude double precision,
    active boolean not null default true,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_customer_addresses_user foreign key (user_id) references users(id),
    constraint chk_customer_addresses_kind check (kind in ('HOME', 'WORK', 'RELAY', 'OTHER')),
    constraint chk_customer_addresses_coordinates check (
        (latitude is null or latitude between -90 and 90)
        and (longitude is null or longitude between -180 and 180)
    )
);

create table subscription_plans (
    id varchar(128) primary key,
    name varchar(120) not null,
    monthly_price_cfa integer not null,
    delivery_discount_percent integer not null,
    monthly_discount_cap_cfa integer,
    loyalty_months_threshold integer not null default 0,
    loyalty_discount_percent integer not null default 0,
    active boolean not null default true,
    created_at timestamp with time zone not null default current_timestamp,
    constraint chk_subscription_plans_amounts check (
        monthly_price_cfa >= 0
        and delivery_discount_percent between 0 and 100
        and (monthly_discount_cap_cfa is null or monthly_discount_cap_cfa >= 0)
        and loyalty_months_threshold >= 0
        and loyalty_discount_percent between 0 and 100
    )
);

create table customer_subscriptions (
    id varchar(128) primary key,
    user_id varchar(128) not null,
    plan_id varchar(128) not null,
    status varchar(64) not null,
    started_at timestamp with time zone not null default current_timestamp,
    current_period_ends_at timestamp with time zone not null,
    cancel_at_period_end boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_customer_subscriptions_user foreign key (user_id) references users(id),
    constraint fk_customer_subscriptions_plan foreign key (plan_id) references subscription_plans(id),
    constraint chk_customer_subscriptions_status check (status in ('ACTIVE', 'PAUSED', 'CANCELLED', 'EXPIRED')),
    constraint chk_customer_subscriptions_period check (current_period_ends_at > started_at)
);

create table referral_delivery_credits (
    id varchar(128) primary key,
    referrer_user_id varchar(128) not null,
    referred_user_id varchar(128) not null,
    credit_cfa integer not null,
    status varchar(64) not null,
    source_code varchar(64),
    created_at timestamp with time zone not null default current_timestamp,
    expires_at timestamp with time zone,
    constraint fk_referral_delivery_credits_referrer foreign key (referrer_user_id) references users(id),
    constraint fk_referral_delivery_credits_referred foreign key (referred_user_id) references users(id),
    constraint chk_referral_delivery_credits_status check (status in ('RESERVED', 'AVAILABLE', 'APPLIED', 'EXPIRED', 'CANCELLED')),
    constraint chk_referral_delivery_credits_amount check (credit_cfa >= 0),
    constraint chk_referral_delivery_credits_not_self check (referrer_user_id <> referred_user_id)
);

create table payment_provider_configurations (
    provider_id varchar(64) primary key,
    display_name varchar(120) not null,
    country_code varchar(8) not null,
    readiness varchar(64) not null,
    customer_checkout_enabled boolean not null default false,
    refund_enabled boolean not null default false,
    courier_payout_enabled boolean not null default false,
    notes varchar(500),
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint chk_payment_provider_configurations_readiness check (readiness in ('PLANNED', 'SANDBOX_READY', 'LIVE_READY', 'DISABLED'))
);

create table product_customization_groups (
    id varchar(128) primary key,
    product_id varchar(255) not null,
    name varchar(120) not null,
    min_choices integer not null default 0,
    max_choices integer not null default 1,
    sort_order integer not null default 0,
    active boolean not null default true,
    constraint fk_product_customization_groups_product foreign key (product_id) references products(id),
    constraint chk_product_customization_groups_choices check (min_choices >= 0 and max_choices >= min_choices)
);

create table product_customization_options (
    id varchar(128) primary key,
    group_id varchar(128) not null,
    name varchar(140) not null,
    price_delta_cfa integer not null default 0,
    sort_order integer not null default 0,
    active boolean not null default true,
    constraint fk_product_customization_options_group foreign key (group_id) references product_customization_groups(id),
    constraint chk_product_customization_options_price_delta check (price_delta_cfa >= 0)
);

create table merchant_cooperatives (
    id varchar(128) primary key,
    code varchar(120) not null,
    name varchar(180) not null,
    city varchar(120) not null,
    area varchar(160),
    status varchar(64) not null,
    reviewed_by_user_id varchar(128),
    reviewed_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint ux_merchant_cooperatives_code unique (code),
    constraint fk_merchant_cooperatives_reviewer foreign key (reviewed_by_user_id) references users(id),
    constraint chk_merchant_cooperatives_status check (status in ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED'))
);

create table merchant_cooperative_members (
    id varchar(128) primary key,
    cooperative_id varchar(128) not null,
    merchant_id varchar(255) not null,
    role varchar(64) not null,
    created_at timestamp with time zone not null default current_timestamp,
    constraint fk_merchant_cooperative_members_cooperative foreign key (cooperative_id) references merchant_cooperatives(id),
    constraint fk_merchant_cooperative_members_merchant foreign key (merchant_id) references merchants(id),
    constraint chk_merchant_cooperative_members_role check (role in ('OWNER', 'MEMBER'))
);

create index idx_customer_addresses_user_active on customer_addresses(user_id, active, updated_at desc);
create index idx_customer_subscriptions_user_created on customer_subscriptions(user_id, created_at desc);
create index idx_referral_delivery_credits_referrer on referral_delivery_credits(referrer_user_id, created_at desc);
create index idx_referral_delivery_credits_referred on referral_delivery_credits(referred_user_id, created_at desc);
create index idx_product_customization_groups_product on product_customization_groups(product_id, sort_order);
create index idx_product_customization_options_group on product_customization_options(group_id, sort_order);
create unique index ux_merchant_cooperative_members_unique on merchant_cooperative_members(cooperative_id, merchant_id);

insert into subscription_plans (
    id, name, monthly_price_cfa, delivery_discount_percent, monthly_discount_cap_cfa,
    loyalty_months_threshold, loyalty_discount_percent, active
) values
('subscriber-basic', 'Sequo Basic', 2500, 10, 3000, 6, 5, true),
('subscriber-plus', 'Sequo Plus', 5000, 20, 7500, 6, 8, true);

insert into payment_provider_configurations (
    provider_id, display_name, country_code, readiness, customer_checkout_enabled,
    refund_enabled, courier_payout_enabled, notes
) values
('yas_togo', 'YAS Togo', 'TG', 'PLANNED', false, false, false, 'Contract MVP requires preparation; live credentials and provider API access are not wired yet.'),
('moov_africa', 'Moov Africa', 'TG', 'PLANNED', false, false, false, 'Contract MVP requires preparation; live credentials and provider API access are not wired yet.');

insert into product_customization_groups (id, product_id, name, min_choices, max_choices, sort_order, active) values
('custom-attieke-piment', 'attieke-poisson-braise', 'Piment', 0, 1, 10, true),
('custom-attieke-side', 'attieke-poisson-braise', 'Supplements', 0, 3, 20, true),
('custom-riz-drink', 'riz-gras-poulet', 'Boisson', 0, 1, 10, true);

insert into product_customization_options (id, group_id, name, price_delta_cfa, sort_order, active) values
('custom-attieke-piment-none', 'custom-attieke-piment', 'Sans piment', 0, 10, true),
('custom-attieke-piment-soft', 'custom-attieke-piment', 'Piment doux', 0, 20, true),
('custom-attieke-piment-hot', 'custom-attieke-piment', 'Piment fort', 0, 30, true),
('custom-attieke-alloco', 'custom-attieke-side', 'Alloco', 500, 10, true),
('custom-attieke-onion', 'custom-attieke-side', 'Extra oignon', 300, 20, true),
('custom-riz-bissap', 'custom-riz-drink', 'Bissap frais', 500, 10, true);

insert into merchant_cooperatives (id, code, name, city, area, status) values
('coop-assigame-market', 'ASSIGAME-MARKET', 'Cooperative Assigame Market', 'Lome', 'Assigame', 'APPROVED');

insert into merchant_cooperative_members (id, cooperative_id, merchant_id, role) values
('coop-assigame-market-owner', 'coop-assigame-market', 'merchant-grand-marche-assigame', 'OWNER'),
('coop-assigame-market-grocery', 'coop-assigame-market', 'merchant-ablogame-grocery', 'MEMBER');
