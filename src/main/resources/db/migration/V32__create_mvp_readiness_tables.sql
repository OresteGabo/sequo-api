-- ============================================================================
-- MVP readiness tables for contract coverage.
-- These tables expose real API surfaces now while keeping external integrations
-- such as mobile money providers configurable until production credentials exist.
-- ============================================================================

create table customer_profiles (
    user_id varchar(128) primary key,
    display_name varchar(180) not null,
    phone_number varchar(64),
    receipt_email varchar(254),
    default_address_id varchar(128),
    marketing_opt_in boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_customer_profiles_user foreign key (user_id) references users(id)
);

create table customer_addresses (
    id varchar(128) primary key,
    user_id varchar(128) not null,
    kind varchar(64) not null,
    label varchar(180) not null,
    recipient_name varchar(180) not null,
    phone_number varchar(64) not null,
    city varchar(120) not null,
    area varchar(160),
    street_hint varchar(240),
    latitude double precision,
    longitude double precision,
    active boolean not null default true,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_customer_addresses_user foreign key (user_id) references users(id),
    constraint chk_customer_addresses_kind check (kind in ('HOME', 'WORK', 'RELAY', 'OTHER')),
    constraint chk_customer_addresses_coordinates check (
        (latitude is null or latitude between -90 and 90)
        and (longitude is null or longitude between -180 and 180)
    )
);

create table subscription_plans (
    id varchar(128) primary key,
    name varchar(120) not null,
    monthly_price_cfa integer not null,
    delivery_discount_percent integer not null,
    monthly_discount_cap_cfa integer,
    loyalty_months_threshold integer not null default 0,
    loyalty_discount_percent integer not null default 0,
    active boolean not null default true,
    created_at timestamp with time zone not null default current_timestamp,
    constraint chk_subscription_plans_amounts check (
        monthly_price_cfa >= 0
        and delivery_discount_percent between 0 and 100
        and (monthly_discount_cap_cfa is null or monthly_discount_cap_cfa >= 0)
        and loyalty_months_threshold >= 0
        and loyalty_discount_percent between 0 and 100
    )
);

create table customer_subscriptions (
    id varchar(128) primary key,
    user_id varchar(128) not null,
    plan_id varchar(128) not null,
    status varchar(64) not null,
    started_at timestamp with time zone not null default current_timestamp,
    current_period_ends_at timestamp with time zone not null,
    cancel_at_period_end boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint fk_customer_subscriptions_user foreign key (user_id) references users(id),
    constraint fk_customer_subscriptions_plan foreign key (plan_id) references subscription_plans(id),
    constraint chk_customer_subscriptions_status check (status in ('ACTIVE', 'PAUSED', 'CANCELLED', 'EXPIRED')),
    constraint chk_customer_subscriptions_period check (current_period_ends_at > started_at)
);

create table referral_delivery_credits (
    id varchar(128) primary key,
    referrer_user_id varchar(128) not null,
    referred_user_id varchar(128) not null,
    credit_cfa integer not null,
    status varchar(64) not null,
    source_code varchar(64),
    created_at timestamp with time zone not null default current_timestamp,
    expires_at timestamp with time zone,
    constraint fk_referral_delivery_credits_referrer foreign key (referrer_user_id) references users(id),
    constraint fk_referral_delivery_credits_referred foreign key (referred_user_id) references users(id),
    constraint chk_referral_delivery_credits_status check (status in ('RESERVED', 'AVAILABLE', 'APPLIED', 'EXPIRED', 'CANCELLED')),
    constraint chk_referral_delivery_credits_amount check (credit_cfa >= 0),
    constraint chk_referral_delivery_credits_not_self check (referrer_user_id <> referred_user_id)
);

create table payment_provider_configurations (
    provider_id varchar(64) primary key,
    display_name varchar(120) not null,
    country_code varchar(8) not null,
    readiness varchar(64) not null,
    customer_checkout_enabled boolean not null default false,
    refund_enabled boolean not null default false,
    courier_payout_enabled boolean not null default false,
    notes varchar(500),
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint chk_payment_provider_configurations_readiness check (readiness in ('PLANNED', 'SANDBOX_READY', 'LIVE_READY', 'DISABLED'))
);

create table product_customization_groups (
    id varchar(128) primary key,
    product_id varchar(255) not null,
    name varchar(120) not null,
    min_choices integer not null default 0,
    max_choices integer not null default 1,
    sort_order integer not null default 0,
    active boolean not null default true,
    constraint fk_product_customization_groups_product foreign key (product_id) references products(id),
    constraint chk_product_customization_groups_choices check (min_choices >= 0 and max_choices >= min_choices)
);

create table product_customization_options (
    id varchar(128) primary key,
    group_id varchar(128) not null,
    name varchar(140) not null,
    price_delta_cfa integer not null default 0,
    sort_order integer not null default 0,
    active boolean not null default true,
    constraint fk_product_customization_options_group foreign key (group_id) references product_customization_groups(id),
    constraint chk_product_customization_options_price_delta check (price_delta_cfa >= 0)
);

create table merchant_cooperatives (
    id varchar(128) primary key,
    code varchar(120) not null,
    name varchar(180) not null,
    city varchar(120) not null,
    area varchar(160),
    status varchar(64) not null,
    reviewed_by_user_id varchar(128),
    reviewed_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    version bigint not null default 0,
    constraint ux_merchant_cooperatives_code unique (code),
    constraint fk_merchant_cooperatives_reviewer foreign key (reviewed_by_user_id) references users(id),
    constraint chk_merchant_cooperatives_status check (status in ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED'))
);

create table merchant_cooperative_members (
    id varchar(128) primary key,
    cooperative_id varchar(128) not null,
    merchant_id varchar(255) not null,
    role varchar(64) not null,
    created_at timestamp with time zone not null default current_timestamp,
    constraint fk_merchant_cooperative_members_cooperative foreign key (cooperative_id) references merchant_cooperatives(id),
    constraint fk_merchant_cooperative_members_merchant foreign key (merchant_id) references merchants(id),
    constraint chk_merchant_cooperative_members_role check (role in ('OWNER', 'MEMBER'))
);

create index idx_customer_addresses_user_active on customer_addresses(user_id, active, updated_at desc);
create index idx_customer_subscriptions_user_created on customer_subscriptions(user_id, created_at desc);
create index idx_referral_delivery_credits_referrer on referral_delivery_credits(referrer_user_id, created_at desc);
create index idx_referral_delivery_credits_referred on referral_delivery_credits(referred_user_id, created_at desc);
create index idx_product_customization_groups_product on product_customization_groups(product_id, sort_order);
create index idx_product_customization_options_group on product_customization_options(group_id, sort_order);
create unique index ux_merchant_cooperative_members_unique on merchant_cooperative_members(cooperative_id, merchant_id);

insert into subscription_plans (
    id, name, monthly_price_cfa, delivery_discount_percent, monthly_discount_cap_cfa,
    loyalty_months_threshold, loyalty_discount_percent, active
) values
('subscriber-basic', 'Sequo Basic', 2500, 10, 3000, 6, 5, true),
('subscriber-plus', 'Sequo Plus', 5000, 20, 7500, 6, 8, true);

insert into payment_provider_configurations (
    provider_id, display_name, country_code, readiness, customer_checkout_enabled,
    refund_enabled, courier_payout_enabled, notes
) values
('yas_togo', 'YAS Togo', 'TG', 'PLANNED', false, false, false, 'Contract MVP requires preparation; live credentials and provider API access are not wired yet.'),
('moov_africa', 'Moov Africa', 'TG', 'PLANNED', false, false, false, 'Contract MVP requires preparation; live credentials and provider API access are not wired yet.');

insert into product_customization_groups (id, product_id, name, min_choices, max_choices, sort_order, active) values
('custom-attieke-piment', 'attieke-poisson-braise', 'Piment', 0, 1, 10, true),
('custom-attieke-side', 'attieke-poisson-braise', 'Supplements', 0, 3, 20, true),
('custom-riz-drink', 'riz-gras-poulet', 'Boisson', 0, 1, 10, true);

insert into product_customization_options (id, group_id, name, price_delta_cfa, sort_order, active) values
('custom-attieke-piment-none', 'custom-attieke-piment', 'Sans piment', 0, 10, true),
('custom-attieke-piment-soft', 'custom-attieke-piment', 'Piment doux', 0, 20, true),
('custom-attieke-piment-hot', 'custom-attieke-piment', 'Piment fort', 0, 30, true),
('custom-attieke-alloco', 'custom-attieke-side', 'Alloco', 500, 10, true),
('custom-attieke-onion', 'custom-attieke-side', 'Extra oignon', 300, 20, true),
('custom-riz-bissap', 'custom-riz-drink', 'Bissap frais', 500, 10, true);

insert into merchant_cooperatives (id, code, name, city, area, status) values
('coop-assigame-market', 'ASSIGAME-MARKET', 'Cooperative Assigame Market', 'Lome', 'Assigame', 'APPROVED');

insert into merchant_cooperative_members (id, cooperative_id, merchant_id, role) values
('coop-assigame-market-owner', 'coop-assigame-market', 'merchant-grand-marche-assigame', 'OWNER'),
('coop-assigame-market-grocery', 'coop-assigame-market', 'merchant-ablogame-grocery', 'MEMBER');
