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
