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
