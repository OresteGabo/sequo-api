create table delivery_mission_idempotency_keys (
    id varchar(255) not null,
    idempotency_key varchar(128) not null,
    mission_id varchar(255) not null,
    actor_user_id varchar(255) not null,
    event_type varchar(64) not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

alter table delivery_mission_idempotency_keys
    add constraint fk_delivery_mission_idempotency_keys_mission
    foreign key (mission_id) references delivery_missions (id);

alter table delivery_mission_idempotency_keys
    add constraint uk_delivery_mission_idempotency_keys_key unique (idempotency_key);

alter table delivery_mission_idempotency_keys
    add constraint chk_delivery_mission_idempotency_keys_event
    check (event_type in (
        'OfferToCourier',
        'CourierAccepts',
        'CourierPicksUpFromSeller',
        'CourierDeliversToCustomer',
        'CourierDepositsAtRelay',
        'RelayReleasesToCustomer',
        'ReportProblem',
        'Cancel'
    ));

create index idx_delivery_mission_idempotency_keys_mission
    on delivery_mission_idempotency_keys (mission_id, created_at);
