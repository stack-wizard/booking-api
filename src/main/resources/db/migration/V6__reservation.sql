create table reservation (
  id                    bigserial primary key,
  tenant_id              bigint not null,

  product_id             bigint null,

  -- what customer selected: can be POOL or EXACT
  requested_resource_id  bigint not null references resource(id) on delete restrict,

  starts_at              timestamptz not null,
  ends_at                timestamptz not null check (ends_at > starts_at),

  status                 text not null default 'HOLD'
                         check (status in ('HOLD','CONFIRMED','CANCELLED')),

  adults                 int not null default 0 check (adults >= 0),
  children               int not null default 0 check (children >= 0),
  infants                int not null default 0 check (infants >= 0),

  customer_name          text null,
  attrs                  jsonb not null default '{}'::jsonb,

  created_at             timestamptz not null default now()
);