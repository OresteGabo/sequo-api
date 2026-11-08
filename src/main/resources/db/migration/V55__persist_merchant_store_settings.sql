-- No default hours, open flags, policy or history are invented for existing merchants.
create table merchant_store_settings (
    merchant_id varchar(255) not null primary key,
    settings_json text not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_store_settings_merchant foreign key (merchant_id) references merchants(id)
);

create table merchant_store_changes (
    id varchar(255) not null primary key,
    merchant_id varchar(255) not null,
    actor_user_id varchar(255) not null,
    before_value text,
    after_value text not null,
    reason varchar(1000) not null,
    idempotency_key varchar(128) not null,
    intent_hash varchar(64) not null,
    created_at timestamp with time zone not null,
    constraint uk_merchant_store_retry unique (idempotency_key),
    constraint fk_store_change_merchant foreign key (merchant_id) references merchants(id),
    constraint fk_store_change_actor foreign key (actor_user_id) references users(id)
);
create index idx_merchant_store_history on merchant_store_changes (merchant_id, created_at desc);
