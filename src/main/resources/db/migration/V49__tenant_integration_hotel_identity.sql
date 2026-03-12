alter table tenant_integration_config
  add column if not exists hotel_name text null;

alter table tenant_integration_config
  add column if not exists legal_owner text null;
