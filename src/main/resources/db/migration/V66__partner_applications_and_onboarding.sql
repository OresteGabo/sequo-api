create table partner_applications (
    id varchar(255) primary key,
    kind varchar(255) not null check (kind in ('VENDOR','RIDER','HUB')),
    submitted_json text not null,
    state varchar(255) not null check (state in ('PENDING','APPROVED','REJECTED')),
    submitted_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    reviewer_user_id varchar(255),
    review_reason varchar(1000),
    operation_id varchar(255),
    version bigint not null default 0
);
create index idx_partner_application_queue on partner_applications(kind,state,submitted_at);
create table partner_onboarding_operations (
    id varchar(255) primary key,
    actor_user_id varchar(255),
    application_id varchar(255),
    kind varchar(255) not null,
    action varchar(255) not null check(action in ('SUBMIT','REGISTER','APPROVE','REJECT')),
    user_id varchar(255),
    entity_id varchar(255),
    details_json text not null,
    reason varchar(1000) not null,
    idempotency_key varchar(160) not null unique,
    intent_hash varchar(64) not null,
    created_at timestamp(6) with time zone not null
);
create index idx_partner_application_history on partner_onboarding_operations(application_id,created_at);
create trigger partner_onboarding_immutable before update or delete or truncate on partner_onboarding_operations
    for each statement execute function reject_activity_journal_mutation();
