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
      'SupplierReference',
      'Approved',
      now() - interval '10 days',
      now() - interval '1 day',
      0
    )
    on conflict (id) do nothing;
  end if;
end $$;

do $$
begin
  if to_regclass('public.catalog_categories') is not null then
    insert into catalog_categories (category_key, title, support_label, accent_hex, sort_order, active)
    values
      ('beauty', 'Beauty', 'Hair & cosmetics', '#B85C88', 70),
      ('bakery', 'Bakery', 'Bread & sweets', '#C7832B', 80)
    on conflict (category_key) do update set
      title = excluded.title,
      support_label = excluded.support_label,
      accent_hex = excluded.accent_hex,
      sort_order = excluded.sort_order,
      active = excluded.active;
  end if;

  insert into merchants (id, owner_user_id, name, status, commission_rate_bps, wallet_provider, wallet_account_ref, created_at, updated_at, version)
  values
    ('sim-merchant-bella-mama-kitchen', 'seed-merchant-owner-1', 'Bella Mama Kitchen', 'ACTIVE', 1400, 'YAS_TOGO', 'sim-yas-bella-mama', now() - interval '18 days', now() - interval '20 minutes', 0),
    ('sim-merchant-smoky-grill-house', 'seed-merchant-owner-1', 'Smoky Grill House', 'ACTIVE', 1500, 'MOOV_AFRICA', 'sim-moov-smoky-grill', now() - interval '17 days', now() - interval '20 minutes', 0),
    ('sim-merchant-green-bowl-cafe', 'seed-merchant-owner-1', 'Green Bowl Cafe', 'ACTIVE', 1300, 'YAS_TOGO', 'sim-yas-green-bowl', now() - interval '16 days', now() - interval '20 minutes', 0),
    ('sim-merchant-lome-fresh-market', 'seed-merchant-owner-1', 'Lome Fresh Market', 'ACTIVE', 1200, 'MOOV_AFRICA', 'sim-moov-lome-fresh', now() - interval '15 days', now() - interval '20 minutes', 0),
    ('sim-merchant-family-pantry', 'seed-merchant-owner-1', 'Family Pantry Plus', 'ACTIVE', 1100, 'YAS_TOGO', 'sim-yas-family-pantry', now() - interval '14 days', now() - interval '20 minutes', 0),
    ('sim-merchant-tech-corner', 'seed-merchant-owner-1', 'Tech Corner Lome', 'ACTIVE', 1600, 'MOOV_AFRICA', 'sim-moov-tech-corner', now() - interval '13 days', now() - interval '20 minutes', 0),
    ('sim-merchant-style-street', 'seed-merchant-owner-1', 'Style Street Boutique', 'ACTIVE', 1500, 'YAS_TOGO', 'sim-yas-style-street', now() - interval '12 days', now() - interval '20 minutes', 0),
    ('sim-merchant-careplus-pharmacy', 'seed-merchant-owner-1', 'CarePlus Pharmacy', 'ACTIVE', 1000, 'MOOV_AFRICA', 'sim-moov-careplus', now() - interval '11 days', now() - interval '20 minutes', 0),
    ('sim-merchant-home-nest', 'seed-merchant-owner-1', 'Home Nest Supplies', 'ACTIVE', 1250, 'YAS_TOGO', 'sim-yas-home-nest', now() - interval '10 days', now() - interval '20 minutes', 0),
    ('sim-merchant-sweet-crust-bakery', 'seed-merchant-owner-1', 'Sweet Crust Bakery', 'ACTIVE', 1350, 'MOOV_AFRICA', 'sim-moov-sweet-crust', now() - interval '9 days', now() - interval '20 minutes', 0)
  on conflict (id) do update set
    owner_user_id = excluded.owner_user_id,
    name = excluded.name,
    status = excluded.status,
    commission_rate_bps = excluded.commission_rate_bps,
    wallet_provider = excluded.wallet_provider,
    wallet_account_ref = excluded.wallet_account_ref,
    updated_at = excluded.updated_at;

  if to_regclass('public.merchant_memberships') is not null then
    insert into merchant_memberships (id, user_id, merchant_id, role_code, active, created_at)
    values
      ('sim-membership-bella-mama-kitchen', 'seed-merchant-owner-1', 'sim-merchant-bella-mama-kitchen', 'MERCHANT_OWNER', true, now() - interval '18 days'),
      ('sim-membership-smoky-grill-house', 'seed-merchant-owner-1', 'sim-merchant-smoky-grill-house', 'MERCHANT_OWNER', true, now() - interval '17 days'),
      ('sim-membership-green-bowl-cafe', 'seed-merchant-owner-1', 'sim-merchant-green-bowl-cafe', 'MERCHANT_OWNER', true, now() - interval '16 days'),
      ('sim-membership-lome-fresh-market', 'seed-merchant-owner-1', 'sim-merchant-lome-fresh-market', 'MERCHANT_OWNER', true, now() - interval '15 days'),
      ('sim-membership-family-pantry', 'seed-merchant-owner-1', 'sim-merchant-family-pantry', 'MERCHANT_OWNER', true, now() - interval '14 days'),
      ('sim-membership-tech-corner', 'seed-merchant-owner-1', 'sim-merchant-tech-corner', 'MERCHANT_OWNER', true, now() - interval '13 days'),
      ('sim-membership-style-street', 'seed-merchant-owner-1', 'sim-merchant-style-street', 'MERCHANT_OWNER', true, now() - interval '12 days'),
      ('sim-membership-careplus-pharmacy', 'seed-merchant-owner-1', 'sim-merchant-careplus-pharmacy', 'MERCHANT_OWNER', true, now() - interval '11 days'),
      ('sim-membership-home-nest', 'seed-merchant-owner-1', 'sim-merchant-home-nest', 'MERCHANT_OWNER', true, now() - interval '10 days'),
      ('sim-membership-sweet-crust-bakery', 'seed-merchant-owner-1', 'sim-merchant-sweet-crust-bakery', 'MERCHANT_OWNER', true, now() - interval '9 days')
    on conflict do nothing;
  end if;

  if to_regclass('public.merchant_storefronts') is not null then
    insert into merchant_storefronts (
      merchant_id, area, kind, distance_km, eta, photo_status, open_status, rating, consolidation, active, sort_order, updated_at
    )
    values
      ('sim-merchant-bella-mama-kitchen', 'Tokoin Habitat', 'Restaurant - local plates', 1.4, '20 min', 'Kitchen verified', 'Open until 22:00', '4.8', 'Packed warm', true, 110, now()),
      ('sim-merchant-smoky-grill-house', 'Agoe Cacaveli', 'Restaurant - grill', 3.8, '35 min', 'Grill station live', 'Open until 23:30', '4.7', 'Heat-sealed meal', true, 120, now()),
      ('sim-merchant-green-bowl-cafe', 'Be Kpota', 'Restaurant - healthy cafe', 2.6, '28 min', 'Fresh prep', 'Open until 21:00', '4.6', 'Cold items separated', true, 130, now()),
      ('sim-merchant-lome-fresh-market', 'Assigame', 'Fresh grocery', 3.2, '45 min', 'Camera verified produce', 'Open now', '4.7', 'Grouped market bag', true, 140, now()),
      ('sim-merchant-family-pantry', 'Ablogame', 'Pantry grocery', 5.5, 'Today 19:00', 'Shelf stock verified', 'Open until 21:00', '4.5', 'Heavy bag ready', true, 150, now()),
      ('sim-merchant-tech-corner', 'Hedzranawoe', 'Electronics & accessories', 5.9, 'Tomorrow', 'Box and serial checked', 'Ships today', '4.8', 'Sealed electronics pack', true, 160, now()),
      ('sim-merchant-style-street', 'Tokoin Forever', 'Fashion boutique', 2.1, '35 min', 'Size tags verified', 'Open now', '4.6', 'Folded boutique pack', true, 170, now()),
      ('sim-merchant-careplus-pharmacy', 'Be Kpota', 'Pharmacy & wellness', 4.4, '30 min', 'Batch checked', 'Open until 23:00', '4.9', 'Care items sealed', true, 180, now()),
      ('sim-merchant-home-nest', 'Adidogome', 'Home & baby supplies', 6.1, 'Today 18:30', 'Pack count verified', 'Open now', '4.5', 'Bulky item handling', true, 190, now()),
      ('sim-merchant-sweet-crust-bakery', 'Nyekonakpoe', 'Bakery & cafe', 2.9, '25 min', 'Baked today', 'Open until 20:30', '4.7', 'Boxed fresh', true, 200, now())
    on conflict (merchant_id) do update set
      area = excluded.area,
      kind = excluded.kind,
      distance_km = excluded.distance_km,
      eta = excluded.eta,
      photo_status = excluded.photo_status,
      open_status = excluded.open_status,
      rating = excluded.rating,
      consolidation = excluded.consolidation,
      active = excluded.active,
      sort_order = excluded.sort_order,
      updated_at = excluded.updated_at;
  end if;

  if to_regclass('public.products') is not null then
    with shop_catalog as (
      select * from (values
        ('sim-merchant-bella-mama-kitchen', 'bella-mama', 'food', 'Local plates', 'PreparedFood', 1800, 250, true, array[
          'Jollof rice chicken', 'Attieke grilled tilapia', 'Fufu palm nut soup', 'Waakye beef stew', 'Akume okra sauce',
          'Fried rice shrimp', 'Beans and plantain', 'Yam chips chicken', 'Banku pepper fish', 'Spaghetti omelette',
          'Rice and peanut sauce', 'Grilled guinea fowl', 'Vegetable couscous', 'Chicken yassa plate', 'Beef kebab plate',
          'Alloco egg plate', 'Tilapia light soup', 'Okra stew rice', 'Tomato stew rice', 'Family jollof bowl'
        ]::text[]),
        ('sim-merchant-smoky-grill-house', 'smoky-grill', 'food', 'Grill', 'PreparedFood', 2200, 300, true, array[
          'Charcoal chicken quarter', 'Charcoal chicken half', 'Beef brochette plate', 'Goat skewers plate', 'Grilled fish capitaine',
          'Pork ribs box', 'Spicy wings box', 'Kelewele chicken box', 'Suya beef wrap', 'Grilled sausage plate',
          'Chicken shawarma', 'Beef shawarma', 'Mixed grill platter', 'Pepper turkey plate', 'Garlic chicken box',
          'Fish and alloco', 'Grilled lamb chops', 'Smoky burger', 'Loaded fries beef', 'Family grill tray'
        ]::text[]),
        ('sim-merchant-green-bowl-cafe', 'green-bowl', 'food', 'Cafe bowls', 'PreparedFood', 1600, 220, true, array[
          'Avocado chicken salad', 'Tuna pasta bowl', 'Veggie quinoa bowl', 'Fruit yogurt parfait', 'Chicken wrap',
          'Beef salad bowl', 'Egg avocado toast', 'Granola banana bowl', 'Couscous veggie bowl', 'Greek salad cup',
          'Turkey club sandwich', 'Peanut smoothie bowl', 'Mango chia cup', 'Rice veggie bowl', 'Lemon chicken bowl',
          'Spicy tuna sandwich', 'Garden soup cup', 'Sweet potato bowl', 'Fresh fruit cup', 'Cafe lunch combo'
        ]::text[]),
        ('sim-merchant-lome-fresh-market', 'lome-fresh', 'grocery', 'Produce', 'GenericSealedItem', 500, 180, false, array[
          'Tomato basket 2kg', 'Onion basket 2kg', 'Fresh pepper pack', 'Carrot bunch', 'Cabbage head',
          'Lettuce bundle', 'Cucumber pack', 'Green beans pack', 'Eggplant pack', 'Okra basket',
          'Fresh ginger bag', 'Garlic net', 'Plantain bunch', 'Sweet banana hand', 'Pineapple piece',
          'Watermelon slice pack', 'Mango pack', 'Orange dozen', 'Avocado pack', 'Market soup bundle'
        ]::text[]),
        ('sim-merchant-family-pantry', 'family-pantry', 'grocery', 'Pantry', 'GenericSealedItem', 700, 350, false, array[
          'Rice bag 5kg', 'Local rice 10kg', 'Spaghetti pack', 'Macaroni pack', 'Corn flour 2kg',
          'Wheat flour 2kg', 'Sugar 1kg', 'Salt 1kg', 'Vegetable oil 1L', 'Palm oil bottle',
          'Tomato paste pack', 'Sardine tin', 'Corned beef tin', 'Evaporated milk pack', 'Powdered milk tin',
          'Tea sachet box', 'Instant coffee jar', 'Mayo jar', 'Peanut butter jar', 'Family pantry starter'
        ]::text[]),
        ('sim-merchant-tech-corner', 'tech-corner', 'electronics', 'Accessories', 'GenericSealedItem', 2500, 2500, true, array[
          'USB-C fast charger', 'Lightning cable 1m', 'USB-C cable 2m', 'Power bank 10000mAh', 'Power bank 20000mAh',
          'Bluetooth earbuds', 'Wireless headset', 'Phone tripod', 'Ring light mini', 'Laptop sleeve 15 inch',
          'Wireless mouse', 'Bluetooth speaker', 'Screen protector iPhone', 'Screen protector Samsung', 'Phone case clear',
          'Memory card 64GB', 'USB flash drive 128GB', 'HDMI cable', 'Travel adapter', 'Smart watch band'
        ]::text[]),
        ('sim-merchant-style-street', 'style-street', 'fashion', 'Clothes', 'SellerSpecific', 3500, 1200, true, array[
          'Plain black tee', 'Plain white tee', 'Printed cotton tee', 'Polo navy shirt', 'Oxford blue shirt',
          'Slim jeans blue', 'Chino beige pants', 'Track pants black', 'Hoodie charcoal', 'Denim jacket',
          'Summer dress floral', 'Wrap skirt wax', 'Ladies blouse cream', 'Ankara shirt men', 'Sports shorts',
          'Canvas sneakers', 'Leather sandals', 'Baseball cap', 'Pattern scarf', 'Boutique outfit set'
        ]::text[]),
        ('sim-merchant-careplus-pharmacy', 'careplus', 'pharmacy', 'Wellness', 'GenericSealedItem', 800, 450, false, array[
          'Paracetamol 500mg box', 'Vitamin C tablets', 'Oral rehydration salts', 'Digital thermometer', 'Hand sanitizer 500ml',
          'Antiseptic solution', 'Bandage roll', 'Cotton wool pack', 'Face mask pack', 'Baby wipes pack',
          'Cough syrup bottle', 'Nasal spray saline', 'Hydration tablets', 'Multivitamin syrup', 'Glucose powder',
          'First aid kit small', 'Pain relief gel', 'Zinc tablets', 'Eye drops sterile', 'Wellness care bundle'
        ]::text[]),
        ('sim-merchant-home-nest', 'home-nest', 'home', 'Home basics', 'GenericSealedItem', 900, 650, false, array[
          'Laundry detergent 1kg', 'Dish soap bottle', 'Floor cleaner 1L', 'Bleach 1L', 'Sponge pack',
          'Trash bags roll', 'Paper towel pack', 'Toilet tissue pack', 'Baby diaper small', 'Baby diaper medium',
          'Baby lotion bottle', 'Baby powder', 'Feeding bottle', 'Plastic storage box', 'Bath towel',
          'Pillow cover pair', 'Bedsheet single', 'Mosquito coil pack', 'LED bulb pack', 'Home starter bundle'
        ]::text[]),
        ('sim-merchant-sweet-crust-bakery', 'sweet-crust', 'bakery', 'Fresh bakery', 'PreparedFood', 400, 180, false, array[
          'Baguette classic', 'Pain au lait pack', 'Chocolate croissant', 'Butter croissant', 'Meat pie',
          'Chicken pie', 'Fish pie', 'Vanilla cupcake', 'Chocolate cupcake', 'Banana bread slice',
          'Coconut cookie pack', 'Peanut cookie pack', 'Birthday cake slice', 'Mini doughnut box', 'Cinnamon roll',
          'Cheese sandwich', 'Tuna sandwich', 'Iced coffee bottle', 'Fresh bissap bottle', 'Breakfast pastry box'
        ]::text[])
      ) as shops(merchant_id, slug, category, subcategory, product_kind, base_price, price_step, bargainable, names)
    ),
    expanded_products as (
      select
        'sim-product-' || slug || '-' || lpad(item_no::text, 2, '0') as id,
        merchant_id,
        product_name,
        product_kind,
        category,
        subcategory,
        base_price + ((item_no - 1) * price_step) as price_cfa,
        item_no,
        bargainable,
        slug
      from shop_catalog
      cross join lateral unnest(names) with ordinality as product_list(product_name, item_no)
    )
    insert into products (
      id, merchant_id, name, kind, status, category, subcategory, detail, base_price_cfa, option_hint,
      original_price_cfa, bargaining_enabled, bargain_floor_cfa, camera_verified, captured_at_label,
      sort_order, created_at, updated_at, version
    )
    select
      id,
      merchant_id,
      product_name,
      product_kind,
      'ACTIVE',
      category,
      subcategory,
      case
        when product_kind = 'PreparedFood' then product_name || ' prepared fresh for delivery; spice, side, and drink choices are available where applicable.'
        when category = 'electronics' then product_name || ' with sealed packaging, shop warranty note, and accessory check before dispatch.'
        when category = 'fashion' then product_name || ' with size/color option noted before packing; exchange condition can be tested in orders.'
        else product_name || ' stocked for delivery simulation with quantity, freshness, and packaging metadata.'
      end,
      price_cfa,
      case
        when product_kind = 'PreparedFood' then 'Choose spice level, side, and drink'
        when category = 'fashion' then 'Confirm size and color before checkout'
        when category = 'electronics' then 'Confirm model, color, and sealed box'
        else 'Standard pack, merchant verified'
      end,
      case when item_no % 4 = 0 then price_cfa + 500 else null end,
      bargainable and item_no % 3 = 0,
      case when bargainable and item_no % 3 = 0 then greatest(price_cfa - 700, 0) else null end,
      item_no % 2 = 0,
      case when item_no % 2 = 0 then 'Captured today' else null end,
      item_no * 10,
      now() - (item_no || ' hours')::interval,
      now(),
      0
    from expanded_products
    on conflict (id) do update set
      merchant_id = excluded.merchant_id,
      name = excluded.name,
      kind = excluded.kind,
      status = excluded.status,
      category = excluded.category,
      subcategory = excluded.subcategory,
      detail = excluded.detail,
      base_price_cfa = excluded.base_price_cfa,
      option_hint = excluded.option_hint,
      original_price_cfa = excluded.original_price_cfa,
      bargaining_enabled = excluded.bargaining_enabled,
      bargain_floor_cfa = excluded.bargain_floor_cfa,
      camera_verified = excluded.camera_verified,
      captured_at_label = excluded.captured_at_label,
      sort_order = excluded.sort_order,
      updated_at = excluded.updated_at;

    insert into products (
      id, merchant_id, name, kind, status, category, subcategory, detail, base_price_cfa, option_hint,
      original_price_cfa, bargaining_enabled, bargain_floor_cfa, camera_verified, captured_at_label,
      sort_order, created_at, updated_at, version
    )
    values
      ('sim-alt-jollof-bella', 'sim-merchant-bella-mama-kitchen', 'Jollof rice chicken', 'PreparedFood', 'ACTIVE', 'food', 'Jollof rice', 'Comparable jollof plate for price comparison across restaurants.', 3200, 'Medium pepper / chicken thigh', null, false, null, true, 'Captured today', 305, now() - interval '42 minutes', now(), 0),
      ('sim-alt-jollof-smoky', 'sim-merchant-smoky-grill-house', 'Jollof rice chicken', 'PreparedFood', 'ACTIVE', 'food', 'Jollof rice', 'Comparable jollof plate with smoky grilled chicken.', 3450, 'Hot pepper / grilled chicken', 3700, true, 3100, true, 'Captured today', 306, now() - interval '40 minutes', now(), 0),
      ('sim-alt-jollof-green', 'sim-merchant-green-bowl-cafe', 'Jollof rice chicken', 'PreparedFood', 'ACTIVE', 'food', 'Jollof rice', 'Comparable lighter jollof bowl with salad side.', 3350, 'Mild pepper / salad side', null, false, null, true, 'Captured today', 307, now() - interval '38 minutes', now(), 0),
      ('sim-alt-attieke-bella', 'sim-merchant-bella-mama-kitchen', 'Attieke grilled tilapia', 'PreparedFood', 'ACTIVE', 'food', 'Attieke fish', 'Comparable attieke fish plate for restaurant alternatives.', 4300, 'Medium fish / onion / pepper', null, false, null, true, 'Captured today', 315, now() - interval '36 minutes', now(), 0),
      ('sim-alt-attieke-smoky', 'sim-merchant-smoky-grill-house', 'Attieke grilled tilapia', 'PreparedFood', 'ACTIVE', 'food', 'Attieke fish', 'Comparable charcoal-grilled tilapia with attieke.', 4550, 'Large fish / alloco option', 4800, true, 4200, true, 'Captured today', 316, now() - interval '34 minutes', now(), 0),
      ('sim-alt-rice-family-5kg', 'sim-merchant-family-pantry', 'Rice bag 5kg', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Rice', 'Comparable 5kg rice bag for cheaper-shop suggestions.', 8800, 'Long grain / sealed bag', 9200, false, null, true, 'Captured today', 325, now() - interval '32 minutes', now(), 0),
      ('sim-alt-rice-lome-5kg', 'sim-merchant-lome-fresh-market', 'Rice bag 5kg', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Rice', 'Comparable 5kg market rice bag with live stock check.', 8400, 'Local market pack / sealed bag', null, true, 7900, true, 'Captured today', 326, now() - interval '30 minutes', now(), 0),
      ('sim-alt-rice-home-5kg', 'sim-merchant-home-nest', 'Rice bag 5kg', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Rice', 'Comparable household rice bag sold with home basics.', 9050, 'Family pack / sealed bag', null, false, null, false, null, 327, now() - interval '28 minutes', now(), 0),
      ('sim-alt-oil-family-1l', 'sim-merchant-family-pantry', 'Vegetable oil 1L', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Cooking oil', 'Comparable cooking oil bottle for price comparison.', 1550, '1L bottle / sealed', null, false, null, true, 'Captured today', 335, now() - interval '26 minutes', now(), 0),
      ('sim-alt-oil-lome-1l', 'sim-merchant-lome-fresh-market', 'Vegetable oil 1L', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Cooking oil', 'Comparable market cooking oil bottle.', 1450, '1L bottle / market stock', 1650, true, 1350, true, 'Captured today', 336, now() - interval '24 minutes', now(), 0),
      ('sim-alt-tomato-lome-2kg', 'sim-merchant-lome-fresh-market', 'Tomato basket 2kg', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Tomatoes', 'Comparable tomato basket for fresh produce alternatives.', 2350, '2kg basket / today harvest', null, false, null, true, 'Captured today', 345, now() - interval '22 minutes', now(), 0),
      ('sim-alt-tomato-family-2kg', 'sim-merchant-family-pantry', 'Tomato basket 2kg', 'GenericSealedItem', 'ACTIVE', 'grocery', 'Tomatoes', 'Comparable tomato basket stocked for pantry orders.', 2500, '2kg basket / pantry stock', null, false, null, true, 'Captured today', 346, now() - interval '20 minutes', now(), 0),
      ('sim-alt-powerbank-tech-10000', 'sim-merchant-tech-corner', 'Power bank 10000mAh', 'GenericSealedItem', 'ACTIVE', 'electronics', 'Power banks', 'Comparable 10000mAh power bank with sealed box.', 11200, 'Black / USB-C / sealed', 12000, true, 10500, true, 'Captured today', 355, now() - interval '18 minutes', now(), 0),
      ('sim-alt-powerbank-home-10000', 'sim-merchant-home-nest', 'Power bank 10000mAh', 'GenericSealedItem', 'ACTIVE', 'electronics', 'Power banks', 'Comparable household emergency power bank.', 10800, 'White / USB-C / sealed', null, false, null, false, null, 356, now() - interval '16 minutes', now(), 0),
      ('sim-alt-earbuds-tech', 'sim-merchant-tech-corner', 'Bluetooth earbuds', 'GenericSealedItem', 'ACTIVE', 'electronics', 'Audio', 'Comparable wireless earbuds from electronics shop.', 16800, 'Black case / sealed', 18000, true, 15800, true, 'Captured today', 365, now() - interval '14 minutes', now(), 0),
      ('sim-alt-earbuds-style', 'sim-merchant-style-street', 'Bluetooth earbuds', 'GenericSealedItem', 'ACTIVE', 'electronics', 'Audio', 'Comparable earbuds sold as a fashion accessory.', 17500, 'White case / sealed', null, false, null, false, null, 366, now() - interval '12 minutes', now(), 0),
      ('sim-alt-tee-style-black', 'sim-merchant-style-street', 'Plain black tee', 'SellerSpecific', 'ACTIVE', 'fashion', 'T-shirts', 'Comparable plain black tee from boutique stock.', 5200, 'Size M / black', null, false, null, true, 'Captured today', 375, now() - interval '10 minutes', now(), 0),
      ('sim-alt-tee-market-black', 'merchant-tokoin-urban-wear', 'Plain black tee', 'SellerSpecific', 'ACTIVE', 'fashion', 'T-shirts', 'Comparable plain black tee from Tokoin Urban Wear.', 4900, 'Size M / black', 5500, true, 4500, true, 'Captured today', 376, now() - interval '9 minutes', now(), 0),
      ('sim-alt-sanitizer-careplus', 'sim-merchant-careplus-pharmacy', 'Hand sanitizer 500ml', 'GenericSealedItem', 'ACTIVE', 'pharmacy', 'Sanitizer', 'Comparable 500ml sanitizer from pharmacy stock.', 2100, '500ml bottle / sealed', null, false, null, true, 'Captured today', 385, now() - interval '8 minutes', now(), 0),
      ('sim-alt-sanitizer-home', 'sim-merchant-home-nest', 'Hand sanitizer 500ml', 'GenericSealedItem', 'ACTIVE', 'pharmacy', 'Sanitizer', 'Comparable 500ml sanitizer from home supply stock.', 1950, '500ml bottle / sealed', 2200, false, null, true, 'Captured today', 386, now() - interval '7 minutes', now(), 0),
      ('sim-alt-croissant-sweet', 'sim-merchant-sweet-crust-bakery', 'Butter croissant', 'PreparedFood', 'ACTIVE', 'bakery', 'Pastry', 'Comparable butter croissant baked today.', 850, 'Single pastry / warm box', null, false, null, true, 'Captured today', 395, now() - interval '6 minutes', now(), 0),
      ('sim-alt-croissant-green', 'sim-merchant-green-bowl-cafe', 'Butter croissant', 'PreparedFood', 'ACTIVE', 'bakery', 'Pastry', 'Comparable cafe croissant for bakery alternatives.', 950, 'Single pastry / cafe pack', null, false, null, true, 'Captured today', 396, now() - interval '5 minutes', now(), 0)
    on conflict (id) do update set
      merchant_id = excluded.merchant_id,
      name = excluded.name,
      kind = excluded.kind,
      status = excluded.status,
      category = excluded.category,
      subcategory = excluded.subcategory,
      detail = excluded.detail,
      base_price_cfa = excluded.base_price_cfa,
      option_hint = excluded.option_hint,
      original_price_cfa = excluded.original_price_cfa,
      bargaining_enabled = excluded.bargaining_enabled,
      bargain_floor_cfa = excluded.bargain_floor_cfa,
      camera_verified = excluded.camera_verified,
      captured_at_label = excluded.captured_at_label,
      sort_order = excluded.sort_order,
      updated_at = excluded.updated_at;
  end if;

  if to_regclass('public.catalog_images') is not null then
    insert into catalog_images (id, product_id, storage_key, public_url, source, moderation_status, created_at, updated_at, version)
    select
      'sim-image-' || p.id,
      p.id,
      'seed/catalog/' || p.id || '.jpg',
      'https://example.test/seed/catalog/' || p.id || '.jpg',
      case when p.camera_verified then 'SellerLiveCapture' else 'SupplierReference' end,
      'Approved',
      now() - interval '1 day',
      now(),
      0
    from products p
    where p.id like 'sim-product-%' or p.id like 'sim-alt-%'
    on conflict (id) do update set
      product_id = excluded.product_id,
      storage_key = excluded.storage_key,
      public_url = excluded.public_url,
      source = excluded.source,
      moderation_status = excluded.moderation_status,
      updated_at = excluded.updated_at;
  end if;

  if to_regclass('public.catalog_promotions') is not null then
    insert into catalog_promotions (id, headline, title, subtitle, product_id, sort_order, active)
    values
      ('sim-promo-bella-jollof', 'Lunch ready', 'Jollof rice chicken', 'Hot plate from Bella Mama Kitchen', 'sim-product-bella-mama-01', 110, true),
      ('sim-promo-smoky-family', 'Family grill', 'Family grill tray', 'Large tray for shared orders', 'sim-product-smoky-grill-20', 120, true),
      ('sim-promo-green-combo', 'Fresh cafe', 'Cafe lunch combo', 'Light meal with drink options', 'sim-product-green-bowl-20', 130, true),
      ('sim-promo-market-bundle', 'Fresh today', 'Market soup bundle', 'Produce bundle for home cooking', 'sim-product-lome-fresh-20', 140, true),
      ('sim-promo-tech-power', 'Charged up', 'Power bank 20000mAh', 'Sealed accessory for same-day dispatch', 'sim-product-tech-corner-05', 150, true),
      ('sim-promo-bakery-breakfast', 'Baked today', 'Breakfast pastry box', 'Fresh bakery box for morning orders', 'sim-product-sweet-crust-20', 160, true)
    on conflict (id) do update set
      headline = excluded.headline,
      title = excluded.title,
      subtitle = excluded.subtitle,
      product_id = excluded.product_id,
      sort_order = excluded.sort_order,
      active = excluded.active;
  end if;

  if to_regclass('public.product_customization_groups') is not null then
    insert into product_customization_groups (id, product_id, name, min_choices, max_choices, sort_order, active)
    select 'sim-custom-' || p.id || '-spice', p.id, 'Spice level', 0, 1, 10, true
    from products p
    where (p.id like 'sim-product-%' or p.id like 'sim-alt-%') and p.kind = 'PreparedFood'
    union all
    select 'sim-custom-' || p.id || '-extras', p.id, 'Extras', 0, 3, 20, true
    from products p
    where (p.id like 'sim-product-%' or p.id like 'sim-alt-%') and p.kind = 'PreparedFood'
    on conflict (id) do update set
      product_id = excluded.product_id,
      name = excluded.name,
      min_choices = excluded.min_choices,
      max_choices = excluded.max_choices,
      sort_order = excluded.sort_order,
      active = excluded.active;
  end if;

  if to_regclass('public.product_customization_options') is not null then
    insert into product_customization_options (id, group_id, name, price_delta_cfa, sort_order, active)
    select 'sim-option-' || g.id || '-none', g.id, 'No pepper', 0, 10, true
    from product_customization_groups g
    where g.id like 'sim-custom-%-spice'
    union all
    select 'sim-option-' || g.id || '-medium', g.id, 'Medium pepper', 0, 20, true
    from product_customization_groups g
    where g.id like 'sim-custom-%-spice'
    union all
    select 'sim-option-' || g.id || '-hot', g.id, 'Hot pepper', 0, 30, true
    from product_customization_groups g
    where g.id like 'sim-custom-%-spice'
    union all
    select 'sim-option-' || g.id || '-plantain', g.id, 'Extra plantain', 500, 10, true
    from product_customization_groups g
    where g.id like 'sim-custom-%-extras'
    union all
    select 'sim-option-' || g.id || '-drink', g.id, 'Cold bissap', 500, 20, true
    from product_customization_groups g
    where g.id like 'sim-custom-%-extras'
    union all
    select 'sim-option-' || g.id || '-protein', g.id, 'Extra protein', 900, 30, true
    from product_customization_groups g
    where g.id like 'sim-custom-%-extras'
    on conflict (id) do update set
      group_id = excluded.group_id,
      name = excluded.name,
      price_delta_cfa = excluded.price_delta_cfa,
      sort_order = excluded.sort_order,
      active = excluded.active;
  end if;
end $$;

commit;
