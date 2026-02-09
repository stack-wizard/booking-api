create table api_token (
  id bigserial primary key,
  tenant_id bigint not null,
  name text not null,
  token_hash text not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (token_hash)
);
