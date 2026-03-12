do $$
begin
  if exists (
      select 1
      from information_schema.columns
      where table_schema = 'public'
        and table_name = 'tenant_integration_config'
        and column_name = 'payment_new_path'
  ) and not exists (
      select 1
      from information_schema.columns
      where table_schema = 'public'
        and table_name = 'tenant_integration_config'
        and column_name = 'request_path'
  ) then
    alter table tenant_integration_config
      rename column payment_new_path to request_path;
  end if;
end $$;
