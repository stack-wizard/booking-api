create table app_user (
  id         bigserial primary key,
  tenant_id  bigint null,
  username   text not null,
  password_hash text not null,
  role       text not null check (role in ('SUPER_ADMIN','ADMIN','STAFF')),
  created_at timestamptz not null default now(),
  unique (username)
);
