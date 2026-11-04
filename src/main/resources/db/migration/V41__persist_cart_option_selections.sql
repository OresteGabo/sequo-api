alter table customer_cart_items
    add column configuration_key varchar(64) default '' not null;

alter table customer_cart_items
    add column selections_json text default '[]' not null;

drop index ux_customer_cart_items_user_product;

create unique index ux_customer_cart_items_configuration
    on customer_cart_items(user_id, product_id, configuration_key);
