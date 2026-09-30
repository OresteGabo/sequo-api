create table delivery_dispatch_runs (
    id varchar(255) not null,
    scanned_ready_sub_orders integer not null,
    created_mission_ids varchar(4000) not null,
    existing_mission_ids varchar(4000) not null,
    skipped_sub_order_ids varchar(4000) not null,
    skipped_reasons varchar(4000) not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table delivery_dispatch_runs
    add constraint chk_delivery_dispatch_runs_counts
    check (scanned_ready_sub_orders >= 0);

create index idx_delivery_dispatch_runs_created
    on delivery_dispatch_runs (created_at);
