-- Existing records and nominal amounts remain; no historical entitlement is silently approved.
alter table subscription_plans add column approval_reference varchar(255);
alter table customer_subscriptions add column current_period_starts_at timestamp with time zone;
alter table customer_subscriptions add column activation_reference varchar(255);
alter table customer_subscriptions add column loyalty_tenure_verified boolean not null default false;
alter table customer_subscriptions drop constraint chk_customer_subscriptions_period;
alter table customer_subscriptions add constraint chk_customer_subscriptions_period check (
    current_period_ends_at > started_at or (status = 'PAUSED' and current_period_ends_at = started_at)
);
alter table referral_delivery_credits add column remaining_cfa integer;
update referral_delivery_credits set remaining_cfa = case when status in ('AVAILABLE','RESERVED') then credit_cfa else 0 end;
alter table referral_delivery_credits alter column remaining_cfa set not null;
alter table referral_delivery_credits add column approval_reference varchar(255);
alter table referral_delivery_credits add constraint chk_referral_remaining check (remaining_cfa >= 0 and remaining_cfa <= credit_cfa);

create table delivery_benefit_applications (
    checkout_id varchar(255) primary key references pending_payment_checkouts(checkout_id),
    customer_id varchar(255) not null references users(id),
    subscription_id varchar(255) references customer_subscriptions(id),
    period_starts_at timestamp with time zone,
    subscription_discount_cfa integer not null,
    loyalty_discount_cfa integer not null,
    credit_id varchar(255) references referral_delivery_credits(id),
    credit_applied_cfa integer not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    applied_at timestamp with time zone,
    released_at timestamp with time zone,
    constraint chk_delivery_benefit_status check (status in ('RESERVED','APPLIED','RELEASED')),
    constraint chk_delivery_benefit_amounts check (subscription_discount_cfa >= 0 and loyalty_discount_cfa >= 0 and credit_applied_cfa >= 0),
    constraint chk_delivery_benefit_credit check (credit_applied_cfa = 0 or credit_id is not null)
);
create index idx_delivery_benefit_customer on delivery_benefit_applications(customer_id);

alter table order_pricing_snapshots add column loyalty_discount_cfa integer not null default 0;
