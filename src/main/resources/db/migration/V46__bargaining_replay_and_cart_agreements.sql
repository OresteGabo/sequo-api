alter table bargaining_threads drop constraint chk_bargaining_threads_status;
alter table bargaining_threads add constraint chk_bargaining_threads_status check (status in (
 'OPEN','ACCEPTED','REJECTED','EXPIRED','CANCELLED','LOCKED_ATTEMPTS_EXHAUSTED'));
alter table bargaining_offers add column idempotency_key varchar(128);
alter table bargaining_offers add column request_fingerprint varchar(64);
create unique index ux_bargaining_offer_actor_retry on bargaining_offers(actor_user_id,idempotency_key);
create index idx_bargaining_scope on bargaining_threads(customer_id,merchant_id,product_id,opened_at);
alter table customer_cart_items add column bargaining_thread_id varchar(128) references bargaining_threads(id);
