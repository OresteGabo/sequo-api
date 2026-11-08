create table admin_catalog_media_changes (
    id varchar(255) primary key,
    product_id varchar(255) not null references products(id),
    actor_user_id varchar(255) not null references users(id),
    action varchar(32) not null,
    reason varchar(1000) not null,
    idempotency_key varchar(128) not null,
    intent_hash varchar(64) not null,
    before_value text not null,
    after_value text not null,
    created_at timestamp with time zone not null,
    constraint uk_admin_catalog_media_retry unique(idempotency_key),
    constraint ck_admin_catalog_media_action check (action in ('GENERIC_IMAGE', 'GENERIC_UPLOAD', 'CAMERA_IMAGE', 'PUBLICATION'))
);
create index idx_admin_catalog_media_history on admin_catalog_media_changes(product_id, created_at);
