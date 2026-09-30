create table merchant_payout_accruals (
    id varchar(255) not null,
    merchant_id varchar(255) not null,
    order_id varchar(255) not null,
    source_order_item_id varchar(255) not null,
    merchant_net_cfa integer not null,
    commission_cfa integer not null,
    platform_margin_cfa integer not null,
    package_received_at timestamp with time zone not null,
    payout_eligible_at timestamp with time zone not null,
    payout_due_by timestamp with time zone not null,
    status varchar(64) not null,
    workflow_type varchar(64) not null,
    active_return_hold boolean not null default false,
    active_dispute_hold boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table merchant_payout_accruals
    add constraint chk_payout_amounts_nonnegative
    check (merchant_net_cfa >= 0 and commission_cfa >= 0 and platform_margin_cfa >= 0);

create index idx_payouts_merchant_status
    on merchant_payout_accruals (merchant_id, status, payout_eligible_at);

create index idx_payouts_due_status
    on merchant_payout_accruals (status, payout_eligible_at, payout_due_by);

create table settlement_ledger_entries (
    id varchar(255) not null,
    account varchar(64) not null,
    direction varchar(32) not null,
    amount_cfa integer not null,
    merchant_id varchar(255),
    courier_id varchar(255),
    relay_point_id varchar(255),
    source_type varchar(64) not null,
    source_id varchar(255) not null,
    description varchar(500) not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table settlement_ledger_entries
    add constraint chk_settlement_amount_positive check (amount_cfa > 0);

create index idx_settlement_source
    on settlement_ledger_entries (source_type, source_id, created_at);

create index idx_settlement_merchant
    on settlement_ledger_entries (merchant_id, created_at);
