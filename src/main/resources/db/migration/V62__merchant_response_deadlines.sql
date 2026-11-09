-- Intentionally no backfill: existing paid orders retain their existing warning-only SLA.
alter table merchant_sub_orders add column alert_until_at timestamp with time zone;
alter table merchant_sub_orders add column response_deadline_at timestamp with time zone;
alter table merchant_sub_orders add column response_expired_at timestamp with time zone;
alter table merchant_sub_orders add column response_disposition varchar(64);
create index idx_merchant_response_deadline on merchant_sub_orders(status, response_deadline_at);

-- Preserve all supported lifecycle history types while admitting the two new audited responses.
alter table order_events drop constraint chk_order_events_type;
alter table order_events add constraint chk_order_events_type check (event_type in (
    'ACCEPTED_FOR_FULFILLMENT', 'DELIVERED', 'RETURN_WINDOW_OPENED',
    'MERCHANT_RESPONSE_EXPIRED', 'MERCHANT_RESPONSE_REJECTED'
));
