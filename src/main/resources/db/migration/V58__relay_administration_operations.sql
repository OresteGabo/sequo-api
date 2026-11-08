-- Append-only SEQUO setup/receipt audit; existing custody, deadlines and fee history are untouched.
create table relay_administration_operations (
    id varchar(255) primary key,
    relay_point_id varchar(255) not null references relay_points(id),
    action varchar(32) not null,
    actor_user_id varchar(255) not null references users(id),
    reason varchar(1000) not null,
    idempotency_key varchar(128) not null unique,
    intent_hash varchar(64) not null,
    before_json text,
    after_json text not null,
    created_at timestamp with time zone not null
);
create index idx_relay_administration_history on relay_administration_operations(relay_point_id, created_at desc);
