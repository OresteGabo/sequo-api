create table activity_journal (
    id varchar(255) primary key,
    operation_id varchar(255) not null,
    phase varchar(24) not null,
    actor_user_id varchar(255),
    actor_roles varchar(500) not null,
    action varchar(250) not null,
    target_reference varchar(1000) not null,
    http_status integer,
    created_at timestamp(6) with time zone not null,
    constraint chk_activity_phase check (phase in ('STARTED', 'HTTP_SUCCEEDED', 'HTTP_FAILED', 'AUTH_EVENT'))
);
-- Actor IDs are snapshots, deliberately without cascading user foreign keys.
create index idx_activity_time on activity_journal (created_at desc, id desc);
create index idx_activity_actor_time on activity_journal (actor_user_id, created_at desc);
create index idx_activity_operation on activity_journal (operation_id);
create index idx_activity_target_time on activity_journal (target_reference, created_at desc);

create function reject_activity_journal_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'Activity journal records are append-only';
end;
$$;
create trigger activity_journal_immutable before update or delete or truncate on activity_journal
    for each statement execute function reject_activity_journal_mutation();
