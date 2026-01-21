create table price_profile (
  id         bigserial primary key,
  tenant_id  bigint not null,
  name       text not null,
  currency   text not null,
  created_at timestamptz not null default now(),
  unique (tenant_id, name)
);

create table price_profile_date (
  id                bigserial primary key,
  price_profile_id  bigint not null references price_profile(id) on delete cascade,
  date_from         date not null,
  date_to           date not null,
  description       text null,
  created_at        timestamptz not null default now(),
  check (date_to >= date_from)
);

alter table price_list
  drop column currency,
  add column price_profile_id bigint null references price_profile(id) on delete restrict,
  add column price_profile_date_id bigint null references price_profile_date(id) on delete restrict;

create index if not exists idx_price_list_profile_date on price_list(price_profile_date_id);
