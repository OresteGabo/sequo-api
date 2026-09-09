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
