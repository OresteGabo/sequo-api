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
