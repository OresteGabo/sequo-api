-- Explicit new operator changes only; legacy accounts retain their prior token contract until managed.
alter table users add column authorization_version varchar(64);
alter table users add column record_version bigint not null default 0;
alter table merchant_memberships add column updated_at timestamp with time zone;
update merchant_memberships set updated_at = created_at where updated_at is null;
alter table merchant_memberships alter column updated_at set not null;

create table admin_access_scopes (
    id varchar(255) not null primary key,
    updated_at timestamp with time zone not null
);
insert into admin_access_scopes (id, updated_at) values ('GLOBAL', timestamp with time zone '1970-01-01 00:00:00+00');

create table admin_access_changes (
    id varchar(255) not null primary key,
    domain varchar(32) not null,
    user_id varchar(255) not null,
    merchant_id varchar(255),
    action varchar(32) not null,
    actor_user_id varchar(255) not null,
    before_value varchar(2000),
    after_value varchar(2000),
    reason varchar(1000) not null,
    idempotency_key varchar(128) not null,
    intent_hash varchar(64) not null,
    created_at timestamp with time zone not null,
    constraint uk_admin_access_retry unique (idempotency_key),
    constraint fk_admin_access_user foreign key (user_id) references users(id),
    constraint fk_admin_access_actor foreign key (actor_user_id) references users(id),
    constraint fk_admin_access_merchant foreign key (merchant_id) references merchants(id),
    constraint chk_admin_access_domain check (domain in ('USER_ACCESS', 'MERCHANT_MEMBERSHIP')),
    constraint chk_admin_access_action check (action in ('SET_ACCESS', 'GRANT_ROLE', 'REVOKE_ROLE', 'SET_MEMBERSHIP'))
);
create index idx_admin_access_user_history on admin_access_changes (user_id, created_at desc);
create index idx_admin_access_merchant_history on admin_access_changes (merchant_id, created_at desc);
