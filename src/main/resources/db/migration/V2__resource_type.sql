create table resource_type (
  id          bigserial primary key,
  tenant_id   bigint not null,
  code        text not null,
  name        text not null,

  -- optional hint: NIGHT / DAY / SLOT / DURATION
  default_time_model text null,

  attrs       jsonb not null default '{}'::jsonb,
  created_at  timestamptz not null default now(),

  unique (tenant_id, code)
);
