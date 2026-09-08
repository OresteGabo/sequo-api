create table relay_storage_fee_assessments (
    id varchar(255) not null,
    relay_parcel_id varchar(255) not null,
    relay_point_id varchar(255) not null,
    daily_fee_cfa integer not null,
    chargeable_days bigint not null,
    total_fee_cfa integer not null,
    last_increment_cfa integer not null,
    fee_starts_at timestamp with time zone not null,
    measured_until timestamp with time zone not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    primary key (id)
);

alter table relay_storage_fee_assessments
    add constraint fk_relay_storage_fee_assessments_parcel
    foreign key (relay_parcel_id) references relay_parcels (id);

alter table relay_storage_fee_assessments
    add constraint uk_relay_storage_fee_assessments_parcel unique (relay_parcel_id);

alter table relay_storage_fee_assessments
    add constraint chk_relay_storage_fee_assessments_nonnegative
    check (
        daily_fee_cfa >= 0
        and chargeable_days >= 0
        and total_fee_cfa >= 0
        and last_increment_cfa >= 0
    );

create index idx_relay_storage_fee_assessments_relay
    on relay_storage_fee_assessments (relay_point_id, updated_at);
