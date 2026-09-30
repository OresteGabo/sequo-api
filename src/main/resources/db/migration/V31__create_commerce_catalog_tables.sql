alter table products
    add column if not exists detail varchar(500);

alter table products
    add column if not exists option_hint varchar(240);

alter table products
    add column if not exists subcategory varchar(128);

alter table products
    add column if not exists original_price_cfa integer;

alter table products
    add column if not exists bargaining_enabled boolean not null default false;

alter table products
    add column if not exists bargain_floor_cfa integer;

alter table products
    add column if not exists camera_verified boolean not null default false;

alter table products
    add column if not exists captured_at_label varchar(120);

alter table products
    add column if not exists sort_order integer not null default 0;

alter table products
    add constraint chk_products_original_price
    check (original_price_cfa is null or base_price_cfa is null or original_price_cfa >= base_price_cfa);

alter table products
    add constraint chk_products_bargain_floor
    check (bargain_floor_cfa is null or bargain_floor_cfa >= 0);

create table catalog_categories (
    category_key varchar(64) primary key,
    title varchar(120) not null,
    support_label varchar(160) not null,
    accent_hex varchar(16) not null,
    sort_order integer not null,
    active boolean not null default true,
    created_at timestamp with time zone not null default current_timestamp
);

create table merchant_storefronts (
    merchant_id varchar(255) primary key,
    area varchar(160),
    kind varchar(160),
    distance_km double precision,
    eta varchar(80),
    photo_status varchar(120),
    open_status varchar(120),
    rating varchar(24),
    consolidation varchar(160),
    active boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_merchant_storefronts_merchant foreign key (merchant_id) references merchants(id),
    constraint chk_merchant_storefronts_distance check (distance_km is null or distance_km >= 0)
);

create table catalog_promotions (
    id varchar(128) primary key,
    headline varchar(160) not null,
    title varchar(180) not null,
    subtitle varchar(260) not null,
    product_id varchar(255) not null,
    starts_at timestamp with time zone,
    ends_at timestamp with time zone,
    sort_order integer not null,
    active boolean not null default true,
    created_at timestamp with time zone not null default current_timestamp,
    constraint fk_catalog_promotions_product foreign key (product_id) references products(id),
    constraint chk_catalog_promotions_window check (ends_at is null or starts_at is null or ends_at > starts_at)
);

create table customer_cart_items (
    id varchar(128) primary key,
    user_id varchar(128) not null,
    product_id varchar(255) not null,
    quantity integer not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_customer_cart_items_user foreign key (user_id) references users(id),
    constraint fk_customer_cart_items_product foreign key (product_id) references products(id),
    constraint chk_customer_cart_items_quantity check (quantity > 0 and quantity <= 99)
);

create table bargaining_threads (
    id varchar(128) primary key,
    customer_id varchar(128) not null,
    merchant_id varchar(255) not null,
    product_id varchar(255) not null,
    status varchar(64) not null,
    opened_at timestamp with time zone not null default current_timestamp,
    closed_at timestamp with time zone,
    expires_at timestamp with time zone,
    last_offer_cfa integer,
    accepted_price_cfa integer,
    version bigint not null default 0,
    constraint fk_bargaining_threads_customer foreign key (customer_id) references users(id),
    constraint fk_bargaining_threads_merchant foreign key (merchant_id) references merchants(id),
    constraint fk_bargaining_threads_product foreign key (product_id) references products(id),
    constraint chk_bargaining_threads_status check (status in ('OPEN', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    constraint chk_bargaining_threads_prices check (
        (last_offer_cfa is null or last_offer_cfa >= 0)
        and (accepted_price_cfa is null or accepted_price_cfa >= 0)
    )
);

create table bargaining_offers (
    id varchar(128) primary key,
    thread_id varchar(128) not null,
    actor_user_id varchar(128) not null,
    actor_type varchar(64) not null,
    offer_type varchar(64) not null,
    amount_cfa integer,
    message varchar(500),
    created_at timestamp with time zone not null default current_timestamp,
    constraint fk_bargaining_offers_thread foreign key (thread_id) references bargaining_threads(id),
    constraint fk_bargaining_offers_actor foreign key (actor_user_id) references users(id),
    constraint chk_bargaining_offers_actor_type check (actor_type in ('CUSTOMER', 'MERCHANT', 'SUPPORT')),
    constraint chk_bargaining_offers_type check (offer_type in ('OFFER', 'COUNTER_OFFER', 'ACCEPT', 'REJECT', 'MESSAGE')),
    constraint chk_bargaining_offers_amount check (amount_cfa is null or amount_cfa >= 0)
);

create unique index ux_customer_cart_items_user_product on customer_cart_items(user_id, product_id);
create index idx_products_category_status_sort on products(category, status, sort_order, created_at desc);
create index idx_products_subcategory_status on products(subcategory, status, sort_order);
create index idx_merchant_storefronts_active_sort on merchant_storefronts(active, sort_order);
create index idx_catalog_promotions_active_sort on catalog_promotions(active, sort_order);
create index idx_bargaining_threads_customer_status on bargaining_threads(customer_id, status, opened_at desc);
create index idx_bargaining_threads_merchant_status on bargaining_threads(merchant_id, status, opened_at desc);

insert into catalog_categories (category_key, title, support_label, accent_hex, sort_order) values
('food', 'Food', 'Hot meals', '#E2693D', 10),
('grocery', 'Grocery', 'Fresh & pantry', '#1B8A5A', 20),
('fashion', 'Fashion', 'Clothes & shoes', '#7B5EA7', 30),
('electronics', 'Electronics', 'Phones & tech', '#4D6F9E', 40),
('pharmacy', 'Pharmacy', 'Care items', '#6C7E51', 50),
('home', 'Home & baby', 'Daily basics', '#A77A41', 60);

insert into merchants (id, name, status, commission_rate_bps) values
('merchant-chez-ramatou', 'Chez Ramatou Attieke', 'ACTIVE', 1500),
('merchant-grand-marche-assigame', 'Grand Marche Assigame', 'ACTIVE', 1500),
('merchant-hedzranawoe-electronics', 'Hedzranawoe Electronics', 'ACTIVE', 1500),
('merchant-pharmacie-du-golfe', 'Pharmacie du Golfe', 'ACTIVE', 1500),
('merchant-tokoin-urban-wear', 'Tokoin Urban Wear', 'ACTIVE', 1500),
('merchant-ablogame-grocery', 'Ablogame Grocery', 'ACTIVE', 1500);

insert into merchant_storefronts (
    merchant_id, area, kind, distance_km, eta, photo_status, open_status, rating, consolidation, sort_order
) values
('merchant-chez-ramatou', 'Tokoin Gbadago', 'Food now', 1.8, '22 min', 'Hot meals', 'Open until 22:30', '4.8', 'Packed warm', 10),
('merchant-grand-marche-assigame', 'Assigame', 'Market cooperative', 3.1, '45 min', 'Fresh picks', 'Open now', '4.6', 'Grouped order', 20),
('merchant-hedzranawoe-electronics', 'Hedzranawoe', 'Electronics & phones', 5.6, 'Tomorrow', 'Phones & laptops', 'Ships today', '4.7', 'Sealed box', 30),
('merchant-pharmacie-du-golfe', 'Be-Kpota', 'Pharmacy & care', 4.7, '35 min', 'Care items', 'Open until 23:00', '4.7', 'Care items sealed', 40),
('merchant-tokoin-urban-wear', 'Tokoin', 'Fashion clothes', 2.3, '35 min', 'Clothes', 'Open now', '4.5', 'Folded pack', 50),
('merchant-ablogame-grocery', 'Ablogame', 'Grocery pantry', 5.9, 'Today 19:00', 'Pantry', 'Open until 21:00', '4.6', 'Heavy bag', 60);

insert into products (
    id, merchant_id, name, kind, status, category, subcategory, detail, base_price_cfa, option_hint,
    original_price_cfa, bargaining_enabled, bargain_floor_cfa, camera_verified, captured_at_label, sort_order
) values
('attieke-poisson-braise', 'merchant-chez-ramatou', 'Attieke poisson braise', 'SellerSpecific', 'ACTIVE', 'food', null, 'Choose fish size, piment, onion, alloco', 4200, 'Medium fish / piment doux / extra onion', null, false, null, false, null, 10),
('riz-gras-poulet', 'merchant-chez-ramatou', 'Riz gras poulet', 'SellerSpecific', 'ACTIVE', 'food', null, 'Sauce tomate, fried plantain, cold bissap', 3300, 'No pepper / add bissap', null, false, null, false, null, 20),
('pagne-wax-6-yards', 'merchant-grand-marche-assigame', 'Pagne wax 6 yards', 'SellerSpecific', 'ACTIVE', 'grocery', null, 'Pattern preview, bargain available', 17500, 'Historical minimum: 15 000 CFA', null, true, 15000, false, null, 10),
('tomato-onion-basket', 'merchant-grand-marche-assigame', 'Tomato and onion basket', 'SellerSpecific', 'ACTIVE', 'grocery', 'Produce', 'Fresh produce inspected at pickup', 5200, 'Family basket / today harvest', null, false, null, false, null, 20),
('iphone-15-rose', 'merchant-hedzranawoe-electronics', 'iPhone 15 rose', 'SellerSpecific', 'ACTIVE', 'electronics', 'Phones', '128 GB, clean finish', 420000, 'Pink / sealed box', null, false, null, false, null, 10),
('hp-laptop-15', 'merchant-hedzranawoe-electronics', 'HP laptop 15', 'SellerSpecific', 'ACTIVE', 'electronics', 'Laptops', 'Everyday Windows laptop', 245000, '8 GB RAM / 256 GB SSD', null, true, 230000, false, null, 20),
('hydrafizz-fraise', 'merchant-pharmacie-du-golfe', 'HydraFizz fraise', 'GenericSealedItem', 'ACTIVE', 'pharmacy', 'Care', 'Effervescent hydration tube', 4800, 'Strawberry / 16 tablets', null, false, null, false, null, 10),
('paracetamol-500-mg', 'merchant-pharmacie-du-golfe', 'Paracetamol 500 mg', 'GenericSealedItem', 'ACTIVE', 'pharmacy', 'Care', 'Pain and fever box', 1200, '16 tablets / sealed box', null, false, null, false, null, 20),
('graphic-black-tee', 'merchant-tokoin-urban-wear', 'Graphic black tee', 'SellerSpecific', 'ACTIVE', 'fashion', 'Clothes', 'Printed cotton shirt', 8500, 'Size M / black', null, false, null, false, null, 10),
('blue-office-shirt', 'merchant-tokoin-urban-wear', 'Blue office shirt', 'SellerSpecific', 'ACTIVE', 'fashion', 'Clothes', 'Long-sleeve shirt', 14500, 'Size L / blue', null, false, null, false, null, 20),
('lait-frais-1l', 'merchant-ablogame-grocery', 'Lait frais 1L', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Pantry', 'Fresh milk bottle', 1900, '1L / chilled', 2300, false, null, true, 'Taken today', 10),
('noix-de-coco', 'merchant-ablogame-grocery', 'Noix de coco', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Produce', 'Fresh coconut', 1200, '1 piece / fresh', null, false, null, true, 'Taken today', 20);

insert into catalog_promotions (id, headline, title, subtitle, product_id, sort_order) values
('promo-hot-food', 'Ready now', 'Attieke poisson braise', 'Warm meal from Chez Ramatou Attieke', 'attieke-poisson-braise', 10),
('promo-bargain-market', 'Bargain available', 'Pagne wax 6 yards', 'Try an offer before checkout', 'pagne-wax-6-yards', 20),
('promo-fresh-grocery', 'Camera verified', 'Lait frais 1L', 'Fresh item photographed today', 'lait-frais-1l', 30);
