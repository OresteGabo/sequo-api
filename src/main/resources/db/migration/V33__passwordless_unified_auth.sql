-- Passwordless unified authentication for the Sequo ecosystem.
-- Keeps historical auth data migratable while moving credentials into
-- provider identities, devices, and short-lived challenge records.

alter table users drop constraint if exists uk_users_provider_id;
drop index if exists idx_users_provider_provider_id;
drop index if exists idx_users_reset_token_hash;

alter table users add column if not exists phone_number varchar(255);
alter table users add column if not exists display_name varchar(255);
alter table users add column if not exists avatar_url varchar(1024);
alter table users add column if not exists is_active boolean not null default true;
alter table users add column if not exists created_at timestamp with time zone not null default current_timestamp;
alter table users add column if not exists updated_at timestamp with time zone not null default current_timestamp;

update users
set display_name = coalesce(display_name, name)
where display_name is null
  and exists (
    select 1
    from information_schema.columns
    where table_name = 'users'
      and column_name = 'name'
  );

alter table users alter column email drop not null;
alter table users drop column if exists password_hash;
alter table users drop column if exists reset_token_hash;
alter table users drop column if exists reset_token_expiry;
alter table users drop column if exists provider_id;
alter table users drop column if exists name;

alter table users
    add constraint uk_users_phone_number unique (phone_number);

create table user_identities (
    id varchar(255) not null,
    user_id varchar(255) not null,
    provider varchar(32) not null,
    provider_user_id varchar(512) not null,
    credential_public_key text,
    sign_count bigint not null default 0,
    created_at timestamp with time zone not null default current_timestamp,
    last_login_at timestamp with time zone,
    primary key (id),
    constraint fk_user_identities_user foreign key (user_id) references users(id) on delete cascade,
    constraint uk_user_identities_provider_user unique (provider, provider_user_id),
    constraint chk_user_identities_provider check (provider in ('GOOGLE', 'APPLE', 'FACEBOOK', 'PASSKEY')),
    constraint chk_user_identities_sign_count check (sign_count >= 0)
);

insert into user_identities (
    id, user_id, provider, provider_user_id, credential_public_key, sign_count, created_at, last_login_at
)
select id, user_id, provider, provider_subject, null, 0, created_at, last_login_at
from social_identities
where exists (
    select 1
    from information_schema.tables
    where table_name = 'social_identities'
)
on conflict do nothing;

drop table if exists social_identities;

create index idx_user_identities_user on user_identities(user_id);

create table user_devices (
    id varchar(255) not null,
    user_id varchar(255) not null,
    device_id varchar(255) not null,
    app_source varchar(32) not null,
    fcm_token varchar(512),
    refresh_token_hash varchar(96) not null,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null default current_timestamp,
    last_active_at timestamp with time zone not null default current_timestamp,
    revoked_at timestamp with time zone,
    primary key (id),
    constraint fk_user_devices_user foreign key (user_id) references users(id) on delete cascade,
    constraint uk_user_devices_device_id unique (device_id),
    constraint uk_user_devices_refresh_token_hash unique (refresh_token_hash),
    constraint chk_user_devices_app_source check (app_source in ('SEQUO_APP', 'SEQUO_HUB', 'SEQUO_RIDER'))
);

create index idx_user_devices_user_active on user_devices(user_id, revoked_at, expires_at);
create index idx_user_devices_last_active on user_devices(user_id, last_active_at desc);

create table auth_challenges (
    id varchar(255) not null,
    purpose varchar(64) not null,
    subject varchar(255) not null,
    challenge_hash varchar(96) not null,
    requesting_device_id varchar(255),
    requesting_app_source varchar(32),
    approved_user_id varchar(255),
    created_at timestamp with time zone not null default current_timestamp,
    expires_at timestamp with time zone not null,
    consumed_at timestamp with time zone,
    primary key (id),
    constraint chk_auth_challenges_purpose check (purpose in (
        'WHATSAPP_OTP',
        'PASSKEY_REGISTRATION',
        'PASSKEY_AUTHENTICATION',
        'CROSS_DEVICE_LOGIN'
    )),
    constraint chk_auth_challenges_requesting_app check (
        requesting_app_source is null
        or requesting_app_source in ('SEQUO_APP', 'SEQUO_HUB', 'SEQUO_RIDER')
    )
);

create index idx_auth_challenges_subject_purpose on auth_challenges(subject, purpose, expires_at desc);
create index idx_auth_challenges_expires_at on auth_challenges(expires_at);
