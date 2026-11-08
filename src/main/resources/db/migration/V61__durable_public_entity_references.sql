create table public_entity_references (
    entity_kind varchar(32) not null,
    internal_id varchar(255) not null,
    public_reference varchar(11) not null,
    format_version integer not null default 1,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (entity_kind, internal_id),
    constraint uk_public_entity_reference unique (public_reference),
    constraint chk_public_entity_reference_length check (char_length(public_reference) = 11)
);
