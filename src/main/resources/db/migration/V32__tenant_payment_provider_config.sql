create table if not exists tenant_payment_provider_config (
  id                    bigserial primary key,
  tenant_id             bigint not null,
  provider              text not null,
  active                boolean not null default true,
  base_url              text null,
  oauth_path            text null,
  payment_new_path      text null,
  client_id             text null,
  client_secret         text null,
  authenticity_token    text null,
  callback_auth_token   text null,
  created_at            timestamptz not null default now(),
  unique (tenant_id, provider)
);

create index if not exists idx_tenant_payment_provider_cfg_tenant on tenant_payment_provider_config (tenant_id);
