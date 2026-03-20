alter table tenant_integration_config
  add column if not exists enterprise_id text null;
