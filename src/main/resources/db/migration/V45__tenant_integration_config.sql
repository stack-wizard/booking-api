do $$
begin
  if to_regclass('tenant_payment_provider_config') is not null
     and to_regclass('tenant_integration_config') is null then
    alter table tenant_payment_provider_config
      rename to tenant_integration_config;
  end if;
end $$;

alter table tenant_integration_config
  add column if not exists integration_type text;

update tenant_integration_config
set integration_type = case
  when upper(provider) = 'OFIS' then 'FISCALIZATION'
  else 'PAYMENT'
end
where integration_type is null;

alter table tenant_integration_config
  alter column integration_type set not null;

alter table tenant_integration_config
  drop constraint if exists tenant_payment_provider_config_tenant_id_provider_key;

alter table tenant_integration_config
  drop constraint if exists tenant_integration_config_tenant_id_provider_key;

drop index if exists idx_tenant_payment_provider_cfg_tenant;
create index if not exists idx_tenant_integration_cfg_tenant
  on tenant_integration_config (tenant_id);

drop index if exists tenant_payment_provider_config_tenant_id_provider_key;
drop index if exists tenant_integration_config_tenant_id_provider_key;

create unique index if not exists uq_tenant_integration_cfg_tenant_type_provider
  on tenant_integration_config (tenant_id, integration_type, provider);

alter table tenant_integration_config
  drop constraint if exists tenant_integration_config_integration_type_check;

alter table tenant_integration_config
  add constraint tenant_integration_config_integration_type_check
  check (integration_type in ('PAYMENT', 'BOOKING', 'FISCALIZATION'));
