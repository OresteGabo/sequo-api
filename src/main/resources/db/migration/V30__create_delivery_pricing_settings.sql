create table if not exists delivery_pricing_settings (
    id varchar(255) not null,
    profile varchar(64) not null,
    minimum_delivery_fee_cfa integer not null,
    extra_km_fee_cfa integer not null,
    included_km integer not null,
    active boolean not null default false,
    effective_from timestamp with time zone not null,
    created_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table delivery_pricing_settings
    add constraint chk_delivery_pricing_settings_money_nonnegative
    check (
        minimum_delivery_fee_cfa >= 0
        and extra_km_fee_cfa >= 0
        and included_km >= 0
    );

create index if not exists idx_delivery_pricing_settings_active_effective
    on delivery_pricing_settings (active, effective_from desc, created_at desc);

insert into delivery_pricing_settings (
    id,
    profile,
    minimum_delivery_fee_cfa,
    extra_km_fee_cfa,
    included_km,
    active,
    effective_from
) values (
    'default-2026-09',
    'DEFAULT',
    400,
    100,
    5,
    true,
    current_timestamp
);
