alter table tenant_integration_config
  add column if not exists public_access_url_template varchar(1000);
