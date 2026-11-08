-- Do not backfill financial postings or guess historical workforce from today's profile.
alter table settlement_ledger_entries add column workforce_type varchar(64);
-- New server-created missions use policy version 1; existing rows require reconciliation.
alter table delivery_missions add column settlement_policy_version integer;
create table delivery_settlements (
    mission_id varchar(255) primary key references delivery_missions(id),
    order_id varchar(255) not null references customer_orders(id),
    courier_id varchar(255) not null references couriers(id),
    workforce_type varchar(64),
    customer_delivery_fee_allocated_cfa integer not null,
    courier_payable_cfa integer not null,
    shortfall_cfa integer not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    constraint chk_delivery_settlement_workforce check (workforce_type is null or workforce_type in ('Freelancer','SequoSalaried')),
    constraint chk_delivery_settlement_status check (status in ('POSTED','REVIEW_REQUIRED')),
    constraint chk_delivery_settlement_amounts check (customer_delivery_fee_allocated_cfa >= 0 and courier_payable_cfa >= 0 and shortfall_cfa >= 0
        and customer_delivery_fee_allocated_cfa <= courier_payable_cfa
        and shortfall_cfa = courier_payable_cfa - customer_delivery_fee_allocated_cfa)
);
create index idx_delivery_settlement_order on delivery_settlements(order_id);
