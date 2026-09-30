create table merchant_commission_overrides (
    merchant_id varchar(255) not null,
    commission_rate_bps integer not null,
    reason varchar(500),
    updated_by_user_id varchar(255) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (merchant_id)
);

alter table merchant_commission_overrides
    add constraint chk_merchant_commission_overrides_rate
    check (commission_rate_bps between 500 and 1500);
