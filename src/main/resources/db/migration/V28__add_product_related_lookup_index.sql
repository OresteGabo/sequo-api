create table if not exists products (
    id varchar(255) not null,
    merchant_id varchar(255),
    name varchar(255) not null,
    kind varchar(64) not null,
    status varchar(64) not null,
    category varchar(128),
    base_price_cfa integer,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table products
    add constraint chk_products_kind
    check (kind in (
        'GenericSealedItem',
        'SellerSpecific',
        'PreparedFood'
    ));

alter table products
    add constraint chk_products_status
    check (status in (
        'ACTIVE',
        'INACTIVE',
        'ARCHIVED'
    ));

alter table products
    add constraint chk_products_price_nonnegative
    check (base_price_cfa is null or base_price_cfa >= 0);

create index if not exists idx_products_active_category_created_at
    on products (category, status, created_at desc);

create index if not exists idx_products_merchant_status
    on products (merchant_id, status, created_at desc);

create table if not exists catalog_images (
    id varchar(255) not null,
    product_id varchar(255) not null,
    storage_key varchar(255) not null,
    public_url varchar(255) not null,
    source varchar(64) not null,
    moderation_status varchar(64) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    primary key (id)
);

alter table catalog_images
    add constraint fk_catalog_images_product
    foreign key (product_id) references products (id);

alter table catalog_images
    add constraint chk_catalog_images_source
    check (source in (
        'SellerLiveCapture',
        'SupplierReference',
        'SequoManaged'
    ));

alter table catalog_images
    add constraint chk_catalog_images_moderation_status
    check (moderation_status in (
        'NeedsReview',
        'Approved',
        'Rejected'
    ));

create index if not exists idx_catalog_images_product_status
    on catalog_images (product_id, moderation_status, created_at desc);
