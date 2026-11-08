-- New administrator operations only: no invented legacy actors/history and no financial rewrites.
create table admin_party_changes (
    id varchar(255) not null primary key,
    domain varchar(32) not null,
    entity_id varchar(255) not null,
    action varchar(32) not null,
    actor_user_id varchar(255) not null,
    before_value varchar(255),
    after_value varchar(255),
    reason varchar(1000) not null,
    idempotency_key varchar(128) not null,
    intent_hash varchar(64) not null,
    created_at timestamp with time zone not null,
    constraint uk_admin_party_retry unique (idempotency_key),
    constraint fk_admin_party_actor foreign key (actor_user_id) references users(id),
    constraint chk_admin_party_domain check (domain in ('COURIER_WORKFORCE', 'RELAY_OPERATOR')),
    constraint chk_admin_party_action check (action in ('SET_WORKFORCE', 'ASSIGN_OPERATOR', 'REMOVE_OPERATOR'))
);
create index idx_admin_party_history on admin_party_changes (domain, entity_id, created_at desc);
