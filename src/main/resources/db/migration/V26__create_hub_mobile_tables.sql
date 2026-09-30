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
