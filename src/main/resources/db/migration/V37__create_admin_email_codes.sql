create table admin_email_codes (
    id varchar(255) not null,
    email_normalized varchar(320) not null,
    code_hash varchar(96) not null,
    status varchar(32) not null,
    requested_at timestamp with time zone not null default current_timestamp,
    expires_at timestamp with time zone not null,
    consumed_at timestamp with time zone,
    user_id varchar(255),
    primary key (id),
    constraint chk_admin_email_codes_status
        check (status in ('PENDING', 'CONSUMED')),
    constraint chk_admin_email_codes_expiry
        check (expires_at > requested_at)
);

create index idx_admin_email_codes_email_status_expiry
    on admin_email_codes(email_normalized, status, expires_at desc);

create index idx_admin_email_codes_user
    on admin_email_codes(user_id);
