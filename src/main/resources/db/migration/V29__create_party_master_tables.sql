create table if not exists merchants (
    id varchar(255) not null,
    owner_user_id varchar(255),
    name varchar(255) not null,
    status varchar(64) not null,
    commission_rate_bps integer not null default 1500,
    wallet_provider varchar(64),
    wallet_account_ref varchar(255),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table merchants
    add constraint fk_merchants_owner_user
    foreign key (owner_user_id) references users (id);

alter table merchants
    add constraint chk_merchants_commission_rate
    check (commission_rate_bps between 0 and 10000);

create index if not exists idx_merchants_owner_user
    on merchants (owner_user_id);

create index if not exists idx_merchants_status
    on merchants (status, created_at);

create table if not exists couriers (
    id varchar(255) not null,
    user_id varchar(255),
    status varchar(64) not null,
    workforce_type varchar(64),
    vehicle_type varchar(64),
    wallet_provider varchar(64),
    wallet_account_ref varchar(255),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table couriers
    add constraint fk_couriers_user
    foreign key (user_id) references users (id);

alter table couriers
    add constraint uk_couriers_user_id
    unique (user_id);

create index if not exists idx_couriers_status
    on couriers (status, created_at);

create table if not exists relay_points (
    id varchar(255) not null,
    operator_user_id varchar(255),
    name varchar(255) not null,
    status varchar(64) not null,
    city varchar(255),
    neighborhood varchar(255),
    landmark varchar(500),
    wallet_provider varchar(64),
    wallet_account_ref varchar(255),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table relay_points
    add constraint fk_relay_points_operator_user
    foreign key (operator_user_id) references users (id);

create index if not exists idx_relay_points_operator_user
    on relay_points (operator_user_id);

create index if not exists idx_relay_points_location_status
    on relay_points (city, neighborhood, status);

create table if not exists relay_locker_grids (
    id varchar(255) not null,
    relay_point_id varchar(255) not null,
    grid_code varchar(64) not null,
    label varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table relay_locker_grids
    add constraint fk_relay_locker_grids_relay_point
    foreign key (relay_point_id) references relay_points (id);

alter table relay_locker_grids
    add constraint uk_relay_locker_grids_relay_code
    unique (relay_point_id, grid_code);

create index if not exists idx_relay_locker_grids_relay_point
    on relay_locker_grids (relay_point_id);
