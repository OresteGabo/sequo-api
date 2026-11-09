create table test_account_credentials (
    username varchar(80) primary key,
    user_id varchar(255) not null references users(id),
    app_family varchar(32) not null,
    password_hash varchar(500) not null,
    enabled boolean not null default true,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null default current_timestamp,
    constraint chk_test_credential_source check (app_family in ('CUSTOMER','VENDOR','HUB','RIDER')),
    constraint chk_test_credential_user check (user_id = 'live-test-v1-customer' or user_id like 'demo-v1-shop-%-owner' or user_id like 'live-test-v1-vendor-staff-%' or user_id like 'live-test-v1-hub-%-operator' or user_id in ('demo-v1-rider-1-user','demo-v1-rider-2-user','demo-v1-rider-3-user','demo-v1-rider-4-user'))
);
create index ix_test_credentials_user on test_account_credentials(user_id);
