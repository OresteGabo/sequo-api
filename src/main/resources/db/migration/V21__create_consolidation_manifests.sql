create table consolidation_manifests (
    id varchar(255) not null,
    order_id varchar(255) not null,
    customer_id varchar(255) not null,
    seller_packages_json varchar(12000) not null,
    status varchar(64) not null,
    final_package_id varchar(255),
    sequo_custody_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    primary key (id),
    constraint uk_consolidation_manifests_order unique (order_id),
    constraint chk_consolidation_manifests_status check (status in ('AwaitingSellerPackages', 'ReadyForSequoPickup', 'InSequoCustody', 'Consolidated', 'Dispatched', 'Cancelled'))
);

create index idx_consolidation_manifests_status_updated
    on consolidation_manifests (status, updated_at);
