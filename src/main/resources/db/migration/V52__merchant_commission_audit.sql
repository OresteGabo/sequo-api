-- Existing overrides remain unchanged. History starts with new operations; no actors or events
-- are fabricated for legacy values, and already-paid order commission snapshots are untouched.
create table merchant_commission_audit (
    id varchar(255) not null primary key,
    merchant_id varchar(255) not null,
    generation bigint not null,
    action varchar(16) not null,
    actor_user_id varchar(255),
    before_rate_bps integer not null,
    after_rate_bps integer not null,
    before_override_present boolean not null,
    after_override_present boolean not null,
    reason varchar(500),
    created_at timestamp with time zone not null,
    idempotency_key varchar(128),
    intent_hash varchar(64) not null,
    revision varchar(64) not null,
    override_created_at timestamp with time zone,
    override_updated_at timestamp with time zone,
    constraint uk_commission_audit_generation unique (merchant_id, generation),
    constraint uk_commission_audit_retry unique (merchant_id, actor_user_id, idempotency_key),
    constraint chk_commission_audit_action check (action in ('SET', 'RESET')),
    constraint chk_commission_audit_generation check (generation > 0),
    constraint chk_commission_audit_after_rate check (after_rate_bps between 500 and 1500),
    constraint fk_commission_audit_merchant foreign key (merchant_id) references merchants(id),
    constraint fk_commission_audit_actor foreign key (actor_user_id) references users(id)
);
create index idx_commission_audit_history on merchant_commission_audit (merchant_id, generation desc);
