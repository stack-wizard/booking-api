alter table tenant_integration_config
  add column if not exists hotel_code text null;

alter table tenant_integration_config
  add column if not exists property_tax_number text null;

alter table tenant_integration_config
  add column if not exists country_code text null;

alter table tenant_integration_config
  add column if not exists country_name text null;

alter table tenant_integration_config
  add column if not exists application_name text null;
