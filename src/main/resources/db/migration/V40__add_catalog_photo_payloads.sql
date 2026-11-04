alter table products add column photo_required boolean not null default false;
alter table products add column image_url varchar(500);

create table catalog_camera_payloads (
    product_id varchar(255) primary key references products(id) on delete cascade,
    jpeg bytea not null
);
