-- Existing numeric/QR hashes remain usable. Only new managed credentials are recoverable by their owner.
alter table relay_pickup_codes add column customer_delivery_version integer;

alter table relay_custody_events drop constraint chk_relay_custody_events_type;
alter table relay_custody_events add constraint chk_relay_custody_events_type
    check (event_type in ('DEPOSIT','PICKUP','RELAY_RELEASE','SEQUO_COLLECTION','RETURN_DROPOFF','PROBLEM','DELAY','REVIEW'));
