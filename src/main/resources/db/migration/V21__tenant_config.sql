create table tenant_config (
  id bigserial primary key,
  tenant_id bigint not null unique,
  hold_ttl_minutes int not null default 15 check (hold_ttl_minutes > 0),
  created_at timestamptz not null default now()
);
