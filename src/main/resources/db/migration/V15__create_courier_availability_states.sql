create table courier_availability_states (
    courier_id varchar(255) not null,
    status varchar(32) not null,
    paused_reason varchar(1000),
    paused_by varchar(255),
    paused_at timestamp with time zone,
    paused_until timestamp with time zone,
    updated_at timestamp with time zone not null,
    primary key (courier_id)
);

alter table courier_availability_states
    add constraint chk_courier_availability_status
    check (status in ('ACTIVE', 'PAUSED'));

create index idx_courier_availability_status
    on courier_availability_states (status, paused_until);
