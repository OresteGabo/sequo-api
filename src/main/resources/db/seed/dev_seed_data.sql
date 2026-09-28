-- Development seed data for Sequo API.
-- Run manually against a migrated PostgreSQL database:
--   psql "$SPRING_DATASOURCE_URL" -f src/main/resources/db/seed/dev_seed_data.sql
--
-- This file is intentionally outside db/migration so it does not run in production automatically.

begin;

insert into users (id, email, password_hash, name, provider, status, provider_id)
values
  ('seed-customer-1', 'customer.seed@sequo.test', null, 'Afi Customer', 'EMAIL', 'ACTIVE', null),
  ('seed-merchant-owner-1', 'merchant.owner.seed@sequo.test', null, 'Kossi Merchant', 'EMAIL', 'ACTIVE', null),
  ('seed-courier-user-1', 'courier.seed@sequo.test', null, 'Ama Courier', 'EMAIL', 'ACTIVE', null),
  ('seed-relay-user-1', 'relay.seed@sequo.test', null, 'Yao Relay Operator', 'EMAIL', 'ACTIVE', null),
  ('seed-support-1', 'support.seed@sequo.test', null, 'Sequo Support', 'EMAIL', 'ACTIVE', null),
  ('seed-admin-1', 'admin.seed@sequo.test', null, 'Sequo Admin', 'EMAIL', 'ACTIVE', null),
  ('seed-google-user-1', 'google.seed@sequo.test', null, 'Google Seed User', 'GOOGLE', 'ACTIVE', 'google-seed-subject-1')
on conflict (id) do nothing;

insert into user_roles (user_id, role_code)
values
  ('seed-customer-1', 'CUSTOMER'),
  ('seed-merchant-owner-1', 'CUSTOMER'),
  ('seed-merchant-owner-1', 'MERCHANT_OWNER'),
  ('seed-courier-user-1', 'CUSTOMER'),
  ('seed-courier-user-1', 'COURIER'),
  ('seed-relay-user-1', 'CUSTOMER'),
  ('seed-relay-user-1', 'RELAY_PARTNER'),
  ('seed-support-1', 'SUPPORT_AGENT'),
  ('seed-admin-1', 'ADMIN'),
  ('seed-google-user-1', 'CUSTOMER')
on conflict do nothing;

insert into social_identities (id, user_id, provider, provider_subject, verified_email, created_at, last_login_at)
values (
  'seed-social-google-1',
  'seed-google-user-1',
  'GOOGLE',
  'google-seed-subject-1',
  'google.seed@sequo.test',
  now() - interval '5 days',
  now() - interval '1 hour'
)
on conflict (provider, provider_subject) do nothing;

insert into merchants (id, owner_user_id, name, status, commission_rate_bps, wallet_provider, wallet_account_ref, created_at, updated_at, version)
values
  ('seed-merchant-1', 'seed-merchant-owner-1', 'Chez Afi Market', 'ACTIVE', 1200, 'YAS_TOGO', 'yas-merchant-001', now() - interval '20 days', now() - interval '1 hour', 0),
  ('seed-merchant-2', 'seed-merchant-owner-1', 'Sequo Fresh Demo', 'ACTIVE', 1000, 'MOOV_AFRICA', 'moov-merchant-002', now() - interval '15 days', now() - interval '2 hours', 0)
on conflict (id) do nothing;

insert into couriers (id, user_id, status, workforce_type, vehicle_type, wallet_provider, wallet_account_ref, created_at, updated_at, version)
values (
  'seed-courier-1',
  'seed-courier-user-1',
  'ACTIVE',
  'FREELANCER',
  'MOTO',
  'YAS_TOGO',
  'yas-courier-001',
  now() - interval '10 days',
  now() - interval '30 minutes',
  0
)
on conflict (id) do nothing;

insert into relay_points (id, operator_user_id, name, status, city, neighborhood, landmark, wallet_provider, wallet_account_ref, created_at, updated_at, version)
values (
  'seed-relay-1',
  'seed-relay-user-1',
  'Relay Agoe Demo',
  'ACTIVE',
  'Lome',
  'Agoe',
  'Near the main pharmacy',
  'MOOV_AFRICA',
  'moov-relay-001',
  now() - interval '12 days',
  now() - interval '25 minutes',
  0
)
on conflict (id) do nothing;

insert into merchant_memberships (id, user_id, merchant_id, role_code, active, created_at)
values (
  'seed-membership-merchant-owner-1',
  'seed-merchant-owner-1',
  'seed-merchant-1',
  'MERCHANT_OWNER',
  true,
  now() - interval '20 days'
)
on conflict do nothing;

insert into courier_availability_states (courier_id, status, paused_reason, paused_by, paused_at, paused_until, updated_at)
values ('seed-courier-1', 'ACTIVE', null, null, null, null, now() - interval '20 minutes')
on conflict (courier_id) do nothing;

insert into customer_orders (
  id,
  checkout_id,
  customer_id,
  service_level,
  route,
  fulfillment_priority,
  requires_consolidation,
  customer_facing_status,
  item_subtotal_cfa,
  delivery_fee_cfa,
  total_cfa,
  payment_provider,
  payment_reference,
  provider_reference,
  payment_status,
  order_status,
  delivered_at,
  return_window_ends_at,
  created_at,
  updated_at,
  version
)
values (
  'seed-order-1',
  'seed-checkout-1',
  'seed-customer-1',
  'Regular',
  'FastDelivery',
  'Standard',
  false,
  'IN_TRANSIT',
  9000,
  1200,
  10200,
  'yas_togo',
  'PAY-SEED-001',
  'YAS-SEED-001',
  'Validated',
  'ACCEPTED_FOR_FULFILLMENT',
  null,
  null,
  now() - interval '2 days',
  now() - interval '1 hour',
  0
)
on conflict (id) do nothing;

insert into customer_order_lines (
  id,
  order_id,
  product_id,
  seller_id,
  seller_name,
  product_name,
  category,
  quantity,
  unit_price_cfa,
  negotiated_unit_price_cfa,
  effective_unit_price_cfa,
  line_total_cfa,
  photo_evidence_type,
  line_index,
  created_at
)
values (
  'seed-order-line-1',
  'seed-order-1',
  'seed-product-1',
  'seed-merchant-1',
  'Chez Afi Market',
  'Demo Rice Bag 5kg',
  'GeneralGoods',
  1,
  9000,
  null,
  9000,
  9000,
  'LiveCameraCapture',
  0,
  now() - interval '2 days'
)
on conflict (id) do nothing;

insert into merchant_sub_orders (
  id,
  sub_order_code,
  order_id,
  merchant_id,
  status,
  item_subtotal_cfa,
  commission_rate_bps,
  commission_cfa,
  merchant_net_cfa,
  package_count,
  accepted_at,
  preparing_at,
  packed_ready_at,
  handed_to_courier_at,
  rejection_reason,
  seller_response_due_at,
  packing_due_at,
  created_at,
  updated_at,
  version
)
values (
  'seed-sub-order-1',
  'SUB-SEED-001',
  'seed-order-1',
  'seed-merchant-1',
  'HANDED_TO_COURIER',
  9000,
  1200,
  1080,
  7920,
  1,
  now() - interval '2 days' + interval '10 minutes',
  now() - interval '2 days' + interval '20 minutes',
  now() - interval '2 days' + interval '50 minutes',
  now() - interval '2 days' + interval '70 minutes',
  null,
  now() - interval '2 days' + interval '30 minutes',
  now() - interval '2 days' + interval '90 minutes',
  now() - interval '2 days',
  now() - interval '1 hour',
  0
)
on conflict (id) do nothing;

insert into delivery_missions (
  id,
  delivery_code,
  order_id,
  merchant_sub_order_id,
  courier_id,
  delivery_mode,
  destination_type,
  status,
  customer_delivery_fee_cfa,
  courier_fee_cfa,
  shortfall_cfa,
  assigned_at,
  accepted_at,
  pickup_at,
  relay_deposited_at,
  delivered_at,
  pickup_proof_metadata,
  pickup_proof_actor_id,
  dropoff_proof_metadata,
  dropoff_proof_actor_id,
  relay_deposit_proof_metadata,
  relay_deposit_proof_actor_id,
  problem_metadata,
  created_at,
  updated_at,
  version
)
values (
  'seed-mission-1',
  'DLV-SEED-001',
  'seed-order-1',
  'seed-sub-order-1',
  'seed-courier-1',
  'STANDARD',
  'CUSTOMER_ADDRESS',
  'PICKED_UP_FROM_SELLER',
  1200,
  900,
  0,
  now() - interval '2 days' + interval '75 minutes',
  now() - interval '2 days' + interval '80 minutes',
  now() - interval '2 days' + interval '95 minutes',
  null,
  null,
  '{"seed":true,"event":"pickup"}',
  'seed-courier-user-1',
  null,
  null,
  null,
  null,
  null,
  now() - interval '2 days' + interval '75 minutes',
  now() - interval '1 hour',
  0
)
on conflict (id) do nothing;

insert into relay_lockers (
  id,
  relay_point_id,
  locker_code,
  status,
  availability_reason,
  expected_available_at,
  updated_by_user_id,
  relay_locker_grid_id,
  created_at,
  updated_at,
  version
)
values (
  'seed-locker-1',
  'seed-relay-1',
  'A01',
  'AVAILABLE',
  'OPERATOR_CONFIRMED_AVAILABLE',
  null,
  'seed-relay-user-1',
  'seed-relay-1',
  now() - interval '12 days',
  now() - interval '25 minutes',
  0
)
on conflict (id) do nothing;

insert into relay_parcels (
  id,
  relay_point_id,
  locker_id,
  order_id,
  delivery_mission_id,
  return_id,
  deposit_code,
  status,
  category,
  deposited_at,
  picked_up_at,
  collected_at,
  late_fee_started_at,
  return_to_seller_due_at,
  problem_metadata,
  created_at,
  updated_at,
  version
)
values (
  'seed-parcel-1',
  'seed-relay-1',
  'seed-locker-1',
  'seed-order-1',
  null,
  null,
  'DEP-SEED-001',
  'DEPOSITED',
  'GeneralGoods',
  now() - interval '8 hours',
  null,
  null,
  now() - interval '1 hour',
  now() + interval '2 days',
  null,
  now() - interval '8 hours',
  now() - interval '1 hour',
  0
)
on conflict (id) do nothing;

insert into relay_pickup_codes (
  id,
  relay_parcel_id,
  code_hash,
  qr_nonce_hash,
  identity_check_required,
  expires_at,
  used_at,
  attempt_count,
  created_at
)
values (
  'seed-pickup-code-1',
  'seed-parcel-1',
  'seed-hash-for-246810',
  null,
  true,
  now() + interval '1 day',
  null,
  0,
  now() - interval '8 hours'
)
on conflict (id) do nothing;

insert into relay_custody_events (
  id,
  relay_parcel_id,
  actor_user_id,
  event_type,
  metadata,
  idempotency_key,
  created_at
)
values (
  'seed-relay-event-1',
  'seed-parcel-1',
  'seed-relay-user-1',
  'Deposit',
  '{"seed":true,"source":"dev_seed_data"}',
  'seed-relay-deposit-1',
  now() - interval '8 hours'
)
on conflict (id) do nothing;

insert into merchant_payout_accruals (
  id,
  merchant_id,
  order_id,
  source_order_item_id,
  merchant_net_cfa,
  commission_cfa,
  platform_margin_cfa,
  package_received_at,
  payout_eligible_at,
  payout_due_by,
  status,
  workflow_type,
  active_return_hold,
  active_dispute_hold,
  created_at,
  updated_at,
  version
)
values (
  'seed-payout-1',
  'seed-merchant-1',
  'seed-order-1',
  'seed-sub-order-1',
  7920,
  1080,
  0,
  now() - interval '1 day',
  now() - interval '1 hour',
  now() + interval '6 days',
  'Accrued',
  'DeliveryConfirmed',
  false,
  false,
  now() - interval '1 day',
  now() - interval '1 hour',
  0
)
on conflict (id) do nothing;

insert into settlement_ledger_entries (
  id,
  account,
  direction,
  amount_cfa,
  merchant_id,
  courier_id,
  relay_point_id,
  source_type,
  source_id,
  description,
  created_at
)
values
  ('seed-ledger-1', 'MerchantPayable', 'Credit', 7920, 'seed-merchant-1', null, null, 'OrderItem', 'seed-sub-order-1', 'Seed merchant payout accrual', now() - interval '1 day'),
  ('seed-ledger-2', 'SequoCommissionRevenue', 'Credit', 1080, 'seed-merchant-1', null, null, 'OrderItem', 'seed-sub-order-1', 'Seed platform commission', now() - interval '1 day')
on conflict (id) do nothing;

insert into notification_preferences (
  id,
  user_id,
  app_family,
  event_type,
  push_enabled,
  in_app_enabled,
  sms_enabled,
  quiet_hours_start,
  quiet_hours_end,
  created_at,
  updated_at,
  version
)
values (
  'seed-pref-customer-1',
  'seed-customer-1',
  'SEQUO_CUSTOMER',
  'ALL',
  true,
  true,
  false,
  '22:00',
  '07:00',
  now() - interval '3 days',
  now() - interval '1 hour',
  0
)
on conflict (user_id, app_family, event_type) do nothing;

insert into notification_messages (
  id,
  event_id,
  recipient_user_id,
  app_family,
  event_type,
  severity,
  title,
  body,
  action_url,
  payload,
  read_at,
  archived_at,
  created_at
)
values (
  'seed-notification-1',
  'seed-event-order-1',
  'seed-customer-1',
  'SEQUO_CUSTOMER',
  'RIDER_PICKED_UP',
  'INFO',
  'Order picked up',
  'Your seed order is on the way.',
  '/orders/seed-order-1',
  '{"orderId":"seed-order-1"}',
  null,
  null,
  now() - interval '1 hour'
)
on conflict (event_id, recipient_user_id, app_family, event_type) do nothing;

insert into hub_opening_hours (
  id,
  relay_point_id,
  day_of_week,
  timezone,
  is_open,
  opens_at,
  closes_at,
  created_at,
  updated_at,
  version
)
values
  ('seed-hub-hours-mon', 'seed-relay-1', 'MONDAY', 'Africa/Lome', true, '08:00', '18:00', now() - interval '7 days', now() - interval '1 day', 0),
  ('seed-hub-hours-tue', 'seed-relay-1', 'TUESDAY', 'Africa/Lome', true, '08:00', '18:00', now() - interval '7 days', now() - interval '1 day', 0),
  ('seed-hub-hours-wed', 'seed-relay-1', 'WEDNESDAY', 'Africa/Lome', true, '08:00', '18:00', now() - interval '7 days', now() - interval '1 day', 0),
  ('seed-hub-hours-thu', 'seed-relay-1', 'THURSDAY', 'Africa/Lome', true, '08:00', '18:00', now() - interval '7 days', now() - interval '1 day', 0),
  ('seed-hub-hours-fri', 'seed-relay-1', 'FRIDAY', 'Africa/Lome', true, '08:00', '18:00', now() - interval '7 days', now() - interval '1 day', 0),
  ('seed-hub-hours-sat', 'seed-relay-1', 'SATURDAY', 'Africa/Lome', true, '09:00', '14:00', now() - interval '7 days', now() - interval '1 day', 0),
  ('seed-hub-hours-sun', 'seed-relay-1', 'SUNDAY', 'Africa/Lome', false, null, null, now() - interval '7 days', now() - interval '1 day', 0)
on conflict (relay_point_id, day_of_week) do nothing;

insert into user_app_preferences (
  id,
  user_id,
  app_family,
  theme,
  language,
  quick_scan_on_open,
  sound_feedback,
  large_locker_labels,
  created_at,
  updated_at,
  version
)
values (
  'seed-app-pref-hub-1',
  'seed-relay-user-1',
  'SEQUO_HUB',
  'SYSTEM',
  'fr',
  true,
  true,
  true,
  now() - interval '2 days',
  now() - interval '1 hour',
  0
)
on conflict (user_id, app_family) do nothing;

insert into merchant_commission_overrides (
  merchant_id,
  commission_rate_bps,
  reason,
  updated_by_user_id,
  created_at,
  updated_at
)
values (
  'seed-merchant-1',
  1200,
  'Seed override for Postman testing',
  'seed-admin-1',
  now() - interval '2 days',
  now() - interval '1 hour'
)
on conflict (merchant_id) do nothing;

do $$
begin
  if to_regclass('public.products') is not null then
    insert into products (id, merchant_id, name, kind, status, category, base_price_cfa, created_at, updated_at, version)
    values
      ('seed-product-1', 'seed-merchant-1', 'Demo Rice Bag 5kg', 'GenericSealedItem', 'ACTIVE', 'GeneralGoods', 9000, now() - interval '10 days', now() - interval '1 day', 0),
      ('seed-product-2', 'seed-merchant-1', 'Demo Grilled Chicken Plate', 'SellerSpecific', 'ACTIVE', 'Food', 3500, now() - interval '10 days', now() - interval '1 day', 0)
    on conflict (id) do nothing;
  end if;

  if to_regclass('public.catalog_images') is not null then
    insert into catalog_images (id, product_id, storage_key, public_url, source, moderation_status, created_at, updated_at, version)
    values (
      'seed-catalog-image-1',
      'seed-product-1',
      'seed/catalog/rice-5kg.jpg',
      'https://example.test/seed/catalog/rice-5kg.jpg',
      'GenericCatalogReference',
      'Approved',
      now() - interval '10 days',
      now() - interval '1 day',
      0
    )
    on conflict (id) do nothing;
  end if;
end $$;

commit;
