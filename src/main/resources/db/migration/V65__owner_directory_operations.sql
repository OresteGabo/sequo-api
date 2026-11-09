create table owner_directory_operations (
    id varchar(255) primary key,
    kind varchar(255) not null,
    entity_id varchar(255) not null,
    actor_user_id varchar(255) not null,
    action varchar(255) not null,
    before_json text,
    after_json text not null,
    reason varchar(1000) not null,
    idempotency_key varchar(128) not null unique,
    intent_hash varchar(64) not null,
    created_at timestamp(6) with time zone not null
);
create index idx_owner_directory_history on owner_directory_operations(kind, entity_id, created_at desc);
create trigger owner_directory_immutable before update or delete or truncate on owner_directory_operations
    for each statement execute function reject_activity_journal_mutation();

create index idx_directory_relay_work on delivery_missions(relay_point_id, status);
