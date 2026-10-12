create table admin_login_requests (
    id varchar(255) not null,
    email_normalized varchar(320) not null,
    code_hash varchar(96) not null,
    status varchar(32) not null,
    device_label varchar(255),
    browser_info varchar(255),
    requested_at timestamp with time zone not null default current_timestamp,
    expires_at timestamp with time zone not null,
    approved_at timestamp with time zone,
    rejected_at timestamp with time zone,
    consumed_at timestamp with time zone,
    approved_by_user_id varchar(255),
    rejection_reason varchar(255),
    created_ip_hash varchar(96),
    primary key (id),
    constraint chk_admin_login_requests_status
        check (status in ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CONSUMED')),
    constraint chk_admin_login_requests_expiry
        check (expires_at > requested_at)
);

create index idx_admin_login_requests_email_status_expiry
    on admin_login_requests(email_normalized, status, expires_at desc);

create index idx_admin_login_requests_status_expiry
    on admin_login_requests(status, expires_at desc);

create index idx_admin_login_requests_approved_by_user
    on admin_login_requests(approved_by_user_id);
