-- New explicit enrollments only; no legacy identity, role, wallet or ownership backfill.
create table admin_enrollment_changes (
    id varchar(255) not null primary key,
    kind varchar(16) not null,
    user_id varchar(255) not null,
    entity_id varchar(255) not null,
    actor_user_id varchar(255) not null,
    reason varchar(1000) not null,
    idempotency_key varchar(128) not null,
    intent_hash varchar(64) not null,
    created_at timestamp with time zone not null,
    recorded_name varchar(160),
    workforce_type varchar(32),
    vehicle_type varchar(32),
    constraint uk_admin_enrollment_retry unique (idempotency_key),
    constraint fk_admin_enrollment_user foreign key (user_id) references users(id),
    constraint fk_admin_enrollment_actor foreign key (actor_user_id) references users(id),
    constraint chk_admin_enrollment_kind check (kind in ('MERCHANT', 'COURIER'))
);
create index idx_admin_enrollment_user on admin_enrollment_changes (user_id, created_at desc);
