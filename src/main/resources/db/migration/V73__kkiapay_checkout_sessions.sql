CREATE TABLE kkiapay_checkout_sessions (
 id VARCHAR(255) PRIMARY KEY,
 checkout_id VARCHAR(255) NOT NULL UNIQUE REFERENCES pending_payment_checkouts(checkout_id),
 sandbox BOOLEAN NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 transaction_id VARCHAR(255) UNIQUE
);
INSERT INTO payment_provider_configurations(provider_id,display_name,country_code,readiness,customer_checkout_enabled,refund_enabled,courier_payout_enabled,notes,updated_at,version)
VALUES ('kkiapay','KKiaPay','TG','PLANNED',FALSE,FALSE,FALSE,'Yas Togo and Moov Africa Togo mobile money only. Disabled until credentials, merchant activation and verification.',CURRENT_TIMESTAMP,0);
