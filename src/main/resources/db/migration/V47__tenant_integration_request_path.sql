do $$
begin
  if to_regclass('tenant_integration_config') is not null
     and exists (
       select 1
       from pg_attribute
       where attrelid = to_regclass('tenant_integration_config')
         and attname = 'payment_new_path'
         and not attisdropped
     ) and not exists (
       select 1
       from pg_attribute
       where attrelid = to_regclass('tenant_integration_config')
         and attname = 'request_path'
         and not attisdropped
     ) then
    alter table tenant_integration_config
      rename column payment_new_path to request_path;
  end if;
end $$;
