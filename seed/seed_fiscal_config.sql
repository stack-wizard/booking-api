-- Fiscalization + OPERA mapping seed data (manual run, not managed by Flyway)
-- Prerequisites:
--   1) all Flyway migrations applied (including V43..V47)
--   2) tenant 1 exists
--   3) optional: seed/seed_booking_data.sql already loaded
--
-- Run:
--   psql "$DATABASE_URL" -f seed/seed_fiscal_config.sql

-- Generic tenant integration config for OFIS fiscalization.
-- Provider credentials are used as HTTP Basic auth username/password.
insert into tenant_integration_config (
  tenant_id,
  integration_type,
  provider,
  active,
  base_url,
  oauth_path,
  request_path,
  hotel_code,
  hotel_name,
  legal_owner,
  property_tax_number,
  country_code,
  country_name,
  application_name,
  client_id,
  client_secret,
  authenticity_token,
  callback_auth_token
)
values (
  1,
  'FISCALIZATION',
  'OFIS',
  true,
  'https://ofis.example.local',
  null,
  '/api/fiscal/events',
  'DH',
  'MIKOS',
  'Mikos',
  '97085726935',
  'HR',
  'Croatia',
  'BookingAPI',
  'ofis_test_user',
  'ofis_test_password',
  null,
  null
)
on conflict (tenant_id, integration_type, provider) do update
set active = excluded.active,
    base_url = excluded.base_url,
    oauth_path = excluded.oauth_path,
    request_path = excluded.request_path,
    hotel_code = excluded.hotel_code,
    hotel_name = excluded.hotel_name,
    legal_owner = excluded.legal_owner,
    property_tax_number = excluded.property_tax_number,
    country_code = excluded.country_code,
    country_name = excluded.country_name,
    application_name = excluded.application_name,
    client_id = excluded.client_id,
    client_secret = excluded.client_secret,
    authenticity_token = excluded.authenticity_token,
    callback_auth_token = excluded.callback_auth_token;

-- Business premise (Croatian: poslovni prostor)
insert into fiscal_business_premise (tenant_id, code, name, active)
values
  (1, '1', 'Internet prodaja', true)
on conflict (tenant_id, code) do update
set name = excluded.name,
    active = excluded.active;

-- Cash register / issuing device (Croatian: naplatni uredjaj)
insert into fiscal_cash_register (tenant_id, business_premise_id, code, terminal_id, active)
select
  1,
  bp.id,
  '3',
  'f629efc444174e139162f11b4bca7d9700000003',
  true
from fiscal_business_premise bp
where bp.tenant_id = 1
  and bp.code = '1'
on conflict (tenant_id, business_premise_id, code) do update
set terminal_id = excluded.terminal_id,
    active = excluded.active;

-- Optional cashier user for CASHIER issuance mode.
-- Password hash reused from existing admin seed for convenience.
insert into app_user (tenant_id, username, password_hash, employee_number, role)
values
  (1, 'cashier1', '$2a$10$tEIqW6jMHt/nglw5VP4RCugx3SSYYoawx4mAQoYJraY4KET3revXK', '97085726935', 'CASHIER')
on conflict (username) do update
set tenant_id = excluded.tenant_id,
    employee_number = excluded.employee_number,
    role = excluded.role;

-- Use existing products and enforce requested tax setup:
-- tax1 = 25%, tax2 = 0% (tax2 avoided).
update product
set tax1_percent = 25.0000,
    tax2_percent = 0.0000
where tenant_id = 1;

-- OPERA fiscal mappings
-- Charge mapping fallback for all products.
delete from opera_fiscal_charge_mapping
where tenant_id = 1
  and product_id is null
  and product_type is null
  and description = 'Default charge line';

insert into opera_fiscal_charge_mapping (
  tenant_id,
  product_id,
  product_type,
  trx_code,
  trx_type,
  trx_code_type,
  trx_group,
  trx_sub_group,
  description,
  bucket_code,
  bucket_type,
  bucket_value,
  bucket_description,
  active,
  priority
)
values
  (1, null, null, '3100', 'C', 'L', null, null, 'Default charge line', 'KPD_1', 'FLIP_KPD', '56.11.01', 'KPD 56.11.01', true, 100);

-- Payment type -> OPERA transaction code mappings.
insert into opera_fiscal_payment_mapping (
  tenant_id,
  payment_type,
  trx_code,
  trx_type,
  trx_code_type,
  trx_group,
  trx_sub_group,
  description,
  bucket_code,
  bucket_type,
  bucket_value,
  bucket_description,
  active
)
values
  (1, 'CASH', '9001', 'FC', 'O', 'PAY', 'Gotovina/Novcanice', 'G', 'G', 'FLIP_PAY_SUBTYPE', 'G', 'Gotovina/Novcanice', true),
  (1, 'CARD', '9002', 'FC', 'O', 'PAY', 'Kartica', 'K', 'K', 'FLIP_PAY_SUBTYPE', 'K', 'Kartica', true),
  (1, 'BANK_TRANSFER', '9003', 'FC', 'O', 'PAY', 'Transakcijski racun', 'T', 'T', 'FLIP_PAY_SUBTYPE', 'T', 'Transakcijski racun', true),
  (1, 'ROOM_CHARGE', '9004', 'FC', 'O', 'PAY', 'Room charge', 'R', 'R', 'FLIP_PAY_SUBTYPE', 'R', 'Room charge', true)
on conflict (tenant_id, payment_type) do update
set trx_code = excluded.trx_code,
    trx_type = excluded.trx_type,
    trx_code_type = excluded.trx_code_type,
    trx_group = excluded.trx_group,
    trx_sub_group = excluded.trx_sub_group,
    description = excluded.description,
    bucket_code = excluded.bucket_code,
    bucket_type = excluded.bucket_type,
    bucket_value = excluded.bucket_value,
    bucket_description = excluded.bucket_description,
    active = excluded.active;

-- Tax percentage -> OPERA tax generate mapping.
delete from opera_fiscal_tax_mapping
where tenant_id = 1
  and tax_percent <> 25.0000;

insert into opera_fiscal_tax_mapping (
  tenant_id,
  tax_percent,
  generate_trx_code,
  tax_name,
  active
)
values
  (1, 25.0000, '3', '2', true)
on conflict (tenant_id, tax_percent) do update
set generate_trx_code = excluded.generate_trx_code,
    tax_name = excluded.tax_name,
    active = excluded.active;

-- Default UDF mappings used by Croatian OFIS provider.
delete from opera_fiscal_udf_mapping
where tenant_id = 1
  and udf_name in ('FLIP_PARTNER_TAX1', 'FLIP_PARTNER_TAX2', 'FLIP_PARTNER_TAX3');

insert into opera_fiscal_udf_mapping (
  tenant_id,
  udf_name,
  udf_value,
  active,
  sort_order
)
values
  (1, 'FLIP_PARTNER_TAX1', '1', true, 10),
  (1, 'FLIP_PARTNER_TAX2', '3', true, 20),
  (1, 'FLIP_PARTNER_TAX3', 'P', true, 30);

-- Quick verification queries:
-- select tenant_id, integration_type, provider, hotel_code, hotel_name, legal_owner, property_tax_number, country_code, country_name, application_name, active, base_url, request_path, client_id from tenant_integration_config where tenant_id = 1 and provider = 'OFIS';
-- select tenant_id, code, name, active from fiscal_business_premise where tenant_id = 1;
-- select tenant_id, business_premise_id, code, terminal_id, active from fiscal_cash_register where tenant_id = 1;
-- select id, username, employee_number, role from app_user where tenant_id = 1 and username = 'cashier1';
-- select tenant_id, payment_type, trx_code from opera_fiscal_payment_mapping where tenant_id = 1;
